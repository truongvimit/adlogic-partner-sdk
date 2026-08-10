package com.itg.template.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.billing.AppPurchase
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdType
import com.ads.module.funtion.RewardCallback
import com.ads.module.util.AppConstant
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.itg.template.tracking.AdsTracking
import com.itg.template.ui.bases.ext.goneView
import java.util.Collections
import java.util.WeakHashMap
import timber.log.Timber

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

    private var interSplashAd: ApInterstitialAd? = null
    private var interOnboarding: ApInterstitialAd? = null
    private var interWelcomeAd: ApInterstitialAd? = null

    private var rewardExample: RewardedAd? = null

    private fun loadNativeInternal(
        activity: Activity,
        placement: String,
        config: AdUnitConfig,
        layoutRes: Int,
        liveData: MutableLiveData<ApNativeAd?>,
        shouldDisplay: Boolean = true,
    ) {
        val skipReason = when {
            !config.isEnable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(activity) -> "purchased"
            !activity.isNetworkAvailable() -> "offline"
            !shouldDisplay -> "ua_gate"
            else -> null
        }
        if (skipReason != null) {
            AdsTracking.nativeLoadSkipped(placement, skipReason)
            liveData.postValue(null)
            return
        }
        AdsTracking.nativeLoadRequested(placement, config.id)
        ERainAd.getInstance().loadNativeAdResultCallback(
            activity, config.id, layoutRes,
            AdsTracking.trackedNativeLoadCallback(placement, object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    super.onNativeAdLoaded(nativeAd)
                    adConfigMap[nativeAd] = config
                    liveData.postValue(nativeAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    super.onAdFailedToLoad(adError)
                    liveData.postValue(null)
                }
            }),
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
            ERainAd.getInstance().getShouldDisplayNativePermission(config.enableUaCheck)
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
            ERainAd.getInstance().getShouldDisplayNativeHome(config.enableUaCheck)
        )
    }

    fun loadNativeSurvey(activity: Activity, layoutRes: Int) {
        loadNativeInternal(
            activity, "native_survey", AdRemoteConfig.native_survey, layoutRes, nativeSurveyAdLive,
            ERainAd.getInstance()
                .getShouldDisplayWidgetUninstall(AdRemoteConfig.native_survey.enableUaCheck)
        )
    }

    fun loadNativeConfirmUninstall(activity: Activity, layoutRes: Int) {
        loadNativeInternal(
            activity,
            "native_confirm_uninstall",
            AdRemoteConfig.native_confirm_uninstall,
            layoutRes,
            nativeConfirmUninstallAdLive,
            ERainAd.getInstance()
                .getShouldDisplayWidgetUninstall(AdRemoteConfig.native_confirm_uninstall.enableUaCheck)
        )
    }

    fun loadNativeWelcome(activity: Activity, layoutRes: Int) {
        loadNativeInternal(
            activity,
            "native_welcome",
            AdRemoteConfig.native_welcome,
            layoutRes,
            nativeWelcomeAdLive,
            ERainAd.getInstance().getShouldDisplayNativeWelcomeBack(AdRemoteConfig.native_welcome.enableUaCheck)
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
        // Force shouldDisplay = true to bypass SDK limits
        loadNativeInternal(
            activity,
            "preview_$configKey",
            config,
            layoutRes,
            nativeDashboardPreviewLive,
            shouldDisplay = true
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
        val skipReason = when {
            !config.isEnable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            !ignoreLimit && !ERainAd.getInstance().getShouldDisplayInterOnboarding(config.enableUaCheck) -> "ua_gate"
            else -> null
        }
        if (skipReason != null) {
            AdsTracking.interLoadSkipped("inter_onboarding", skipReason)
            interOnboarding = null
            return
        }
        AdsTracking.interLoadRequested("inter_onboarding", config.id)
        interOnboarding = ERainAd.getInstance().getInterstitialAds(
            context, config.id,
            AdsTracking.trackedInterLoadCallback("inter_onboarding", object : AdCallback() {}),
        )
    }

    fun showInterOnboarding(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        val interstitial = interOnboarding
        AdsTracking.interShowRequested("inter_onboarding")
        val blockReason = when {
            interstitial == null || !interstitial.isReady -> "not_ready"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            !ignoreLimit -> "ignore_limit_false"
            else -> null
        }
        if (blockReason == null && interstitial != null) {
            ERainAd.getInstance().forceShowInterstitial(
                context, interstitial,
                AdsTracking.trackedInterShowCallback("inter_onboarding", object : AdCallback() {
                    override fun onNextAction() {
                        super.onNextAction()
                        onAction()
                    }
                }, reloads = true), true,
            )
        } else {
            AdsTracking.interShowBlocked("inter_onboarding", blockReason ?: "unknown")
            onAction()
        }
    }

    fun loadInterWelcome(context: Context, ignoreLimit: Boolean = false) {
        val config = AdRemoteConfig.inter_welcome
        val skipReason = when {
            !config.isEnable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            !ignoreLimit -> "ignore_limit_false"
            else -> null
        }
        if (skipReason != null) {
            AdsTracking.interLoadSkipped("inter_welcome", skipReason)
            interWelcomeAd = null
            return
        }
        AdsTracking.interLoadRequested("inter_welcome", config.id)
        interWelcomeAd = ERainAd.getInstance().getInterstitialAds(
            context, config.id,
            AdsTracking.trackedInterLoadCallback("inter_welcome", object : AdCallback() {}),
        )
    }

    fun showInterWelcome(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        val interstitial = interWelcomeAd
        AdsTracking.interShowRequested("inter_welcome")
        val blockReason = when {
            interstitial == null || !interstitial.isReady -> "not_ready"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            !ignoreLimit -> "ignore_limit_false"
            else -> null
        }
        if (blockReason == null && interstitial != null) {
            ERainAd.getInstance().forceShowInterstitial(
                context, interstitial,
                AdsTracking.trackedInterShowCallback("inter_welcome", object : AdCallback() {
                    override fun onNextAction() {
                        super.onNextAction()
                        onAction()
                    }
                }, reloads = false), false,
            )
        } else {
            AdsTracking.interShowBlocked("inter_welcome", blockReason ?: "unknown")
            onAction()
        }
    }

    fun loadAndShowReward(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit
    ) {
        val config = AdRemoteConfig.reward_example
        val skipReason = when {
            !config.isEnable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(activity) -> "purchased"
            else -> null
        }
        if (skipReason != null) {
            AdsTracking.rewardLoadSkipped("reward_example", skipReason)
            onFailed()
            return
        }

        AdsTracking.rewardLoadRequested("reward_example", config.id)
        ERainAd.getInstance().initRewardAds(
            activity,
            config.id,
            object : AdCallback() {
                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    super.onRewardAdLoaded(rewardedAd)
                    AdsTracking.rewardLoaded("reward_example")
                    rewardExample = rewardedAd

                    var isEarn = false
                    AdsTracking.rewardShowRequested("reward_example")
                    ERainAd.getInstance().showRewardAds(
                        activity,
                        rewardExample,
                        object : RewardCallback {
                            override fun onUserEarnedReward(var1: RewardItem?) {
                                isEarn = true
                                rewardExample = null
                                AdsTracking.rewardEarned("reward_example")
                            }

                            override fun onRewardedAdClosed() {
                                AdsTracking.rewardClosed("reward_example", isEarn)
                                if (isEarn) onSuccess()
                                else onFailed()
                            }

                            override fun onRewardedAdFailedToShow(codeError: Int) {
                                rewardExample = null
                                AdsTracking.rewardShowFailed("reward_example", codeError)
                                onFailed()
                            }

                            override fun onAdClicked() {
                                AdsTracking.rewardClicked("reward_example")
                            }
                        }
                    )
                }

                override fun onAdFailedToLoad(i: LoadAdError?) {
                    super.onAdFailedToLoad(i)
                    AdsTracking.rewardLoadFailed("reward_example", i)
                    onFailed()
                }
            }
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
            removeBannerView(activity, frAds)
            // Mirror the SDK's own purchased gate so a silent internal no-op is not
            // recorded as a pending request
            if (AppPurchase.getInstance().isPurchased(activity)) {
                AdsTracking.bannerLoadSkipped(placement, "purchased")
            } else {
                AdsTracking.bannerLoadRequested(placement, adUnitConfig.id)
            }
            val callback = AdsTracking.trackedBannerCallback(placement, object : AdCallback() {
                override fun onAdFailedToLoad(i: LoadAdError?) {
                    super.onAdFailedToLoad(i)
                    frAds.goneView()
                    Timber.tag("AdsManager_Banner")
                        .d("Load banner on ${activity.javaClass.simpleName} failed by : ${i?.message}")
                }
            }, collapsible = isCollapse)
            if (isCollapse) ERainAd.getInstance().loadCollapsibleBanner(
                activity,
                adUnitConfig.id,
                AppConstant.CollapsibleGravity.BOTTOM,
                callback,
            )
            else ERainAd.getInstance().loadBanner(activity, adUnitConfig.id, callback)
        } else {
            AdsTracking.bannerLoadSkipped(placement, "disabled_config")
            frAds.removeAllViews()
            frAds.goneView()
        }
    }

    @SuppressLint("InflateParams")
    private fun removeBannerView(activity: Activity, frAds: FrameLayout) {
        try {
            val container = frAds.findViewById<FrameLayout>(com.ads.module.R.id.banner_container)
            if (container != null) {
                for (i in 0 until container.childCount) {
                    val view = container.getChildAt(i)
                    if (view is AdView) {
                        view.destroy()
                        container.removeView(view)
                    }
                }
            }
            val shimmerFrameLayout = LayoutInflater.from(activity)
                .inflate(com.ads.module.R.layout.layout_banner_control, null)
            frAds.removeAllViews()
            frAds.addView(shimmerFrameLayout)
        } catch (_: Exception) {
        }
    }

    fun clearAll() {
        if (interOnboarding?.isReady == true) {
            AdsTracking.discarded("inter_onboarding", "INTERSTITIAL", "clear_all")
        }
        if (interWelcomeAd?.isReady == true) {
            AdsTracking.discarded("inter_welcome", "INTERSTITIAL", "clear_all")
        }
        nativeSurveyAdLive.postValue(null)
        nativeConfirmUninstallAdLive.postValue(null)
        nativeWelcomeAdLive.postValue(null)
        interSplashAd = null
        interOnboarding = null
        interWelcomeAd = null
    }

    private fun Context.isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
        }
    }
}
