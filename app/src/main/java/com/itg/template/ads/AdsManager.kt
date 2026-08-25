package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import timber.log.Timber

/**
 * The app's placement catalog: maps [AdRemoteConfig] slots onto the SDK's gate, stores,
 * and view helpers. Mechanism (cache, expiry, dedup, show contract, view lifecycle) lives
 * in `com.ads.module.helper`; only placement policy stays here.
 */
@SuppressLint("StaticFieldLeak")
object AdsManager {

    /**
     * The one native integration for every placement: waterfall + UA gate from
     * [AdRemoteConfig], remote-config styling applied by the SDK to both the loaded ad and
     * its auto-derived loading skeleton, telemetry keyed by the placement. A screen hands
     * its container over and calls `requestAds` once:
     *
     * ```
     * AdsManager.nativeHelper(this, this, "native_welcome", AdRemoteConfig.native_welcome, layout)
     *     .setNativeContentView(binding.frAds)
     *     .requestAds(NativeAdParam.Request)
     * ```
     *
     * [bypassUaGate] is for dashboard/test slots that must load on any install. Pass a
     * null [placement] for such slots: it skips placement registration, so preview loads
     * never re-map the real ad units' revenue attribution. Chain `setShimmerLayoutView`/
     * `setShimmerLayout` for a hand-made skeleton; call `setNativeStyle` again before a
     * reload to restyle the next fill.
     */
    fun nativeHelper(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        placement: String?,
        config: AdUnitConfig,
        @LayoutRes layoutRes: Int,
        bypassUaGate: Boolean = false,
    ): NativeAdHelper {
        val nativeConfig =
            NativeAdConfig(config.waterfallIds, config.isUsable, false, layoutRes).also {
                it.forceUaCheck = !bypassUaGate && config.enableUaCheck
            }
        return NativeAdHelper(activity, lifecycleOwner, nativeConfig)
            .setNativeStyle(config.toNativeStyle())
            .also { it.placement = placement }
    }

    fun loadInterOnboarding(context: Context, ignoreLimit: Boolean = false) {
        val config = AdRemoteConfig.inter_onboarding
        InterstitialAdManager.load(
            context,
            "inter_onboarding",
            config.waterfallIds,
            InterLoadOptions(
                enabled = config.isUsable,
                passesUaGate = AdGate.passesUaGate(config.enableUaCheck, bypass = ignoreLimit),
            ),
        )
    }

    /**
     * [nextAction] decides *when* [onAction] runs. [InterNextAction.UnderAd] — the app-wide
     * default, set by `ERainTuning.install()` in `GlobalApp` — starts the next screen while the ad
     * is still up, so it is already painted when the ad closes. Pass
     * [InterNextAction.AfterDismiss] when the callback must not run behind the ad: a destination
     * that opens the camera, starts audio or plays video — or a screen that only finishes itself.
     */
    fun showInterOnboarding(
        context: Context,
        ignoreLimit: Boolean = false,
        nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
        onAction: () -> Unit,
    ) {
        InterstitialAdManager.show(
            context,
            "inter_onboarding",
            onCompleteOnce(onAction),
            nextAction = nextAction,
        )
    }

    fun loadInterWelcome(context: Context, ignoreLimit: Boolean = false) {
        val config = AdRemoteConfig.inter_welcome
        InterstitialAdManager.load(
            context,
            "inter_welcome",
            config.waterfallIds,
            InterLoadOptions(
                enabled = config.isUsable,
                passesUaGate = AdGate.passesUaGate(config.enableUaCheck, bypass = ignoreLimit),
            ),
        )
    }

    /** See [showInterOnboarding] for what [nextAction] changes. */
    fun showInterWelcome(
        context: Context,
        ignoreLimit: Boolean = false,
        nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
        onAction: () -> Unit,
    ) {
        InterstitialAdManager.show(
            context,
            "inter_welcome",
            onCompleteOnce(onAction),
            nextAction = nextAction,
        )
    }

    /**
     * The store fires onComplete exactly once, whatever the module reports — the only thing the
     * timing changes is when. Navigation goes here and nowhere else: `onClosed` runs on one side
     * of it or the other depending on [InterNextAction], so a screen wired to both would move
     * twice under one timing and not at all under the other.
     */
    private fun onCompleteOnce(onAction: () -> Unit) = object : InterShowCallback() {
        override fun onSkipped(reason: AdSkipReason) {
            if (reason == AdSkipReason.FAILED_TO_SHOW) {
                Timber.tag("AdsManager").w("Interstitial show failed")
            }
        }

        override fun onComplete() = onAction()
    }

    fun loadAndShowReward(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit
    ) {
        val config = AdRemoteConfig.reward_example
        RewardAdManager.loadAndShow(
            activity,
            "reward_example",
            config.waterfallIds,
            enabled = config.isEnable,
            onSuccess = { onSuccess() },
            onFailed = { onFailed() },
        )
    }

    fun clearAll() {
        // Per-key: the store is process-wide and OnboardKit owns placements of its own
        InterstitialAdManager.release("inter_onboarding")
        InterstitialAdManager.release("inter_welcome")
    }
}
