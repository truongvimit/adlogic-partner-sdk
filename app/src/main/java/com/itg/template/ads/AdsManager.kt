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
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.billing.AppPurchase
import com.ads.module.funtion.AdCallback
import com.ads.module.tracking.AdTracking
import io.trackkit.AdFormat
import com.ads.module.funtion.AdType
import com.ads.module.funtion.RewardCallback
import com.ads.module.util.AppConstant
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
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
        shouldDisplay: Boolean = true,
    ) {
        val skipReason = when {
            !config.isUsable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(activity) -> "purchased"
            !activity.isNetworkAvailable() -> "offline"
            !shouldDisplay -> "ua_gate"
            else -> null
        }
        // A load that never happened, and why — the only ad facts the SDK cannot observe itself
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.NATIVE, skipReason)
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
        val passesUaGate = ignoreLimit ||
            ERainAd.getInstance()
                .getShouldDisplayInterOnboarding(AdRemoteConfig.inter_onboarding.enableUaCheck)
        loadInterstitial(
            context,
            "inter_onboarding",
            AdRemoteConfig.inter_onboarding,
            interOnboarding,
            passesUaGate,
        ) { interOnboarding = it }
    }

    fun showInterOnboarding(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        showInterstitial(context, "inter_onboarding", interOnboarding, onAction)
        interOnboarding = null
    }

    fun loadInterWelcome(context: Context, ignoreLimit: Boolean = false) {
        val passesUaGate = ignoreLimit ||
            ERainAd.getInstance()
                .getShouldDisplayInterWelcomeBack(AdRemoteConfig.inter_welcome.enableUaCheck)
        loadInterstitial(
            context,
            "inter_welcome",
            AdRemoteConfig.inter_welcome,
            interWelcomeAd,
            passesUaGate,
        ) { interWelcomeAd = it }
    }

    fun showInterWelcome(context: Context, ignoreLimit: Boolean = false, onAction: () -> Unit) {
        showInterstitial(context, "inter_welcome", interWelcomeAd, onAction)
        interWelcomeAd = null
    }

    /**
     * Runs the placement's waterfall and hands the winner to [onResolved], or `null` when nothing
     * filled. Keeps [buffered] instead when it is still usable.
     *
     * `ignoreLimit` only bypasses the UA gate — it used to gate the load itself, so every call
     * with the default argument skipped with `ignore_limit_false` and never had an ad to show.
     */
    private fun loadInterstitial(
        context: Context,
        placement: String,
        config: AdUnitConfig,
        buffered: ApInterstitialAd?,
        passesUaGate: Boolean,
        onResolved: (ApInterstitialAd?) -> Unit,
    ) {
        // One cached ad per placement; re-requesting over a ready one burns a request and the fill
        if (buffered?.isReady == true) return
        val skipReason = when {
            !config.isUsable -> "disabled_config"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            !context.isNetworkAvailable() -> "offline"
            !passesUaGate -> "ua_gate"
            else -> null
        }
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.INTERSTITIAL, skipReason)
            onResolved(null)
            return
        }
        // waterfallIds, not id: the list is the waterfall, highest floor first
        AdTracking.request(placement, AdFormat.INTERSTITIAL, config.waterfallIds.first())
        AdWaterfall.loadInterstitial(
            context,
            config.waterfallIds,
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    onResolved(apInterstitialAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    onResolved(null)
                }
            },
        )
    }

    /**
     * Shows [ad] and runs [onAction] exactly once, whatever the module reports.
     *
     * Wiring only `onNextAction` — as this did — loses the callback entirely when the show fails,
     * because `openActivityAfterShowInterAds` is pinned on and the module then reports the failure
     * through `onAdFailedToShow` alone. The screen was left waiting forever.
     */
    private fun showInterstitial(
        context: Context,
        placement: String,
        ad: ApInterstitialAd?,
        onAction: () -> Unit,
    ) {
        val blockReason = when {
            ad == null || !ad.isReady -> "not_ready"
            AppPurchase.getInstance().isPurchased(context) -> "purchased"
            else -> null
        }
        if (blockReason != null || ad == null) {
            AdTracking.skipped(placement, AdFormat.INTERSTITIAL, blockReason ?: "unknown")
            onAction()
            return
        }
        val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val proceed = { if (proceeded.compareAndSet(false, true)) onAction() }
        ERainAd.getInstance().forceShowInterstitial(
            context, ad,
            object : AdCallback() {
                override fun onNextAction() = proceed()

                override fun onAdClosed() = proceed()

                override fun onAdFailedToShow(adError: com.google.android.gms.ads.AdError?) {
                    AdTracking.skipped(placement, AdFormat.INTERSTITIAL, "failed_to_show")
                    Timber.tag("AdsManager").w("Show $placement failed: ${adError?.message}")
                    proceed()
                }
            },
            // Reloading is the caller's job; letting the module do it too double-requests the unit
            false,
        )
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
            AdTracking.skipped("reward_example", AdFormat.REWARDED, skipReason)
            onFailed()
            return
        }

        AdTracking.request("reward_example", AdFormat.REWARDED, config.id)
        ERainAd.getInstance().initRewardAds(
            activity,
            config.id,
            object : AdCallback() {
                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    super.onRewardAdLoaded(rewardedAd)
                    rewardExample = rewardedAd

                    var isEarn = false
                    ERainAd.getInstance().showRewardAds(
                        activity,
                        rewardExample,
                        object : RewardCallback {
                            override fun onUserEarnedReward(var1: RewardItem?) {
                                isEarn = true
                                rewardExample = null
                            }

                            override fun onRewardedAdClosed() {
                                if (isEarn) onSuccess()
                                else onFailed()
                            }

                            override fun onRewardedAdFailedToShow(codeError: Int) {
                                rewardExample = null
                                onFailed()
                            }

                            override fun onAdClicked() = Unit
                        }
                    )
                }

                override fun onAdFailedToLoad(i: LoadAdError?) {
                    super.onAdFailedToLoad(i)
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
                AdTracking.skipped(placement, AdFormat.BANNER, "purchased")
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
            AdTracking.skipped(placement, AdFormat.BANNER, "disabled_config")
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
