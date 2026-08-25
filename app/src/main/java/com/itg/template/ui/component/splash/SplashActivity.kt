package com.itg.template.ui.component.splash

import androidx.lifecycle.lifecycleScope
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.billing.Billing
import com.ads.module.config.AdRemoteConfig
import com.itg.template.ads.RemoteConfigUtils
import com.itg.template.ads.open_resume
import com.itg.template.app.OnboardKitSetup
import com.itg.template.app.ResumeAdsEntryRule
import io.onboardkit.ui.splash.ObSplashActivity
import io.paykit.PayKit
import kotlinx.coroutines.launch

/**
 * Launcher splash. The whole flow — consent, timers, barrier, interstitial, navigation — lives in
 * OnboardKit; the ad config refreshes itself through the installed [com.ads.module.config.AdConfigSource].
 * What remains here is this app's own product wiring.
 */
class SplashActivity : ObSplashActivity(), RemoteConfigUtils.Listener {

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
        // The ad units already refreshed inside the SDK's remote step. This fetch is for the app's
        // own flags — force update, the uninstall widget — which the SDK knows nothing about.
        RemoteConfigUtils.init(this, this)
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
        // Same story for the interval: one owner (:ads, which holds the impression timestamp), one
        // remote key, applied to every placement rather than only the onboarding ones.
        ERainAd.getInstance().setIntervalInterstitialAd(RemoteConfigUtils.getInterstitialIntervalSec())
    }

    override fun onDestroy() {
        // The fetch may still be in flight; without this the process-wide RemoteConfigUtils holds
        // this Activity until it returns.
        RemoteConfigUtils.detach(this)
        super.onDestroy()
    }
}
