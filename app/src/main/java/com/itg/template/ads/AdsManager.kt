package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import com.ads.module.tracking.AdTracking
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.LoadAdError
import io.trackkit.AdFormat
import java.util.Collections
import java.util.WeakHashMap
import timber.log.Timber

/**
 * The app's placement catalog: maps [AdRemoteConfig] slots onto the SDK's gate, stores,
 * and view helpers. Mechanism (cache, expiry, dedup, show contract, view lifecycle) lives
 * in `com.ads.module.helper`; only placement policy stays here.
 */
@SuppressLint("StaticFieldLeak")
object AdsManager {

    // Auto-resolve config for each loaded native ad. Weak keys: a native ad holds an inflated
    // view tree, so strong references here would pin view hierarchies for the process lifetime
    private val adConfigMap: MutableMap<ApNativeAd, AdUnitConfig> =
        Collections.synchronizedMap(WeakHashMap())

    fun getAdConfig(ad: ApNativeAd): AdUnitConfig? = adConfigMap[ad]

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
     * The standard native integration for one placement: waterfall + UA gate from
     * [AdRemoteConfig], the app's CTA/component styling as the binder, telemetry keyed by
     * the placement. A screen hands its views over and calls `requestAds` once:
     *
     * ```
     * AdsManager.nativeHelper(this, this, "native_welcome", AdRemoteConfig.native_welcome, layout)
     *     .setNativeContentView(binding.frAds)
     *     .setShimmerLayoutView(binding.shimmer)
     *     .requestAds(NativeAdParam.Request)
     * ```
     */
    fun nativeHelper(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        placement: String,
        config: AdUnitConfig,
        @LayoutRes layoutRes: Int,
    ): NativeAdHelper {
        val nativeConfig =
            NativeAdConfig(config.waterfallIds, config.isUsable, false, layoutRes).also {
                it.forceUaCheck = config.enableUaCheck
            }
        return NativeAdHelper(activity, lifecycleOwner, nativeConfig)
            .setNativeAdBinder { act, ad, container, shimmer ->
                populateNativeAdView(
                    act, ad, config, container, shimmer ?: ShimmerFrameLayout(act),
                )
            }
            .also { it.placement = placement }
    }

    // ── Dashboard / Test helpers (ignore shouldDisplay) ──

    /** Dedicated LiveData for customization preview – won't collide with real flows */
    val nativeDashboardPreviewLive = MutableLiveData<ApNativeAd?>()

    /**
     * Load a native ad for dashboard preview purposes.
     * Bypasses all shouldDisplay checks so it always loads.
     */
    fun loadNativeForDashboard(activity: Activity, configKey: String, layoutRes: Int) {
        val config = try {
            AdRemoteConfig.getInstance().ads[configKey]
                ?: AdUnitConfig(id = "", isEnable = false)
        } catch (_: Exception) {
            AdUnitConfig(id = "", isEnable = false)
        }
        val skipReason = AdGate.skipReason(activity, config.isUsable)
        if (skipReason != null) {
            AdTracking.skipped("preview_$configKey", AdFormat.NATIVE, skipReason.key)
            nativeDashboardPreviewLive.postValue(null)
            return
        }
        AdTracking.request("preview_$configKey", AdFormat.NATIVE, config.waterfallIds.first())
        AdWaterfall.loadNative(
            activity, config.waterfallIds, layoutRes,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    adConfigMap[nativeAd] = config
                    nativeDashboardPreviewLive.postValue(nativeAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    nativeDashboardPreviewLive.postValue(null)
                }
            },
        )
    }

    /** Load native language ad for dashboard – ignores shouldDisplay */
    fun loadNativeLanguageForDashboard(activity: Activity, layoutRes: Int) {
        loadNativeForDashboard(activity, "native_language_1", layoutRes)
    }

    /** Load native onboarding full for dashboard – ignores shouldDisplay */
    fun loadNativeFullForDashboard(activity: Activity, layoutRes: Int) {
        loadNativeForDashboard(activity, "native_onboarding_fullscreen_1_3", layoutRes)
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
        nativeDashboardPreviewLive.postValue(null)
        // Per-key: the store is process-wide and OnboardKit owns placements of its own
        InterstitialAdManager.release("inter_onboarding")
        InterstitialAdManager.release("inter_welcome")
    }
}
