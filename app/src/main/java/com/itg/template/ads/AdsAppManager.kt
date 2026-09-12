package com.itg.template.ads

import android.app.Application
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdConfig
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.consent.ConsentOptions
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.app.AppConstants
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.uninstall.ConfirmUninstallActivity
import com.itg.template.ui.component.uninstall.SurveyActivity
import com.itg.template.ui.component.welcome.WelcomeActivity
import io.onboardkit.ads.erain.ERainTuning
import io.suite.firebase.FirebaseAdConfigSource

/** App configuration, initialization and resume policy. Screens call the SDK directly. */
object AdsAppManager {
    fun initialize(application: Application): ERainAdConfig {
        AdRemoteConfig.initializeFromAssets(application)
        AdConfig.install(FirebaseAdConfigSource())
        ConsentCenter.configure(ConsentOptions(timeoutMs = AppConstants.DEFAULT_TIME_OUT_GDPR))

        val environment = if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP
            else ERainAdConfig.ENVIRONMENT_PRODUCTION
        val config = ERainAdConfig(application, environment).apply {
            adjustConfig = AdjustConfig(true, application.getString(R.string.adjust_token)).apply {
                eventAdImpression = application.getString(R.string.event_token)
                eventNamePurchase = application.getString(R.string.adjust_event_token_purchase)
                fbAppId = application.getString(R.string.facebook_app_id)
            }
            facebookClientToken = application.getString(R.string.facebook_client_token)
            // SplashActivity applies the remote interval after fetching it.
            intervalInterstitialAd = 0
            idAdResume = ""
            listDeviceTest = listOf("1E25A7D66221E2116062EA114AFE2982")
        }
        ERainAd.getInstance().init(application, config)
        // Retains the app's UnderAd default; Welcome explicitly uses AfterDismiss.
        ERainTuning.install()

        // The same exclusions apply to app-open and the welcome-back screen.
        AppOpenManager.getInstance().apply {
            disableAppResumeWithActivity(SplashActivity::class.java)
            disableAppResumeWithActivity(ConfirmUninstallActivity::class.java)
            disableAppResumeWithActivity(WelcomeActivity::class.java)
            disableAppResumeWithActivity(SurveyActivity::class.java)
        }
        return config
    }
}
