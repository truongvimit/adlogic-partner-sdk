package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import com.ads.module.tracking.AdTracking
import timber.log.Timber

/**
 * The app's placement catalog: maps [AdRemoteConfig] slots onto the SDK's gate, stores,
 * and view helpers. Mechanism (cache, expiry, dedup, show contract, view lifecycle) lives
 * in `com.ads.module.helper`; only placement policy stays here.
 */
@SuppressLint("StaticFieldLeak")
object AdsManager {

    /**
     * Binds every configured ad unit to its placement, once, before the ads SDK starts.
     *
     * AdMob's paid-event callback only knows the ad unit, so :ads reads the placement back from
     * [io.trackkit.PlacementRegistry] — same shape as ironSource's register-once
     * addImpressionDataListener, where per-impression context the SDK cannot know is supplied
     * out-of-band instead of by wrapping call sites.
     *
     * The config key *is* the placement name, so the mapping cannot drift from AdRemoteConfig.
     * OnboardKit re-registers its own units under its placement keys when it loads them.
     */
    fun registerAdPlacements() {
        val config = runCatching { AdRemoteConfig.getInstance() }.getOrNull() ?: return
        config.ads.forEach { (placement, unit) ->
            unit.waterfallIds.forEach { adUnitId ->
                AdTracking.registerPlacement(
                    adUnitId,
                    placement
                )
            }
        }
    }

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

    fun showInterOnboarding(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        InterstitialAdManager.show(context, "inter_onboarding", onCompleteOnce(onAction))
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

    fun showInterWelcome(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        InterstitialAdManager.show(context, "inter_welcome", onCompleteOnce(onAction))
    }

    /** The store fires onComplete exactly once, whatever the module reports. */
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
