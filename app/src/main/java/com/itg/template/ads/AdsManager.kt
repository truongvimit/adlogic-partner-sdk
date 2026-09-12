package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
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
     * The dashboard's native previews, which deliberately do not follow a placement's own
     * configuration: [bypassUaGate] loads on any install and a null [placement] skips placement
     * registration, so a preview never re-maps a real ad unit's revenue attribution.
     *
     * An ordinary slot uses `NativeAdHelper.forPlacement(activity, owner, placement, container,
     * layoutRes)` instead — see [com.itg.template.ui.component.welcome.WelcomeActivity].
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

    /**
     * Load and show read the same placement configuration, so a fill is only ever bought for an
     * ad that may actually be presented. There is deliberately no bypass: [InterstitialAdManager]
     * applies `enable_ua_check` at show time whatever a caller passed at load time, so bypassing
     * here would only spend a request on an ad the store would then refuse.
     */
    fun loadInterOnboarding(context: Context) =
        InterstitialAdManager.load(context, AppAdPlacement.INTER_ONBOARDING)

    /**
     * [nextAction] decides *when* [onAction] runs. [InterNextAction.UnderAd] — the app-wide
     * default, set by `ERainTuning.install()` in `GlobalApp` — starts the next screen while the ad
     * is still up, so it is already painted when the ad closes. Pass
     * [InterNextAction.AfterDismiss] when the callback must not run behind the ad: a destination
     * that opens the camera, starts audio or plays video — or a screen that only finishes itself.
     */
    fun showInterOnboarding(
        context: Context,
        nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
        onAction: () -> Unit,
    ) {
        InterstitialAdManager.show(
            context,
            AppAdPlacement.INTER_ONBOARDING,
            onCompleteOnce(onAction),
            nextAction = nextAction,
        )
    }

    /** See [loadInterOnboarding] for why there is no bypass. */
    fun loadInterWelcome(context: Context) =
        InterstitialAdManager.load(context, AppAdPlacement.INTER_WELCOME)

    /** See [showInterOnboarding] for what [nextAction] changes. */
    fun showInterWelcome(
        context: Context,
        nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
        onAction: () -> Unit,
    ) {
        InterstitialAdManager.show(
            context,
            AppAdPlacement.INTER_WELCOME,
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
        RewardAdManager.loadAndShow(
            activity,
            AppAdPlacement.REWARD_EXAMPLE,
            { onSuccess() },
            { onFailed() },
        )
    }

    fun clearAll() {
        // Per-key: the store is process-wide and OnboardKit owns placements of its own
        InterstitialAdManager.release(AppAdPlacement.INTER_ONBOARDING)
        InterstitialAdManager.release(AppAdPlacement.INTER_WELCOME)
    }
}
