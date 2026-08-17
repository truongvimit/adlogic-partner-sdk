package com.itg.template.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.billing.AppPurchase
import com.ads.module.tracking.AdTracking
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.inter_welcome
import com.itg.template.ui.component.splash.SplashActivity
import io.trackkit.AdFormat
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
        // Same gate chain and order as before; the reason is captured so a welcome-resume the app
        // declined is still reported — the ads SDK is never called and cannot know it happened
        val blockReason = when {
            isDisable -> "disabled_activity"
            !ResumeAdsEntryRule.shouldShowWelcomeOnResume() -> "mode_not_welcome"
            AppOpenManager.getInstance().isInterstitialShowing -> "interstitial_showing"
            AppPurchase.getInstance().isPurchased(currentActivity.applicationContext) -> "purchased"
            !ERainAd.getInstance().shouldDisplayForUa(AdRemoteConfig.inter_welcome.enableUaCheck) -> "ua_gate"
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
