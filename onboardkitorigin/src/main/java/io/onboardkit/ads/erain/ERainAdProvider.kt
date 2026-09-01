package io.onboardkit.ads.erain

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdmobHelper
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason as SdkAdSkipReason
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeAdStyler
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
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.trackkit.PlacementRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
        // The flow reuses its splash interstitial at the language and pager exits, and decides
        // which screens to show from a bare "is one buffered" probe. A background top-up would
        // both add an impression and delete the screens the flow would otherwise have shown.
        InterstitialAutoBuffer.reserve(
            AdPlacement.SplashInterstitial.key,
            AdPlacement.QuestionInterstitial.key,
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

    // GMA requires destroy() on every consumed NativeAd; the buffer only owns unpolled ones
    private val boundNatives = ConcurrentHashMap<String, ApNativeAd>()

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
        ensureNativeBridge(key)
        val covered =
            preload.preloadWithKeyIfEmpty(key, activity, nativeConfig(ids, request.layoutRes))
        // A purchased/offline no-op must still answer, or a waiting screen shimmers forever
        if (!covered && !isNativeReady(request.placement)) {
            notifyListener(key) { it.onFailedToLoad() }
        }
    }

    override fun isNativeReady(placement: AdPlacement): Boolean =
        preload.getAdNative(placement.key) != null

    override fun isNativeLoading(placement: AdPlacement): Boolean =
        preload.isPreloadInProgress(placement.key)

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        listener?.let { listeners[placement.key] = it }
        val frame = container as? FrameLayout ?: return false
        val ad = preload.pollAdNative(placement.key) ?: return false
        NativeAdStyler.populate(
            activity,
            ad,
            nativeStyles[placement.key],
            frame,
            shimmer as? ShimmerFrameLayout,
        )
        boundNatives.put(placement.key, ad)?.let { previous ->
            if (previous !== ad) destroyNative(previous)
        }
        // GMA counts the impression once the view tree is bound and visible
        notifyListener(placement.key) { it.onImpression() }
        return true
    }

    override fun releaseNative(placement: AdPlacement) {
        val key = placement.key
        nativeBridges.remove(key)
        nativeStyles.remove(key)
        preload.release(key)
        boundNatives.remove(key)?.let(::destroyNative)
        listeners.remove(key)
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
            },
            reportTelemetry = false,
            nextAction = InterNextAction.UnderAd,
        )
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
        // Per-key release: the stores are process-wide and the host app owns keys of its own
        nativeBridges.keys.forEach { preload.release(it) }
        nativeBridges.clear()
        boundNatives.values.forEach(::destroyNative)
        boundNatives.clear()
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
                    notifyListener(key) { it.onLoaded() }
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    ObLog.w(ObLog.Section.LOAD, "$key native UNFILLED — no fill")
                    notifyListener(key) { it.onFailedToLoad() }
                }

                override fun onAdClicked() {
                    notifyListener(key) { it.onClicked() }
                }

                override fun onAdOpened() {
                    notifyListener(key) { it.onAdOpened() }
                }

            }.also { preload.registerAdCallback(key, it) }
        }
    }

    // Exhaustive so adding a store reason forces a mapping decision here
    private fun mapReason(reason: SdkAdSkipReason): AdSkipReason = when (reason) {
        SdkAdSkipReason.NOT_READY -> AdSkipReason.NOT_READY
        SdkAdSkipReason.CAPPED_BY_MODULE -> AdSkipReason.CAPPED_BY_ADS_MODULE
        SdkAdSkipReason.FAILED_TO_SHOW -> AdSkipReason.FAILED_TO_SHOW
        SdkAdSkipReason.PURCHASED -> AdSkipReason.PREMIUM
        SdkAdSkipReason.DISABLED_CONFIG -> AdSkipReason.NO_AD_UNIT
        // 1:1 rather than collapsed into NOT_READY: reporting an offline or gated request as
        // "nothing buffered" hid the two causes a funnel actually needs to tell apart.
        SdkAdSkipReason.OFFLINE -> AdSkipReason.OFFLINE
        SdkAdSkipReason.UA_GATE -> AdSkipReason.UA_GATE
    }

    private fun destroyNative(ad: ApNativeAd) {
        runCatching { ad.admobNativeAd?.destroy() }
    }

    private fun notifyListener(key: String, block: (AdEventListener) -> Unit) {
        listeners[key]?.let { runCatching { block(it) } }
    }
}
