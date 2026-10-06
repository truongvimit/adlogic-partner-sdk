package io.onboardkit.ads.erain

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.AdNativeState
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.adnative.NativeAdManager
import com.ads.module.helper.adnative.NativeClickAction
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.banner.BannerAdParam
import com.ads.module.helper.interstitial.InterLoadAndShowOptions
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.ads.awaitNativeRequestWindow
import io.onboardkit.ads.canStartNativeRequest
import io.onboardkit.ads.isPrivacyGoalsNative
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.onboardkit.remote.OnboardingSettings
import io.trackkit.PlacementRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.ads.module.helper.AdSkipReason as SdkAdSkipReason

/** The SDK's [OnboardingAdProvider]: the flow's placements translated onto the `:ads` helpers. */
class ERainAdProvider : OnboardingAdProvider() {

    private val interKeys = listOf(
        AdPlacement.SplashInterstitial.key,
        AdPlacement.AfterOnboardingInterstitial.key,
    )

    init {
        InterstitialAutoBuffer.reserve(*interKeys.toTypedArray())
    }

    /** The `ad_config` key each interstitial was last loaded under; its show reads the same overrides. */
    private val interConfigKeys = mutableMapOf<String, String?>()

    private val nativeKeys = mutableSetOf<String>()
    private val slots = mutableMapOf<String, NativeSlot>()

