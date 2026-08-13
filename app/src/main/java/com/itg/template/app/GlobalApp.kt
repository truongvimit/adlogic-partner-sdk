package com.itg.template.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.application.AdsMultiDexApplication
import com.ads.module.billing.AppPurchase
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.itg.devconfig.DevConfig
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdsManager
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.tracking.installDebugSinks
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.uninstall.ConfirmUninstallActivity
import dagger.hilt.android.HiltAndroidApp
import io.onboardkit.OnboardingSdk
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.TrackkitEvents
import io.trackkit.firebase.FirebaseSink
import io.trackkit.sink.ConsoleSink
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import timber.log.Timber
import java.util.Arrays
import kotlin.jvm.java

@HiltAndroidApp
class GlobalApp : AdsMultiDexApplication() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: GlobalApp

        @SuppressLint("StaticFieldLeak")
        var currentActivity: Activity? = null
    }

    override fun onCreate() {
        super.onCreate()
        // First, before any SDK: everything below emits through Tracker, and events tracked
        // before install() would only be buffered, not attributed to this session.
        initTracking()
        // No MobileAds.initialize here: ERainAd.init -> Admob.init is the single canonical site
        // (it also logs per-adapter status); a second call just races the first for no gain.
        DevConfig.init(
            context = this,
            nkhStudioVersion = BuildConfig.ERAIN_STUDIO_VERSION,
            playServicesAdsVersion = BuildConfig.PLAY_SERVICES_ADS_VERSION,
            gdprModuleVersion = BuildConfig.GDPR_MODULE_VERSION
        )


        instance = this
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        initAdRemoteConfig()
        // Bind ad units to placements before any ad can load: the paid-event bridge in :ads reads
        // the placement back from the registry, and an unregistered unit reports as "unknown"
        AdsManager.registerAdPlacements()
        initAds()
        initOnboardKit()

        // Unconditionally register lifecycle observer and callbacks so dynamic welcome/resume toggling works during testing
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
        registerActivityLifecycleCallbacks(AppActivityLifecycleCallbacks())
    }

    private fun initTracking() {
        Tracker.install(
            this,
            TrackerConfig(
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                strictValidation = BuildConfig.DEBUG,
                logLevel = if (BuildConfig.DEBUG) 2 else 1,
            ),
        )
        // Firebase is the only destination Trackkit owns. Adjust is not a sink at all — the MMP
        // lives in :ads, where every signal it consumes already originates. See ARCHITECTURE.md.
        //
        // collectionFollowsConsent = false: Consent Mode already denies ad storage on refusal, and
        // a hard collection switch would also kill first_open, retention and the onboarding funnel.
        Tracker.addSink(FirebaseSink(collectionFollowsConsent = false))
        if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
        // AdTracer dashboard, debug builds only — a sink, so no ad call site knows it exists
        installDebugSinks()
    }


    private fun initAdRemoteConfig() {
        AdRemoteConfig.initializeFromAssets(this)
    }

    private fun initAds() {
        val environment =
            if (BuildConfig.DEBUG) ERainAdConfig.ENVIRONMENT_DEVELOP else ERainAdConfig.ENVIRONMENT_PRODUCTION
        mERainAdConfig = ERainAdConfig(this, environment)

        val adjustConfig = AdjustConfig(true, resources.getString(R.string.adjust_token))
        // Both tokens default to "" and nothing used to set them, so every impression and every
        // purchase reached Adjust as AdjustEvent("") and was dropped server-side without a trace.
        // Mint them on the Adjust dashboard; a blank one is skipped with a warning, never sent.
        adjustConfig.eventAdImpression = getString(R.string.event_token)
        adjustConfig.eventNamePurchase = getString(R.string.adjust_event_token_purchase)
        // Without it Adjust has nothing to forward to Meta, so Meta-attributed campaigns stay
        // empty in the Adjust dashboard.
        adjustConfig.fbAppId = getString(R.string.facebook_app_id)
        mERainAdConfig.adjustConfig = adjustConfig
        mERainAdConfig.facebookClientToken = resources.getString(R.string.facebook_client_token)
        // No adjustTokenTiktok here: every impression path falls back to
        // adjustConfig.eventAdImpression (set above) — one token, one door.
        // 0 = the ads module enforces no interval of its own. Frequency is owned by OnboardKit's
        // ob_ads_interstitial_interval_sec, which is remote-tunable and reports why an ad was
        // skipped. With 35 here the module silently swallowed the splash interstitial on any
        // relaunch inside 35s, and nothing downstream could tell that apart from a dismissal.
        mERainAdConfig.intervalInterstitialAd = 0

        mERainAdConfig.idAdResume = ""
        mERainAdConfig.listDeviceTest = listOf("1E25A7D66221E2116062EA114AFE2982")

        ERainAd.getInstance().init(this, mERainAdConfig)

        // Process-wide ad-module switches live in one place and are set once. Screen-by-screen
        // toggling is what let a splash finish itself before its own interstitial could show.
        ERainTuning.install()

        // OnboardKit excludes its own screens from app-resume when they start, so only the
        // app's own screens are listed here.
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)
        AppOpenManager.getInstance().disableAppResumeWithActivity(ConfirmUninstallActivity::class.java)
    }

    private fun initOnboardKit() {
        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            listener = OnboardingListener { context, outcome ->
                if (outcome is OnboardingOutcome.Completed) {
                    outcome.selectedLanguage?.let {
                        AppSharedPreferencesApp(context).languageCode = it
                    }
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        OnboardKitSetup.configure()
    }

    fun Context.getSystemLocaleString(@StringRes resId: Int): String {
        val systemConfig = Resources.getSystem().configuration
        val systemLocale = systemConfig.locales[0]

        val config = Configuration(resources.configuration)
        config.setLocale(systemLocale)

        val systemContext = createConfigurationContext(config)
        return systemContext.getString(resId)
    }
}
