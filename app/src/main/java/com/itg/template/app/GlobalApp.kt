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
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.application.AdsMultiDexApplication
import com.ads.module.billing.AppPurchase
import com.ads.module.config.AdjustConfig
import com.ads.module.config.ERainAdConfig
import com.itg.devconfig.DevConfig
import com.itg.template.BuildConfig
import com.itg.template.R
import com.ads.module.config.AdConfig
import com.ads.module.config.AdRemoteConfig
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.tracking.installDebugSinks
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.uninstall.ConfirmUninstallActivity
import com.itg.template.ui.component.uninstall.SurveyActivity
import com.itg.template.ui.component.welcome.WelcomeActivity
import dagger.hilt.android.HiltAndroidApp
import io.onboardkit.OnboardingSdk
import io.paykit.PayKit
import io.paykit.PayKitLogLevel
import io.paykit.PaywallPlacement
import io.suite.firebase.FirebaseConfigSource
import com.ads.module.consent.ConsentCenter
import com.ads.module.consent.ConsentOptions
import io.suite.firebase.FirebaseAdConfigSource
import io.paykit.integration.OnboardKitPaywallGate
import io.paykit.payKitConfig
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import io.trackkit.TrackkitEvents
import io.suite.firebase.FirebaseSink
import io.trackkit.sink.ConsoleSink
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.erain.ERainTuning
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

        /** UMP logs this id on the first debug run; it is what forces the EEA form on that device. */
        private const val CONSENT_TEST_DEVICE_HASHED_ID = "ED3576D8FCF2F8C52AD8E98B4CFA4005"
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
        // Loads assets/ad_config.json and binds every id to its placement; the SDK re-binds on
        // each remote refresh, so there is nothing to call again later.
        AdRemoteConfig.initializeFromAssets(this)
        // Where fresher ad units come from. Installed here, applied by the SDK — no call site has
        // to bridge remote config into the ad layer by hand.
        AdConfig.install(FirebaseAdConfigSource())
        initAds()
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


    private fun initAds() {
        // The UMP knobs the removed GDPR module took from its callback: the same 20s budget for
        // the round trip, and the hashed id that makes a debug build see the EEA form wherever it
        // is actually running. Without the id, ConsentDebugSettings has no test device to force
        // and a debug run outside the EEA never gets a form to look at.
        ConsentCenter.configure(
            ConsentOptions(
                timeoutMs = AppConstants.DEFAULT_TIME_OUT_GDPR,
                testDeviceHashedId = CONSENT_TEST_DEVICE_HASHED_ID,
            ),
        )

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
        // 0 = no interval until remote config says otherwise; SplashActivity applies
        // `interstitial_interval_sec` on every fetch. With 35 baked in here the module silently
        // swallowed the splash interstitial on any relaunch inside 35s, and nothing downstream
        // could tell that apart from a dismissal.
        mERainAdConfig.intervalInterstitialAd = 0

        mERainAdConfig.idAdResume = ""
        mERainAdConfig.listDeviceTest = listOf("1E25A7D66221E2116062EA114AFE2982")

        ERainAd.getInstance().init(this, mERainAdConfig)

        // Process-wide ad-module switches live in one place and are set once. Screen-by-screen
        // toggling is what let a splash finish itself before its own interstitial could show.
        //
        // Among them: `InterstitialAdManager.defaultNextAction`, which install() sets to
        // `InterNextAction.UnderAd` — every interstitial hands control back on the same tick as
        // show(), so the next screen starts underneath the ad and is painted before it closes
        // (Apero's `openActivityAfterShowInterAds = true`). Assign it here to change the app-wide
        // default; one placement that needs the other timing passes `InterNextAction.AfterDismiss`
        // to its own show call instead — see WelcomeActivity. An app without OnboardKit gets the
        // SDK default, AfterDismiss, until it sets one.
        ERainTuning.install()

        // OnboardKit excludes its own screens from app-resume when they start, so only the
        // app's own screens are listed here.
        // Both resume paths read this one list, so a screen listed here is off-limits to the
        // app-open ad and to the welcome-back screen alike.
        AppOpenManager.getInstance().disableAppResumeWithActivity(SplashActivity::class.java)
        AppOpenManager.getInstance().disableAppResumeWithActivity(ConfirmUninstallActivity::class.java)
        AppOpenManager.getInstance().disableAppResumeWithActivity(WelcomeActivity::class.java)
        AppOpenManager.getInstance().disableAppResumeWithActivity(SurveyActivity::class.java)
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
