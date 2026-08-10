package com.itg.template.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.billing.AppPurchase
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.inter_welcome
import com.itg.template.tracking.AdsTracking
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.uninstall.SurveyActivity
import com.itg.template.ui.component.welcome.WelcomeActivity
import com.itg.template.utils.Routes
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.ob5.ObFullScreenAdActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.question.ObQuestionActivity

class AppLifecycleObserver : DefaultLifecycleObserver {

    private val listActivityDisableResume = arrayListOf(
        SplashActivity::class.java,
        ObLanguageActivity::class.java,
        ObOnboardingHostActivity::class.java,
        ObFullScreenAdActivity::class.java,
        ObQuestionActivity::class.java,
        WelcomeActivity::class.java,
        SurveyActivity::class.java,
    )

    override fun onStart(owner: LifecycleOwner) {
        val currentActivity = GlobalApp.currentActivity ?: return
        val isDisable = listActivityDisableResume.any { clazz ->
            clazz.isInstance(currentActivity)
        }
        // Same gate chain and order as before; the reason is captured so blocked welcome-resume
        // opportunities become visible to the debug tracker
        val blockReason = when {
            isDisable -> "disabled_activity"
            !ResumeAdsEntryRule.shouldShowWelcomeOnResume() -> "mode_not_welcome"
            AppOpenManager.getInstance().isInterstitialShowing -> "interstitial_showing"
            AppPurchase.getInstance().isPurchased(currentActivity.applicationContext) -> "purchased"
            !ERainAd.getInstance().getShouldDisplayInterWelcomeBack(AdRemoteConfig.inter_welcome.enableUaCheck) -> "ua_gate"
            else -> null
        }
        if (blockReason == null) {
            AdsTracking.resumeOpportunity("welcome_launched")
            Routes.startWelcomeActivity(currentActivity)
        } else {
            AdsTracking.resumeOpportunity(blockReason)
        }
    }

    override fun onStop(owner: LifecycleOwner) {}
}
