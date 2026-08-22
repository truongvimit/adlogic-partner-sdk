package com.itg.template.ui.component.splash

import androidx.lifecycle.lifecycleScope
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.billing.Billing
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.RemoteConfigUtils
import com.itg.template.ads.open_resume
import com.itg.template.app.OnboardKitSetup
import com.itg.template.app.ResumeAdsEntryRule
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.ui.bases.ConsentHandler
import com.itg.template.ui.bases.ext.isNetwork
import io.onboardkit.ui.splash.ObSplashActivity
import io.paykit.PayKit
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Launcher splash. All flow logic (timers, barrier, interstitial, navigation to
 * language/onboarding/main) lives in OnboardKit — this class only plugs in the app's
 * consent dialog and remote-config bootstrap.
 */
class SplashActivity : ObSplashActivity(), RemoteConfigUtils.Listener {

    private val appSharedPref by lazy { AppSharedPreferencesApp(this) }
    private var consentHandler: ConsentHandler? = null

    /**
     * Returns whether ads may be requested. Anything other than a completed consent flow answers
     * `false`, so no ad request goes out while the form is still on screen.
     */
    override suspend fun onConsentRequired(): Boolean {
        // Already answered in a previous session, or a region where UMP does not apply
        if (appSharedPref.isConfirmConsent || appSharedPref.isUserGlobal) return true
        if (!isNetwork()) return false
        return suspendCancellableCoroutine { continuation ->
            consentHandler = ConsentHandler(
                activity = this,
                appSharedPref = appSharedPref,
                trackingSuffix = 1,
                onConsentFlowCompleted = { canPersonalized ->
                    if (continuation.isActive) continuation.resume(canPersonalized)
                },
            )
            consentHandler?.requestConsent()
        }
    }

    /**
     * Waits for Play to say whether this user is premium, since every ad request below is gated on
     * the answer. Verification started in `Application.onCreate`, so this normally returns at once.
     */
    override suspend fun onInitBilling() {
        // Fire-and-forget: the paywall document is not needed until the SPLASH_INTER checkpoint,
        // and awaiting it here would hold every splash ad behind a remote fetch.
        lifecycleScope.launch { PayKit.sync(timeoutMs = 3_000) }
        Billing.awaitReady(timeoutMs = 5_000)
    }

    override fun onRemoteFetched() {
        RemoteConfigUtils.init(this, this)
        AdRemoteConfig.initialize(this, RemoteConfigUtils.getAdRemoteConfig())
        // Ad unit ids may have changed remotely — rebuild the OnboardKit config with fresh ids
        OnboardKitSetup.configure()

        if (ResumeAdsEntryRule.shouldEnableOpenResume()) {
            AppOpenManager.getInstance().setAppResumeAdId(AdRemoteConfig.open_resume.id)
            AppOpenManager.getInstance().enableAppResume()
        } else {
            AppOpenManager.getInstance().disableAppResume()
        }
    }

    override fun loadSuccess() {
        // UA owns the interstitial click cap from here: 0 turns it off, N caps each ad unit at N
        // clicks per 24h. Applied on every fetch so a mid-session activation takes effect without
        // a relaunch; until the fetch lands the SDK default (off) stands.
        ERainAd.getInstance().setMaxClickAdsPerDay(RemoteConfigUtils.getMaxClickAdsPerDay())
    }

    override fun onDestroy() {
        consentHandler?.clear()
        consentHandler = null
        // The fetch may still be in flight; without this the process-wide RemoteConfigUtils holds
        // this Activity until it returns.
        RemoteConfigUtils.detach(this)
        super.onDestroy()
    }
}
