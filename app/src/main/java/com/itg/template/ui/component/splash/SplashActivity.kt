package com.itg.template.ui.component.splash

import com.ads.module.admob.AppOpenManager
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.RemoteConfigUtils
import com.itg.template.ads.open_resume
import com.itg.template.app.OnboardKitSetup
import com.itg.template.app.ResumeAdsEntryRule
import com.itg.template.data.pref.AppSharedPreferencesApp
import com.itg.template.ui.bases.ConsentHandler
import com.itg.template.ui.bases.ext.isNetwork
import io.onboardkit.ui.splash.ObSplashActivity
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

    override suspend fun onConsentRequired() {
        if (appSharedPref.isConfirmConsent || appSharedPref.isUserGlobal || !isNetwork()) return
        suspendCancellableCoroutine { continuation ->
            consentHandler = ConsentHandler(
                activity = this,
                appSharedPref = appSharedPref,
                trackingSuffix = 1,
                onConsentFlowCompleted = {
                    if (continuation.isActive) continuation.resume(Unit)
                },
            )
            consentHandler?.requestConsent()
        }
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
        // Remote values were already activated by the SDK sync before this listener fires
    }

    override fun onDestroy() {
        consentHandler?.clear()
        consentHandler = null
        super.onDestroy()
    }
}
