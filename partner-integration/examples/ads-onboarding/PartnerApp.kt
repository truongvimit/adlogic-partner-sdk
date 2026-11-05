package com.example.app

import android.app.Application
import android.content.Intent
import com.ads.module.ads.AdsSdk
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdsSdkConfig
import com.ads.module.config.AdjustConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.sdk.AdsSdkProvider
import io.onboardkit.ads.sdk.AdsSdkTuning
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.trackkit.Tracker
import io.trackkit.TrackerConfig

class PartnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tracker.install(this, TrackerConfig(appVersionCode = BuildConfig.VERSION_CODE.toLong()))
        AdRemoteConfig.initializeFromAssets(this)

        val environment = if (BuildConfig.DEBUG) AdsSdkConfig.ENVIRONMENT_DEVELOP
                          else AdsSdkConfig.ENVIRONMENT_PRODUCTION
        val adjustToken = getString(R.string.adjust_token).trim()
        val adsConfig = AdsSdkConfig(this, environment).apply {
            if (adjustToken.isNotEmpty()) {
                adjustConfig = AdjustConfig(true, adjustToken).apply {
                    fbAppId = getString(R.string.facebook_app_id)
                    eventAdImpression = getString(R.string.event_token).trim()
                    eventNamePurchase = getString(R.string.adjust_event_token_purchase).trim()
                }
            }
        }
        AdsSdk.getInstance().init(this, adsConfig)
        AdsSdkTuning.install()

        OnboardingSdk.install(this) {
            adProvider = AdsSdkProvider()
            listener = OnboardingListener { context, outcome ->
                if (outcome is OnboardingOutcome.Completed) {
                    outcome.selectedLanguage?.let { code ->
                        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putString(LANGUAGE, code).apply()
                    }
                }
                val extras = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras?.let { putExtras(it) } },
                )
            }
        }
        OnboardKitSetup.configure()
        OnboardingSdk.setFlowLogging(BuildConfig.DEBUG)
    }

    companion object {
        const val PREFS = "partner_app"
        const val LANGUAGE = "language_code"
    }
}
