package io.onboardkit.ads.erain

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdmobHelper
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.AdNativeState
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.interstitial.InterLoadAndShowOptions
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.util.SharePreferenceUtils
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.LoadAdError
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.ads.awaitNativeRequestWindow
import io.onboardkit.ads.canStartNativeRequest
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.trackkit.PlacementRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.ads.module.helper.AdSkipReason as SdkAdSkipReason

/**
 * Default [OnboardingAdProvider]: a thin adapter over the `:ads` helper layer.
 *
 * Buffering, expiry, in-flight dedup and the show contract all live in
 * [NativeAdPreload] / [InterstitialAdManager]; this class only translates between the
 * onboarding flow's placement/callback vocabulary and the module's. Telemetry stays with
 * the flow's own AdTelemetry, so the managers are called with reporting off.
 */
class ERainAdProvider(
    /**
     * How long one ad unit may take before the waterfall moves to the next floor.
     *
     * `30 s` is the audited `REQUEST_AD_TIMEOUT`; the whole waterfall is bounded by the caller's
     * own budget (`ob_splash_ad_budget_ms`, `60 s`, the audited `LOAD_AD_TIMEOUT`). Lowering this
     * trades fill rate for speed — measure before you do.
     */
    private val tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS,
) : OnboardingAdProvider {

    init {
        // Flow placements preload at their own transitions and stay independent of the
        // content buffer's activation and shared frequency interval.
        InterstitialAutoBuffer.reserve(
            AdPlacement.SplashInterstitial.key,
            AdPlacement.QuestionInterstitial.key,
            AdPlacement.AfterOnboardingInterstitial.key,
        )
    }

    private val preload = NativeAdPreload.getInstance()

    /**
     * One observer per placement — the screen currently showing it. A list here grew a wrapper per
     * recycled fragment, and every stale wrapper re-reported load events against a dead Activity.
     */
    private val listeners = ConcurrentHashMap<String, AdEventListener>()

    /** Buffer observers, one per placement; also the roster of keys this provider owns. */
    private val nativeBridges = ConcurrentHashMap<String, AdCallback>()
    private val interKeys = ConcurrentHashMap.newKeySet<String>()

    private class NativeBinding(
        val activity: Activity,
        val owner: LifecycleOwner,
        val container: FrameLayout,
        val helper: NativeAdHelper,
    ) {
        var inBind = false
        var justBound = false
    }
    private val nativeBindings = mutableMapOf<String, NativeBinding>()
    private val nativeConfigs = mutableMapOf<String, NativeAdConfig>()
    private val nativeOwners = mutableMapOf<String, LifecycleOwner>()
    private val nativeOwnerObservers = mutableMapOf<String, LifecycleEventObserver>()
    private val deferredNativeFailures = mutableSetOf<String>()
    private val pendingNativeBinds = mutableSetOf<String>()
    private val failedNativeLoads = mutableSetOf<String>()
    private class QueuedNative(val activity: Activity, val job: Job)
    private val queuedNatives = mutableMapOf<String, QueuedNative>()

    /**
     * Presentation style per placement, resolved from the ad config at request time.
     *
     * The flow hands this provider ad unit ids, not config keys, so the style is captured while the
     * ids are still in hand and read back at bind. Binding with no style ignored `components`,
     * `colorCTA` and `heightCTA` for every onboarding native — one edit to the ad config moved
     * every other slot in the app and left these behind.
     */
    private val nativeStyles = ConcurrentHashMap<String, NativeAdStyle>()

    // AdGate reads the Entitlement port, so this stays correct whether or not :billingkit ships.
    override fun isPremium(context: Context): Boolean = AdGate.isPurchased(context)

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        val key = request.placement.key
        if (nativeBindings[key]?.helper?.isRestoringPresentation == true) return
        val queued = queuedNatives[key]
        if (queued?.activity === activity && queued.job.isActive) return
        queued?.job?.cancel()
        if (isNativeReady(request.placement) || preload.isPreloadInProgress(key) ||
            activity.canStartNativeRequest(request.allowWhileVisible)) {
            preloadNativeNow(activity, request)
            return
        }
        val owner = activity as? LifecycleOwner ?: run { notifyNativeFailure(key); return }
        val job = owner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            if (activity.awaitNativeRequestWindow()) {
                // Entitlement/host/remote authority can change while focus is absent.
                if (io.onboardkit.OnboardingSdk.isReady() &&
                    io.onboardkit.OnboardingSdk.guard().skipReason(activity, request.placement, request.unit) != null) {
                    failedNativeLoads.add(key)
                    notifyNativeFailure(key)
                } else preloadNativeNow(activity, request)
            }
        }
        val pending = QueuedNative(activity, job)
        queuedNatives[key] = pending
        job.invokeOnCompletion { if (queuedNatives[key] === pending) queuedNatives.remove(key) }
        job.start()
    }

    private fun preloadNativeNow(activity: Activity, request: NativeAdRequest) {
        val key = request.placement.key
        if (nativeBindings[key]?.helper?.isRestoringPresentation == true) return
        deferredNativeFailures.remove(key)
        failedNativeLoads.remove(key)
        val ids = request.unit.loadOrder
        if (ids.isEmpty()) {
            ObLog.w(ObLog.Section.LOAD, "$key skip — no usable ad unit id")
            return
        }
        // GMA's paid-event callback only knows the ad unit id; this is the only place that knows
        // which onboarding screen asked for it.
        ids.forEach { PlacementRegistry.register(it, key) }
        ids.firstNotNullOfOrNull { AdRemoteConfig.getInstance().unitForAdId(it) }
            ?.let { nativeStyles[key] = it.toNativeStyle() }
        val config = nativeConfig(ids, request.layoutRes)
        nativeConfigs[key] = config
        ensureNativeBridge(key)
        val covered =
            preload.preloadWithKeyIfEmpty(key, activity, config)
        // A purchased/offline no-op must still answer, or a waiting screen shimmers forever
        if (!covered && !isNativeReady(request.placement)) {
            failedNativeLoads.add(key)
            notifyNativeFailure(key)
        }
    }

    override fun isNativeReady(placement: AdPlacement): Boolean =
        preload.getAdNative(placement.key) != null

    override fun isNativeLoading(placement: AdPlacement): Boolean =
        preload.isPreloadInProgress(placement.key) || queuedNatives[placement.key]?.job?.isActive == true

    override fun isNativeLoadFailed(placement: AdPlacement): Boolean =
        placement.key in failedNativeLoads && !isNativeReady(placement) && !isNativeLoading(placement) &&
            nativeBindings[placement.key]?.helper?.isRestoringPresentation != true

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        val key = placement.key
        listener?.let { listeners[key] = it }
        val frame = container as? FrameLayout ?: return false
        val owner = frame.findViewTreeLifecycleOwner() ?: activity as? LifecycleOwner ?: return false
        if (nativeOwners[key] !== owner) {
            detachNativeOwner(key)
            nativeOwners[key] = owner
            var observing = false
            val observer = LifecycleEventObserver { _, event ->
                if (nativeOwners[key] === owner) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        detachNativeOwner(key)
                        // The helper receives the same destruction event and transfers its ad
                        // on configuration recreation before releasing its own UI references.
                        nativeBindings.remove(key)
                        deferredNativeFailures.remove(key)
                        listeners.remove(key)
                    } else if (observing && event == Lifecycle.Event.ON_RESUME) {
                        if (deferredNativeFailures.remove(key)) {
                            pendingNativeBinds.remove(key)
                            notifyListener(key) { it.onFailedToLoad() }
                        } else if (key in pendingNativeBinds && !helperAwaitsNative(key) &&
                            preload.getAdNative(key) != null) notifyListener(key) { it.onLoaded() }
                    }
                }
            }
            nativeOwnerObservers[key] = observer
            owner.lifecycle.addObserver(observer)
            observing = true
        }
        pendingNativeBinds.add(key)
        deferredNativeFailures.remove(key)
        val current = nativeBindings[key]
        val binding = if (current?.activity === activity && current.container === frame && current.owner === owner) current else {
            current?.helper?.destroy()
            val config = nativeConfigs[key] ?: return false
            val helper = NativeAdHelper(activity, owner, config)
                .setNativeContentView(frame)
                .setNativeStyle(nativeStyles[key])
                .also { it.placement = key; it.reportTelemetry = false }
            (shimmer as? ShimmerFrameLayout)?.let(helper::setShimmerLayoutView)
            NativeBinding(activity, owner, frame, helper).also { created ->
                nativeBindings[key] = created
                helper.registerAdListener(object : AdCallback() {
                    override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                        pendingNativeBinds.remove(key)
                        created.justBound = true
                        if (!created.inBind) {
                            try { notifyListener(key) { it.onLoaded() } }
                            finally { created.justBound = false }
                        }
                        // Existing onboarding dwell timers use this bind signal, not paid analytics.
                        notifyListener(key) { it.onImpression() }
                    }
                    override fun onAdFailedToLoad(error: LoadAdError?) {
                        pendingNativeBinds.add(key)
                        notifyNativeFailure(key)
                    }
                    override fun onAdClicked() { notifyListener(key) { it.onClicked() } }
                    override fun onAdOpened() { notifyListener(key) { it.onAdOpened() } }
                })
            }
        }
        // A helper may finish an in-flight load on resume before the legacy onLoaded callback
        // asks bindNative again. Acknowledge that bind instead of consuming another ad.
        if (binding.justBound) {
            pendingNativeBinds.remove(key)
            binding.justBound = false
            return true
        }
        binding.inBind = true
        return try {
            binding.helper.bindAvailable().also {
                if (it) {
                    pendingNativeBinds.remove(key)
                    binding.justBound = false
                }
            }
        } finally { binding.inBind = false }
    }

    private fun detachNativeOwner(key: String) {
        pendingNativeBinds.remove(key)
        deferredNativeFailures.remove(key)
        val owner = nativeOwners.remove(key)
        nativeOwnerObservers.remove(key)?.let { owner?.lifecycle?.removeObserver(it) }
    }

    override fun releaseNative(placement: AdPlacement) {
        val key = placement.key
        queuedNatives.remove(key)?.job?.cancel()
        nativeBindings.remove(key)?.helper?.destroy()
        detachNativeOwner(key)
        deferredNativeFailures.remove(key)
        nativeBridges.remove(key)?.let { preload.unregisterAdCallback(key, it) }
        listeners.remove(key)
        // A screen departure cannot cancel the process-owned request or drop its unused fill.
    }

    override fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener?,
    ) {
        val key = placement.key
        listener?.let { listeners[key] = it }
        interKeys.add(key)
        InterstitialAdManager.load(
            context,
            key,
            unit.loadOrder,
            InterLoadOptions(tierTimeoutMs = tierTimeoutMs, reportTelemetry = false),
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    ObLog.d(ObLog.Section.LOAD, "$key inter FILLED")
                    notifyListener(key) { it.onLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    ObLog.w(ObLog.Section.LOAD, "$key inter UNFILLED")
                    notifyListener(key) { it.onFailedToLoad() }
                }
                // No onAdClicked here: the show path already forwards clicks, and the manager
                // pings this same bridge — overriding both would deliver every click twice
            },
        )
    }

    override fun isInterstitialReady(placement: AdPlacement): Boolean =
        InterstitialAdManager.isReady(placement.key)

    /**
     * Maps the store's show contract onto the flow's two moments: `onComplete` without a
     * preceding skip is the commit — the ad is on screen and the next screen may start
     * underneath it — while a skip suppresses `onNextAction` entirely.
     *
     * The mode is pinned per show rather than read from [InterstitialAdManager.defaultNextAction]
     * because it is what [ObInterstitialCallback.onNextAction] *means*: `UnderAd` is the only mode
     * under which `onComplete` says "the ad is on screen". An app that prefers `AfterDismiss` for
     * its own placements must not be able to redefine that. A flow screen whose destination has to
     * wait for the dismissal leaves `onNext` unused instead — see
     * [io.onboardkit.ads.NextScreenTiming].
     */
    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    ) {
        val key = placement.key
        interKeys.add(key)
        InterstitialAdManager.show(
            activity,
            key,
            interstitialCallback(key, callback),
            reportTelemetry = false,
            nextAction = InterNextAction.UnderAd,
        )
    }

    override fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        interKeys.add(placement.key)
        InterstitialAdManager.loadAndShow(
            activity,
            placement.key,
            unit.loadOrder,
            interstitialCallback(placement.key, callback),
            InterLoadAndShowOptions(
                timeoutMs = timeoutMs,
                reportTelemetry = false,
                nextAction = InterNextAction.UnderAd,
            ),
        )
    }

    private fun interstitialCallback(
        key: String,
        callback: ObInterstitialCallback
    ): InterShowCallback =
        object : InterShowCallback() {
            private val skipped = AtomicBoolean(false)

            override fun onSkipped(reason: SdkAdSkipReason) {
                skipped.set(true)
                ObLog.w(ObLog.Section.SHOW, "$key skipped: ${reason.key}")
                callback.onAdSkipped(mapReason(reason))
            }

            override fun onComplete() {
                if (!skipped.get()) callback.onNextAction()
            }

            override fun onClosed() {
                callback.onAdClosed()
            }

            override fun onClicked() {
                notifyListener(key) { it.onClicked() }
            }
        }

    override fun lastInterstitialShownAtMs(context: Context): Long =
        runCatching { SharePreferenceUtils.getLastImpressionInterstitialTime(context) }
            .getOrDefault(0L)

    override fun clicksToday(context: Context, adUnitId: String): Int =
        runCatching { AdmobHelper.getNumClickAdsPerDay(context, adUnitId) }.getOrDefault(0)

    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        ERainAd.getInstance().loadBanner(
            activity,
            unit.id,
            object : AdCallback() {
                override fun onAdLoaded() {
                    listener?.onLoaded()
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    listener?.onFailedToLoad()
                }

                override fun onAdClicked() {
                    listener?.onClicked()
                }
            },
        )
    }

    override fun suppressAppResume(activityClass: Class<out Activity>) {
        runCatching { AppOpenManager.getInstance().disableAppResumeWithActivity(activityClass) }
    }

    override fun releaseAll() {
        queuedNatives.values.toList().forEach { it.job.cancel() }
        queuedNatives.clear()
        // Per-key release: the stores are process-wide and the host app owns keys of its own
        (nativeBridges.keys + nativeConfigs.keys).toSet().forEach { preload.release(it) }
        nativeBridges.clear()
        nativeBindings.values.forEach { it.helper.destroy() }
        nativeBindings.clear()
        nativeOwners.keys.toList().forEach(::detachNativeOwner)
        deferredNativeFailures.clear()
        pendingNativeBinds.clear()
        failedNativeLoads.clear()
        nativeConfigs.clear()
        nativeStyles.clear()
        interKeys.forEach { InterstitialAdManager.release(it) }
        interKeys.clear()
        listeners.clear()
    }

    private fun nativeConfig(ids: List<String>, layoutRes: Int): NativeAdConfig =
        NativeAdConfig(ids, true, false, layoutRes).also {
            it.tierTimeoutMs = tierTimeoutMs
        }

    private fun ensureNativeBridge(key: String) {
        nativeBridges.computeIfAbsent(key) {
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    ObLog.d(ObLog.Section.LOAD, "$key native FILLED")
                    failedNativeLoads.remove(key)
                    deferredNativeFailures.remove(key)
                    notifyNativeLoadListener(key) { it.onLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    ObLog.w(ObLog.Section.LOAD, "$key native UNFILLED — no fill")
                    failedNativeLoads.add(key)
                    if (!helperAwaitsNative(key)) notifyNativeFailure(key)
                }

                // Click/open belong to the consumed ad's helper, not the preload listener.
            }.also { preload.registerAdCallback(key, it) }
        }
    }

    // A restored or resumed helper joins the load itself and reports its outcome. The
    // legacy preload bridge only resolves attempts which have no helper waiting on them.
    private fun helperAwaitsNative(key: String): Boolean =
        nativeBindings[key]?.helper?.nativeAdState?.value is AdNativeState.Loading

    private fun notifyNativeFailure(key: String) {
        if (key !in pendingNativeBinds) return
        val owner = nativeOwners[key]
        if (owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            deferredNativeFailures.add(key)
        } else {
            pendingNativeBinds.remove(key)
            notifyListener(key) { it.onFailedToLoad() }
        }
    }

    private fun notifyNativeLoadListener(key: String, block: (AdEventListener) -> Unit) {
        if (key !in pendingNativeBinds || helperAwaitsNative(key)) return
        val owner = nativeOwners[key]
        if (owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        notifyListener(key, block)
    }

    // Exhaustive so adding a store reason forces a mapping decision here
    private fun mapReason(reason: SdkAdSkipReason): AdSkipReason = when (reason) {
        SdkAdSkipReason.NOT_READY -> AdSkipReason.NOT_READY
        SdkAdSkipReason.CAPPED_BY_MODULE -> AdSkipReason.CAPPED_BY_ADS_MODULE
        SdkAdSkipReason.FAILED_TO_SHOW -> AdSkipReason.FAILED_TO_SHOW
        SdkAdSkipReason.SHOW_IN_BACKGROUND -> AdSkipReason.SHOW_IN_BACKGROUND
        SdkAdSkipReason.PURCHASED -> AdSkipReason.PREMIUM
        SdkAdSkipReason.CONSENT_NOT_GRANTED -> AdSkipReason.CONSENT_NOT_GRANTED
        SdkAdSkipReason.CONSENT_FORM_SHOWING -> AdSkipReason.SUPPRESSED_BY_FLOW
        SdkAdSkipReason.DISABLED_CONFIG -> AdSkipReason.NO_AD_UNIT
        // 1:1 rather than collapsed into NOT_READY: reporting an offline or gated request as
        // "nothing buffered" hid the two causes a funnel actually needs to tell apart.
        SdkAdSkipReason.OFFLINE -> AdSkipReason.OFFLINE
        SdkAdSkipReason.UA_GATE -> AdSkipReason.UA_GATE
    }

    private fun notifyListener(key: String, block: (AdEventListener) -> Unit) {
        listeners[key]?.let { runCatching { block(it) } }
    }
}
