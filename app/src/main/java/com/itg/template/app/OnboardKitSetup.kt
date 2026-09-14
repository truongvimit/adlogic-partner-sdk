package com.itg.template.app

import com.itg.template.R
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import timber.log.Timber

/**
 * App-owned resources plus live ad_config placement bindings. Configure once at startup.
 */
object OnboardKitSetup {

    /**
     * The pages of this flow, by role rather than by number.
     *
     * Two numbering schemes meet here and they do not line up: remote config counts **content
     * pages** (`native_ob1..3`) while [StepId] counts **positions in the flow**, and the ad-only
     * page sits at position 3 between them. So content page 3 is `StepId.OB4`, and `StepId.OB3`
     * is the ad page — which reads like a typo everywhere except here.
     *
     * | Position | StepId | Ad unit key | What the user sees |
     * |---|---|---|---|
     * | 1 | `OB1` | `native_ob1` | content |
     * | 2 | `OB2` | `native_ob2` | content |
     * | 3 | `OB3` | `native_fsob` | **ad only, full screen** |
     * | 4 | `OB4` | `native_ob3` | content |
     *
     * Remote on/off still follows the position: `ob_enable_step_ob3` hides the ad page.
     */
    private object Page {
        val CONTENT_1 = StepId.OB1
        val CONTENT_2 = StepId.OB2
        val AD_FULL_SCREEN = StepId.OB3
        val CONTENT_3 = StepId.OB4
    }

    fun configure() {
        onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            language = LanguageConfig(
            )

            // Three content pages, with native_fsob between content pages 2 and 3.
            // StepId.OB3 is the fullscreen position; StepId.OB4 reads native_ob3.
            steps(
                ContentStepDefinition(
                    Page.CONTENT_1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = R.drawable.img_onboard_sample_1,
                ),
                ContentStepDefinition(
                    Page.CONTENT_2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = R.drawable.img_onboard_sample_2,
                ),
                AdFullScreenStepDefinition(Page.AD_FULL_SCREEN),
                ContentStepDefinition(
                    Page.CONTENT_3,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = R.drawable.img_onboard_sample_4,
                ),
            )
            // Standard placement keys resolve directly from the current ad_config on each use.
            // Behavioral defaults/overrides come from the two bundled settings JSON documents.
            this.ads = AdsConfig.fromAdConfig()

        }
            .onSuccess { config ->
                OnboardingSdk.configure(config)
                    .onFailure { Timber.e(it, "OnboardKit rejected the config") }
            }
            .onFailure { Timber.e(it, "OnboardKit config invalid") }
    }

}
