package io.adlogic.sample.ads

import android.app.Application
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.AdsSdk
import com.ads.module.config.AdConfig
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdjustConfig
import com.ads.module.config.AdsSdkConfig
import com.ads.module.config.settings.SettingsRegistry
import com.ads.module.consent.ConsentCenter
import com.ads.module.consent.ConsentOptions
import io.adlogic.sample.BuildConfig
import io.adlogic.sample.R
import io.adlogic.sample.app.AppConstants
import io.adlogic.sample.ui.component.splash.SplashActivity
import io.adlogic.sample.ui.component.uninstall.ConfirmUninstallActivity
import io.adlogic.sample.ui.component.uninstall.SurveyActivity
import io.onboardkit.ads.sdk.AdsSdkTuning
import io.suite.firebase.FirebaseAdConfigSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App configuration, initialization and resume policy. Screens call the SDK directly. */
object AdsAppManager : RemoteConfigUtils.Listener {
    fun initialize(application: Application): AdsSdkConfig {
        AdRemoteConfig.initializeFromAssets(application)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = AppConstants.DEFAULT_TIME_OUT_GDPR))

        val environment = if (BuildConfig.DEBUG) AdsSdkConfig.ENVIRONMENT_DEVELOP
            else AdsSdkConfig.ENVIRONMENT_PRODUCTION
        val config = AdsSdkConfig(application, environment).apply {
            adjustConfig = AdjustConfig(true, application.getString(R.string.adjust_token)).apply {
                eventAdImpression = application.getString(R.string.event_token)
                eventNamePurchase = application.getString(R.string.adjust_event_token_purchase)
                fbAppId = application.getString(R.string.facebook_app_id)
            }
            facebookClientToken = application.getString(R.string.facebook_client_token)
            // The process-owned remote callback applies the interval after fetching it.
            intervalInterstitialAd = 0
            idAdResume = ""
            listDeviceTest = listOf("1E25A7D66221E2116062EA114AFE2982")
        }
        AdsSdk.getInstance().init(application, config)
        // Retains the app's UnderAd default.
        AdsSdkTuning.install()

        // The same exclusions apply to every resume type.
        AppOpenManager.getInstance().apply {
            disableAppResumeWithActivity(SplashActivity::class.java)
            disableAppResumeWithActivity(ConfirmUninstallActivity::class.java)
            disableAppResumeWithActivity(SurveyActivity::class.java)
        }
        // Product flags and caps must still apply if remote finishes after splash is gone.
        SettingsRegistry.addFetchListener("demo-ad-policy") {
            withContext(Dispatchers.Main.immediate) { applyAdPolicy() }
        }
        RemoteConfigUtils.init(application, this)
        return config
    }

    override fun loadSuccess() {
        applyAdPolicy()
    }

    private fun applyAdPolicy() {
        AdsSdk.getInstance().setMaxClickAdsPerDay(RemoteConfigUtils.getMaxClickAdsPerDay())
        AdsSdk.getInstance().setIntervalInterstitialAd(RemoteConfigUtils.getInterstitialIntervalSec())
    }
}
