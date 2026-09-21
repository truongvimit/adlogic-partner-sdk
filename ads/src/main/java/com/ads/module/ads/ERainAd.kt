package com.ads.module.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.webkit.WebView
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.LogLevel
import com.ads.module.R
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.engine.BannerEngine
import com.ads.module.engine.InterstitialEngine
import com.ads.module.engine.adMainScope
import com.ads.module.event.AdjustInstallReferrer
import com.ads.module.event.ERainAdjust
import com.ads.module.event.MmpTracking
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.banner.BannerType
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.util.AppUtil
import com.ads.module.util.SharePreferenceUtils
import com.facebook.FacebookSdk
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.initialization.InitializationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The module's entry point: [init] once from `Application.onCreate`, then use the helpers. */
object ERainAd {
    private const val TAG_ADJUST = "ERainAdjust"
    private const val TAG_GMA = "ERainStudio"

    @JvmStatic
    fun getInstance(): ERainAd = this

    var adConfig: ERainAdConfig? = null
        private set

    internal var appContext: Context? = null
        private set

    @Volatile
    internal var skipResumeAfterAdClick: Boolean =
        AdBehavior.defaultBool("app_open.presentation.skip_after_ad_click")
        private set

    /**
     * The UA gate: a force-organic placement shows only to paid installs; any other always shows.
     * Organic until Adjust attribution lands, and before [init].
     */
    fun shouldDisplayForUa(isForceOrganic: Boolean): Boolean = !isForceOrganic || !isOrganic()

    /** Interstitial clicks per ad unit per 24h before that unit stops loading; `0` disables it. */
    fun setMaxClickAdsPerDay(maxClickAdsPerDay: Int) {
        InterstitialEngine.setMaxClickAdsPerDay(maxClickAdsPerDay)
    }

    /** AutoBuffer interval in seconds, gating both preload and show; `0` disables it. */
    fun setIntervalInterstitialAd(intervalSeconds: Int) {
        val config = adConfig ?: return
        config.intervalInterstitialAd = intervalSeconds
        InterstitialAutoBuffer.onGateChanged()
    }

    /** Whether a click on any ad suppresses the next app-resume ad. */
    fun setDisableAdResumeWhenClickAds(disable: Boolean) {
        skipResumeAfterAdClick = disable
    }

    fun init(app: Application, adConfig: ERainAdConfig?) {
        AdBehavior.initialize(app)
        if (adConfig == null) throw RuntimeException("Cant not set ERainAdConfig null")
        this.adConfig = adConfig
        AppUtil.VARIANT_DEV = adConfig.isVariantDev
        // Before any purchase can fire: :billingkit reports revenue only through the Trackkit seam.
        MmpTracking.ensureInstalled()
        if (adConfig.isEnableAdjust!!) setupAdjust(adConfig, adConfig.isVariantDev)
        initGma(app, adConfig.listDeviceTest)
        AppOpenManager.getInstance().init(adConfig.application, adConfig.idAdResume)
        // Never overwrite a real manifest ClientToken with the legacy placeholder.
        val facebookToken = adConfig.facebookClientToken
        if (!TextUtils.isEmpty(facebookToken) && facebookToken!!.trim().isNotEmpty() &&
            ERainAdConfig.DEFAULT_TOKEN_FACEBOOK_SDK != facebookToken
        ) {
            FacebookSdk.setClientToken(facebookToken)
        }
        @Suppress("DEPRECATION")
        FacebookSdk.sdkInitialize(app)
    }

    /** Loads a banner into the activity's `banner_container`. */
    fun loadBanner(activity: Activity?, id: String?, callback: AdCallback?) {
        val host = checkNotNull(activity) { "loadBanner needs the host Activity" }
        BannerEngine.load(
            host, checkNotNull(id) { "loadBanner needs an ad unit id" },
            host.findViewById(R.id.banner_container),
            host.findViewById(R.id.shimmer_container_banner), BannerType.Normal, callback,
        )
    }

    private fun isOrganic(): Boolean {
        val config = adConfig ?: return true
        return SharePreferenceUtils.getIsOrganic(config.application!!)
    }

    private fun initGma(app: Application, testDevices: List<String>?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = Application.getProcessName()
            if (app.packageName != processName) WebView.setDataDirectorySuffix(processName)
        }
        appContext = app.applicationContext
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setTestDeviceIds(testDevices).build(),
        )
        // initialize() blocks on adapter start-up; it must never run on the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(app) { status -> adMainScope.launch { logAdapters(status) } }
        }
    }

    private fun logAdapters(initializationStatus: InitializationStatus) {
        for ((adapterClass, status) in initializationStatus.adapterStatusMap) {
            if (status == null) continue
            Log.d(
                TAG_GMA,
                String.format(
                    "Adapter name: %s, Description: %s, Latency: %d",
                    adapterClass, status.description, status.latency,
                ),
            )
        }
    }

    private fun setupAdjust(adConfig: ERainAdConfig, buildDebug: Boolean?) {
        val application = adConfig.application
        val adjustConfig = adConfig.adjustConfig!!
        val adjustToken = adjustConfig.adjustToken
        if (TextUtils.isEmpty(adjustToken)) {
            Log.e(
                TAG_ADJUST,
                "adjustConfig.enableAdjust is true but adjustToken is empty — " +
                    "Adjust stays off. Set the app token from the Adjust dashboard.",
            )
            return
        }

        val debug = buildDebug!!
        val environment =
            if (debug) AdjustConfig.ENVIRONMENT_SANDBOX else AdjustConfig.ENVIRONMENT_PRODUCTION
        val config = AdjustConfig(application, adjustToken, environment)

        // VERBOSE logs the app token and attribution payload: a QA aid, a production logcat leak.
        config.setLogLevel(if (debug) LogLevel.VERBOSE else LogLevel.WARN)
        config.enablePreinstallTracking()
        config.enableSendingInBackground()
        if (!TextUtils.isEmpty(adjustConfig.fbAppId)) config.setFbAppId(adjustConfig.fbAppId)

        config.setOnAttributionChangedListener { attribution ->
            val organic = "Organic" == attribution.trackerName ||
                attribution.network?.equals("organic", ignoreCase = true) == true
            SharePreferenceUtils.setIsOrganic(application!!, organic)
            Log.i(
                TAG_ADJUST,
                "attribution: network=" + attribution.network + " campaign=" +
                    attribution.campaign + " organic=" + organic,
            )
        }
        config.setOnEventTrackingFailedListener { failure ->
            Log.e(TAG_ADJUST, "event rejected: $failure")
        }
        config.setOnSessionTrackingFailedListener { failure ->
            Log.e(TAG_ADJUST, "session rejected: $failure")
        }
        if (debug) {
            config.setOnEventTrackingSucceededListener { success ->
                Log.d(TAG_ADJUST, "event ok: $success")
            }
            config.setOnSessionTrackingSucceededListener { success ->
                Log.d(TAG_ADJUST, "session ok: $success")
            }
        }

        if (!config.isValid) {
            Log.e(
                TAG_ADJUST,
                "AdjustConfig rejected (token/environment/context) — Adjust stays off",
            )
            return
        }
        Adjust.initSdk(config)
        ERainAdjust.markInitialized()
        AdjustInstallReferrer.readOnce(application!!)
        Log.i(TAG_ADJUST, "Adjust initialised ($environment)")
    }
}
