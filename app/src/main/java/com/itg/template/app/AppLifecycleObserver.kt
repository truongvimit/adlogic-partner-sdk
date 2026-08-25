package com.itg.template.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ads.module.admob.AppOpenManager
import com.ads.module.helper.AdGate
import com.ads.module.tracking.AdTracking
import com.ads.module.config.AdRemoteConfig
import com.itg.template.ads.inter_welcome
import io.trackkit.AdFormat
import com.itg.template.utils.Routes

class AppLifecycleObserver : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        // From the SDK's tracker, which is populated in onActivityStarted. The app's own copy is
        // set in onActivityResumed — after this callback — so on the first foreground of every
        // process it was still null and the welcome screen was silently skipped.
        val currentActivity = AppOpenManager.getInstance().currentActivity ?: return
        // One exclusion list for both resume paths, owned by AppOpenManager: SDK screens register
        // themselves there, and the app registers its own in GlobalApp.
        val isDisable = AppOpenManager.getInstance().isResumeSuppressedFor(currentActivity)
        // Same gate chain and order as before; the reason is captured so a welcome-resume the app
        // declined is still reported — the ads SDK is never called and cannot know it happened
        val blockReason = when {
            isDisable -> "disabled_activity"
            !ResumeAdsEntryRule.shouldShowWelcomeOnResume() -> "mode_not_welcome"
            AppOpenManager.getInstance().isInterstitialShowing -> "interstitial_showing"
            AdGate.isPurchased(currentActivity.applicationContext) -> "purchased"
            !AdGate.passesUaGate(AdRemoteConfig.inter_welcome.enableUaCheck) -> "ua_gate"
            else -> null
        }
        if (blockReason == null) {
            Routes.startWelcomeActivity(currentActivity)
        } else {
            AdTracking.skipped("inter_welcome", AdFormat.INTERSTITIAL, blockReason)
        }
    }

    override fun onStop(owner: LifecycleOwner) {}
}