    private val impressedNatives = WeakHashMap<ApNativeAd, Boolean>()
    private class QueuedNative(val activity: Activity, val job: Job)
    private val queuedNatives = mutableMapOf<String, QueuedNative>()

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        val key = request.placement.key
        if (slots[key]?.helper?.isRestoringPresentation == true) return
        val queued = queuedNatives[key]
        if (queued?.activity === activity && queued.job.isActive) return
        queued?.job?.cancel()
        if (NativeAdManager.isReady(key) || NativeAdManager.isLoading(key) ||
            activity.canStartNativeRequest(request.allowWhileVisible)) {
            preloadNativeNow(activity, request)
            return
        }
        val owner = activity as? LifecycleOwner ?: run {
            slots[key]?.onLoadOutcome(filled = false)
            return
        }
        val job = owner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            if (activity.awaitNativeRequestWindow(request.allowWhileVisible)) {
                dispatchQueued(activity, request)
            }
        }
        val pending = QueuedNative(activity, job)
        queuedNatives[key] = pending
        job.invokeOnCompletion { if (queuedNatives[key] === pending) queuedNatives.remove(key) }
        job.start()
    }

    private fun dispatchQueued(activity: Activity, request: NativeAdRequest) {
        val sdk = OnboardingSdk
        val placement = request.placement
        val current = if (sdk.configuredPlacementKey(placement) == null) request
        else sdk.configOrNull()?.ads?.nativeUnitFor(placement)?.let { request.copy(unit = it) }
        val refused = sdk.isReady() &&
            (current == null || sdk.guard().skipReason(activity, placement, current.unit) != null)
        if (refused) slots[placement.key]?.onLoadOutcome(filled = false)
        else preloadNativeNow(activity, current ?: request)
    }

    private fun preloadNativeNow(context: Context, request: NativeAdRequest) {
        val key = request.placement.key
        if (slots[key]?.helper?.isRestoringPresentation == true) return
        val ids = request.unit.loadOrder
        if (ids.isEmpty()) {
            ObLog.w(ObLog.Section.LOAD, "$key skip — no usable ad unit id")
            return
        }
        nativeKeys += key
        ids.forEach { PlacementRegistry.register(it, key) }
        val config = nativeConfig(request)
        NativeAdManager.preload(context, key, config, reportTelemetry = false) { reason ->
            if (reason == null) ObLog.d(ObLog.Section.LOAD, "$key native FILLED")
            else ObLog.w(ObLog.Section.LOAD, "$key native UNFILLED — ${reason.key}")
            slots[key]?.onLoadOutcome(filled = reason == null)
        }
    }

    override fun nativeStatus(placement: AdPlacement): NativeStatus {
        val key = placement.key
        return when {
            NativeAdManager.isReady(key) -> NativeStatus.READY
            NativeAdManager.isLoading(key) || queuedNatives[key]?.job?.isActive == true ->
                NativeStatus.LOADING
            NativeAdManager.isFailed(key) && slots[key]?.helper?.isRestoringPresentation != true ->
                NativeStatus.FAILED
            else -> NativeStatus.IDLE
        }
    }

    override fun bindNative(
        activity: ComponentActivity,
        request: NativeAdRequest,
        container: FrameLayout,
        listener: AdEventListener,
    ): Boolean {
        val key = request.placement.key
        val owner = container.findViewTreeLifecycleOwner() ?: activity
        val current = slots[key]
        val slot = current?.takeIf { it.serves(activity, owner, container, request) } ?: run {
            current?.release()
            NativeSlot(activity, owner, container, request).also { slots[key] = it }
        }
        slot.refresh()
        val bound = slot.bind(listener)
        // A preload may still be waiting for its old host's focus. Move that existing queue
        // to the destination; an already-dispatched load keeps its original request/callback.
        if (!bound && queuedNatives[key]?.let { it.activity !== activity && it.job.isActive } == true) {
            preloadNative(activity, request)
        }
        return bound
    }

    override fun releaseNative(placement: AdPlacement) {
        val key = placement.key
        queuedNatives.remove(key)?.job?.cancel()
        slots.remove(key)?.release()
    }

    override fun pendingClickAction(placement: AdPlacement): NativeClickAction? =
        slots[placement.key]?.helper?.pendingClickAction

    private inner class NativeSlot(
        private val activity: Activity,
        private val owner: LifecycleOwner,
        private val container: FrameLayout,
        request: NativeAdRequest,
    ) : AdCallback() {
        private val placement = request.placement
        private val key = placement.key
        private val loadOrder = request.unit.loadOrder
        private val layoutRes = request.layoutRes
        private val config = nativeConfig(request)
        val helper = NativeAdHelper(activity, owner, config, key)
            .setNativeStyleProvider { currentNativeStyle() }
            .setNativeContentView(container)
            .also {
                it.reportTelemetry = false
                it.registerAdListener(this)
            }
        private var listener: AdEventListener? = null
        private var waiting = false
        private var binding = false
        private val forgetOnDestroy = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && slots[key] === this) slots.remove(key)
        }.also { owner.lifecycle.addObserver(it) }

        fun serves(
            activity: Activity,
            owner: LifecycleOwner,
            container: FrameLayout,
            request: NativeAdRequest,
        ): Boolean = this.activity === activity && this.owner === owner &&
            this.container === container && loadOrder == request.unit.loadOrder &&
            layoutRes == request.layoutRes

        fun refresh() {
            config.behavior = OnboardingSettings.behavior(placement)
        }

        private fun currentNativeStyle() = AdRemoteConfig.getInstance().let { ads ->
            OnboardingSdk.configuredPlacementKey(placement)?.let { ads.ads[it] }
                ?: config.adUnitIds.firstNotNullOfOrNull(ads::unitForAdId)
        }?.toNativeStyle()

        fun bind(listener: AdEventListener): Boolean {
            this.listener?.onDetached()
            this.listener = listener
            waiting = true
            binding = true
            val taken = try {
                helper.bindAvailable()
            } finally {
                binding = false
            }
            if (taken) waiting = false
            return taken
        }

        fun onLoadOutcome(filled: Boolean) {
            if (!waiting || helper.nativeAdState.value is AdNativeState.Loading) return
            if (!filled) return failUnlessShowing()
            refresh()
            val refused = !helper.bindAvailable() && helper.nativeAdState.value.let {
                it is AdNativeState.Fail || it is AdNativeState.Cancel
            }
            if (refused && waiting) failUnlessShowing()
        }

        fun release() {
            listener?.onDetached()
            owner.lifecycle.removeObserver(forgetOnDestroy)
            helper.destroy()
        }

        override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
            waiting = false
            if (!binding) report { onLoaded() }
            if (impressedNatives[nativeAd] == true) report { onImpression() }
        }

        override fun onAdImpression() {
            helper.nativeAd?.let { impressedNatives[it] = true }
            report { onImpression() }
        }

        override fun onAdFailedToLoad(error: LoadAdError?) = failUnlessShowing()

        override fun onAdFailedToShow(adError: AdError?) {
            if (!binding) failUnlessShowing()
        }

        override fun onAdClicked() = report { onClicked() }

        override fun onAdOpened() = report { onAdOpened() }

        private fun failUnlessShowing() {
            waiting = false
            if (helper.nativeAd?.isUsable != true) report { onFailedToLoad() }
        }

        // A screen's exception must not escape into the helper's lifecycle or vendor dispatch.
        private fun report(event: AdEventListener.() -> Unit) {
            val screen = listener ?: return
            runCatching { screen.event() }
                .onFailure { ObLog.w(ObLog.Section.LOAD, "$key screen callback threw: $it") }
        }
    }

    override fun loadInterstitial(
        activity: Activity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        adConfigKey: String?,
        listener: AdEventListener?,
    ) {
        val key = placement.key
        interConfigKeys[key] = adConfigKey
        InterstitialAdManager.load(
            activity,
            key,
            unit.loadOrder,
            InterLoadOptions(
                passesUaGate = AdGate.placementPassesUaGate(
                    adConfigKey ?: OnboardingSdk.configuredPlacementKey(placement) ?: key),
                reportTelemetry = false,
            ).apply { behavior = OnboardingSettings.behavior(placement, adConfigKey) },
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    ObLog.d(ObLog.Section.LOAD, "$key inter FILLED")
                    listener?.onLoaded()
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    ObLog.w(ObLog.Section.LOAD, "$key inter UNFILLED")
                    listener?.onFailedToLoad()
                }

                override fun onAdClicked() {
                    listener?.onClicked()
                }
            },
        )
    }

    override fun isInterstitialReady(placement: AdPlacement): Boolean =
        InterstitialAdManager.isReady(placement.key)

    override fun readyInterstitialUnitId(placement: AdPlacement): String? =
        InterstitialAdManager.readyAdUnitId(placement.key)

    override fun releaseInterstitial(placement: AdPlacement) {
        InterstitialAdManager.release(placement.key)
    }

    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    ) {
        val key = placement.key
        InterstitialAdManager.show(
            activity,
            key,
            interstitialCallback(key, callback),
            reportTelemetry = false,
            nextAction = InterNextAction.UnderAd,
            behavior = OnboardingSettings.behavior(placement, interConfigKeys[key]),
        )
    }

    override fun loadAndShowInterstitial(
        activity: AppCompatActivity,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        callback: ObInterstitialCallback,
        timeoutMs: Long,
    ) {
        InterstitialAdManager.loadAndShow(
            activity,
            placement.key,
            unit.loadOrder,
            interstitialCallback(placement.key, callback),
            InterLoadAndShowOptions(
                timeoutMs = timeoutMs,
                passesUaGate = AdGate.placementPassesUaGate(
                    OnboardingSdk.configuredPlacementKey(placement) ?: placement.key),
                reportTelemetry = false,
                nextAction = InterNextAction.UnderAd,
            ).apply { behavior = OnboardingSettings.behavior(placement) },
        )
    }

    // Shows pin UnderAd: only there does onComplete without a skip mean the ad is on screen.
    private fun interstitialCallback(
        key: String,
        callback: ObInterstitialCallback,
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
        }

    override fun loadBanner(
        activity: AppCompatActivity,
        unit: BannerAdUnit,
        listener: AdEventListener,
    ) {
        val callback = object : AdCallback() {
            override fun onAdLoaded() { listener.onLoaded() }
            override fun onAdFailedToLoad(error: LoadAdError?) { listener.onFailedToLoad() }
            override fun onAdClicked() { listener.onClicked() }
        }
        val declaredKey = OnboardingSdk.configuredPlacementKey(AdPlacement.SplashBanner)
            ?.takeIf { AdRemoteConfig.getInstance().declares(it) }
        val config = declaredKey?.let { BannerAdConfig.forPlacement(it) }
            ?: BannerAdConfig(unit.id, true, false)
        config.behavior = OnboardingSettings.behavior(AdPlacement.SplashBanner)
        BannerAdHelper(activity, activity, config).apply {
            registerAdListener(callback)
            requestAds(BannerAdParam.Request)
        }
    }

    override fun releaseAll() {
        queuedNatives.values.toList().forEach { it.job.cancel() }
        queuedNatives.clear()
        slots.values.toList().forEach { it.release() }
        slots.clear()
        // After the slots: releasing a key answers its pending loads, which must not reach a slot.
        nativeKeys.forEach(NativeAdManager::release)
        nativeKeys.clear()
        impressedNatives.clear()
        interKeys.forEach(InterstitialAdManager::release)
    }

    private fun nativeConfig(request: NativeAdRequest): NativeAdConfig {
        val placement = request.placement
        val layoutRes = request.layoutRes
        val liveSdkFrame: (() -> Int)? = if (NativeTemplates.isSdkLayout(layoutRes)) {
            {
                if (OnboardingSdk.configOrNull() != null)
                    NativeTemplates.layoutForPlacement(placement) else layoutRes
            }
        } else {
            null
        }
        val pagerPage = (placement is AdPlacement.StepNative && !placement.isPrivacyGoalsNative) ||
            placement is AdPlacement.StepFullScreen
        return NativeAdConfig.forUnits(
            request.unit.loadOrder,
            layoutRes,
            adConfigKey = OnboardingSdk.configuredPlacementKey(placement),
            joinOnly = true,
            singleFill = pagerPage,
            liveLayoutId = liveSdkFrame,
            liveClickAction = { OnboardingSettings.nativeClickAction(placement) },
        ).also { it.behavior = OnboardingSettings.behavior(placement) }
    }

    private fun mapReason(reason: SdkAdSkipReason): AdSkipReason = when (reason) {
        SdkAdSkipReason.REQUESTS_HELD -> AdSkipReason.REQUESTS_HELD
        SdkAdSkipReason.NOT_READY -> AdSkipReason.NOT_READY
        SdkAdSkipReason.CAPPED_BY_MODULE -> AdSkipReason.CAPPED_BY_ADS_MODULE
        SdkAdSkipReason.FAILED_TO_SHOW -> AdSkipReason.FAILED_TO_SHOW
        SdkAdSkipReason.SHOW_IN_BACKGROUND -> AdSkipReason.SHOW_IN_BACKGROUND
        SdkAdSkipReason.PURCHASED -> AdSkipReason.PREMIUM
        SdkAdSkipReason.CONSENT_NOT_GRANTED -> AdSkipReason.CONSENT_NOT_GRANTED
        SdkAdSkipReason.CONSENT_FORM_SHOWING -> AdSkipReason.SUPPRESSED_BY_FLOW
        SdkAdSkipReason.DISABLED_CONFIG -> AdSkipReason.NO_AD_UNIT
        SdkAdSkipReason.OFFLINE -> AdSkipReason.OFFLINE
        SdkAdSkipReason.UA_GATE -> AdSkipReason.UA_GATE
    }
}
