package com.itg.template.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.application.AdsMultiDexApplication
import com.ads.module.billing.AppPurchase
import com.itg.devconfig.DevConfig
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.ads.AdsAppManager
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.tracking.installDebugSinks
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.uninstall.ConfirmUninstallActivity
import dagger.hilt.android.HiltAndroidApp
import io.onboardkit.OnboardingSdk
import io.paykit.PayKit
import io.paykit.PayKitLogLevel
import io.paykit.PaywallPlacement
import io.suite.firebase.FirebaseConfigSource
import io.paykit.integration.OnboardKitPaywallGate
import io.paykit.payKitConfig
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.TrackkitEvents
import io.suite.firebase.FirebaseSink
import io.trackkit.sink.ConsoleSink
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.core.OnboardingListener
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.ui.splash.SplashEntry
import timber.log.Timber
import java.util.Arrays
import kotlin.jvm.java

@HiltAndroidApp
class GlobalApp : AdsMultiDexApplication() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: GlobalApp
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
        mERainAdConfig = AdsAppManager.initialize(this)
        // Before OnboardKit: its paywall gate calls straight into PayKit at the first checkpoint.
        initPayKit()
        initOnboardKit()

        // The welcome-resume observer. The activity it acts on comes from AppOpenManager, which
        // already tracks it from onActivityStarted — a second app-side tracker was a third copy of
        // the same state, and the one that updated too late to be useful here.
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
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


    private fun initPayKit() {
        payKitConfig {
            termsUrl = getString(R.string.paywall_terms_url)
            privacyUrl = getString(R.string.paywall_privacy_url)
            // Fail-closed by design: a placement missing here shows nothing until remote config
            // names it. SPLASH stays out — the splash interstitial already owns that slot.
            defaultPlacements = setOf(
                PaywallPlacement.AFTER_ONBOARDING,
                PaywallPlacement.SETTING,
            )
            exitButtonDelayMs = 3_000
            logLevel = if (BuildConfig.DEBUG) PayKitLogLevel.DEBUG else PayKitLogLevel.WARN
            // This app's own catalogue, used until a remote fetch lands; it names no placements,
            // so defaultPlacements above stays in charge.
            fallbackConfigRes = R.raw.paywall_config
        }.onSuccess { config ->
            PayKit.install(this, config)
            // Vendor adapter, kept out of :paykit itself. SplashActivity does the actual fetch.
            PayKit.configSource(FirebaseConfigSource())
        }.onFailure {
            Timber.e(it, "PayKit config rejected — the paywall stays off")
        }
    }

    private fun initOnboardKit() {
        io.onboardkit.remote.ObRemote.installFetchDelegate(io.suite.firebase.RemoteConfigClient::fetchAndActivate)
        OnboardingSdk.install(this) {
            adProvider = ERainAdProvider()
            // Wired after initPayKit(): the gate answers from PayKit's state, so onboarding would
            // see "not ready" at every checkpoint if the paywall were installed later.
            paywallGate = OnboardKitPaywallGate()
            listener = OnboardingListener { context, outcome ->
                if (outcome is OnboardingOutcome.Completed) {
                    outcome.selectedLanguage?.let {
                        AppSharedPreferencesApp(context).languageCode = it
                    }
                }
                // Aborted drops the passthrough on purpose: the user backed out of the flow the
                // entry started, so its feature must not reopen.
                val passthrough = when (outcome) {
                    is OnboardingOutcome.Completed -> outcome.passthrough
                    is OnboardingOutcome.Skipped -> outcome.passthrough
                    is OnboardingOutcome.Aborted -> null
                }
                // The per-app part of an entry is only this: which screen it lands on. The intent,
                // the ad key and the timing are the SDK's standard SplashEntry wiring.
                val destination = when (SplashEntry.from(passthrough)) {
                    SplashEntry.UNINSTALL -> ConfirmUninstallActivity::class.java
                    else -> MainActivity::class.java
                }
                context.startActivity(
                    // NEW_TASK only, never CLEAR_TASK — under UNDER_AD this runs while the ad is
                    // on screen, and clearing the task would finish the Activity hosting it.
                    Intent(context, destination)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { passthrough?.let(::putExtras) },
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
