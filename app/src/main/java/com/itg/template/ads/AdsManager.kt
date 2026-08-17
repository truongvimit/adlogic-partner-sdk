package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import com.ads.module.tracking.AdTracking
import com.ads.module.util.AppConstant
import com.google.android.gms.ads.LoadAdError
import com.itg.template.ui.bases.ext.goneView
import io.trackkit.AdFormat
import java.util.Collections
import java.util.WeakHashMap
import timber.log.Timber

/**
 * The app's placement catalog and delivery layer: maps [AdRemoteConfig] slots onto the
 * SDK's gate/store/helpers and publishes results to the screens' LiveData.
 *
 * Mechanism (cache, expiry, dedup, show contract) lives in `com.ads.module.helper`;
 * only placement policy stays here.
 */
@SuppressLint("StaticFieldLeak")
object AdsManager {

    val nativeSurveyAdLive = MutableLiveData<ApNativeAd?>()
    val nativeConfirmUninstallAdLive = MutableLiveData<ApNativeAd?>()
    val nativeWelcomeAdLive = MutableLiveData<ApNativeAd?>()
    val nativePermissionAdLive = MutableLiveData<ApNativeAd?>()
    val nativeHomeAdLive = MutableLiveData<ApNativeAd?>()
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

    private fun loadNativeInternal(
        activity: Activity,
        placement: String,
        config: AdUnitConfig,
        layoutRes: Int,
        liveData: MutableLiveData<ApNativeAd?>,
        passesUaGate: Boolean = true,
    ) {
        // A load that never happened, and why — same chain and reason keys as always
        val skipReason = AdGate.skipReason(activity, config.isUsable, passesUaGate)
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.NATIVE, skipReason.key)
            liveData.postValue(null)
            return
        }
        // waterfallIds, not id: the list is the waterfall, highest floor first
        AdTracking.request(placement, AdFormat.NATIVE, config.waterfallIds.first())
        AdWaterfall.loadNative(
            activity, config.waterfallIds, layoutRes,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    adConfigMap[nativeAd] = config
                    liveData.postValue(nativeAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    liveData.postValue(null)
                }
            },
        )
    }

    fun loadNativePermission(activity: Activity, layoutRes: Int) {
        val config = AdRemoteConfig.native_permission
        loadNativeInternal(
            activity,
            "native_permission",
            config,
            layoutRes,
            nativePermissionAdLive,
            AdGate.passesUaGate(config.enableUaCheck)
        )
    }

    fun loadNativeHome(activity: Activity, layoutRes: Int) {
        val config = AdRemoteConfig.native_home
        loadNativeInternal(
            activity,
            "native_home",
            config,
            layoutRes,
            nativeHomeAdLive,
            AdGate.passesUaGate(config.enableUaCheck)
        )
    }

    fun loadNativeSurvey(activity: Activity, layoutRes: Int) {
        val config = AdRemoteConfig.native_survey
        loadNativeInternal(
            activity, "native_survey", config, layoutRes, nativeSurveyAdLive,
            AdGate.passesUaGate(config.enableUaCheck)
        )
    }

    fun loadNativeConfirmUninstall(activity: Activity, layoutRes: Int) {
        val config = AdRemoteConfig.native_confirm_uninstall
        loadNativeInternal(
            activity,
            "native_confirm_uninstall",
            config,
            layoutRes,
            nativeConfirmUninstallAdLive,
            AdGate.passesUaGate(config.enableUaCheck)
        )
    }

    fun loadNativeWelcome(activity: Activity, layoutRes: Int) {
        val config = AdRemoteConfig.native_welcome
        loadNativeInternal(
            activity,
            "native_welcome",
            config,
            layoutRes,
            nativeWelcomeAdLive,
            AdGate.passesUaGate(config.enableUaCheck)
        )
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
        // Force the UA gate open to bypass SDK limits
        loadNativeInternal(
            activity,
            "preview_$configKey",
            config,
            layoutRes,
            nativeDashboardPreviewLive,
            passesUaGate = true
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

    fun loadBanner(
        activity: AppCompatActivity,
        adUnitConfig: AdUnitConfig,
        frAds: FrameLayout,
        isCollapse: Boolean,
        placement: String = "banner_home",
    ) {
        if (adUnitConfig.isEnable) {
            BannerAdHelper.resetPlaceholder(activity, frAds)
            // Mirror the SDK's own purchased gate so a silent internal no-op is not
            // recorded as a pending request
            if (AdGate.isPurchased(activity)) {
                AdTracking.skipped(placement, AdFormat.BANNER, AdSkipReason.PURCHASED.key)
            } else {
                AdTracking.request(placement, AdFormat.BANNER, adUnitConfig.id)
            }
            val callback = object : AdCallback() {
                override fun onAdFailedToLoad(i: LoadAdError?) {
                    super.onAdFailedToLoad(i)
                    frAds.goneView()
                    Timber.tag("AdsManager_Banner")
                        .d("Load banner on ${activity.javaClass.simpleName} failed by : ${i?.message}")
                }
            }
            if (isCollapse) ERainAd.getInstance().loadCollapsibleBanner(
                activity,
                adUnitConfig.id,
                AppConstant.CollapsibleGravity.BOTTOM,
                callback,
            )
            else ERainAd.getInstance().loadBanner(activity, adUnitConfig.id, callback)
        } else {
            AdTracking.skipped(placement, AdFormat.BANNER, AdSkipReason.DISABLED_CONFIG.key)
            frAds.removeAllViews()
            frAds.goneView()
        }
    }

    fun clearAll() {
        nativeSurveyAdLive.postValue(null)
        nativeConfirmUninstallAdLive.postValue(null)
        nativeWelcomeAdLive.postValue(null)
        // Per-key: the store is process-wide and OnboardKit owns placements of its own
        InterstitialAdManager.release("inter_onboarding")
        InterstitialAdManager.release("inter_welcome")
    }
}
