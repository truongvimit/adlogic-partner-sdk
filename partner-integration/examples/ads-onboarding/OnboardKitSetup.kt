package com.example.app

import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId

object OnboardKitSetup {
    fun configure() {
        val config = onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            steps(
                ContentStepDefinition(
                    StepId.OB1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = R.drawable.img_onboard_sample_1,
                ),
                ContentStepDefinition(
                    StepId.OB2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = R.drawable.img_onboard_sample_2,
                ),
                AdFullScreenStepDefinition(StepId.OB3),
                ContentStepDefinition(
                    StepId.OB4,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = R.drawable.img_onboard_sample_4,
                ),
            )
            // Standard keys stay linked to the current ad_config after remote fetch.
            // Override associations here only when your app uses different placement names.
            ads = AdsConfig.fromAdConfig()
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
    }
}
