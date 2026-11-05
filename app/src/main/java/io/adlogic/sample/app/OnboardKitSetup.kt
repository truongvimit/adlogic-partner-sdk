package io.adlogic.sample.app

import io.adlogic.sample.R
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.ResumeScreenConfig
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.GoalOption
import io.onboardkit.config.SelectionMode
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import timber.log.Timber

/**
 * App-owned resources plus live ad_config placement bindings. Configure once at startup.
 */
object OnboardKitSetup {

    fun configure() {
        onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            language = LanguageConfig(
            )

            // App catalog/default order. Remote onboarding.order may select/reorder these IDs.
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
                AdFullScreenStepDefinition(StepId.FULL1),
                ContentStepDefinition(
                    StepId.OB3,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = R.drawable.img_onboard_sample_3,
                ),
                AdFullScreenStepDefinition(StepId.FULL2),
                ContentStepDefinition(
                    StepId.OB4,
                    titleRes = R.string.onboarding_title_4,
                    subtitleRes = R.string.onboarding_des_4,
                    imageRes = R.drawable.img_onboard_sample_4,
                ),
            )
            // The screen app_resume.type native_resume opens on a return from the background.
            resumeScreen = ResumeScreenConfig(
                titleRes = R.string.onboarding_title_1,
                subtitleRes = R.string.onboarding_des_1,
                imageRes = R.drawable.img_onboard_sample_1,
            )
            // Standard placement keys resolve directly from the current ad_config on each use.
            // Behavioral defaults/overrides come from the two bundled settings JSON documents.
            this.ads = AdsConfig.fromAdConfig()
            privacyGoalsScreen = PrivacyGoalsScreenConfig(
                enabled = true,
                goal = GoalsScreenConfig(
                    selectionMode = SelectionMode.MULTIPLE,
                    minSelection = 1,
                    options = listOf(
                        GoalOption("learn", titleRes = R.string.goal_learn, imageRes = R.drawable.ic_goal_learn),
                        GoalOption("save_time", titleRes = R.string.goal_save_time, imageRes = R.drawable.ic_goal_time),
                        GoalOption("organise", titleRes = R.string.goal_organise, imageRes = R.drawable.ic_goal_organise),
                        GoalOption("fun", titleRes = R.string.goal_fun, imageRes = R.drawable.ic_goal_fun),
                        GoalOption("connect", titleRes = R.string.goal_connect, imageRes = R.drawable.ic_goal_connect),
                        GoalOption("progress", titleRes = R.string.goal_progress, imageRes = R.drawable.ic_goal_progress),
                    ),
                ),
            )

        }
            .onSuccess { config ->
                OnboardingSdk.configure(config)
                    .onFailure { Timber.e(it, "OnboardKit rejected the config") }
            }
            .onFailure { Timber.e(it, "OnboardKit config invalid") }
    }

}
