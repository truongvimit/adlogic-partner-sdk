package io.onboardkit.ads

import com.ads.module.config.AdRemoteConfig
import androidx.annotation.LayoutRes
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.NativeTemplate
import io.onboardkit.remote.OnboardingSettings

/** Maps template names (compile-time enum or remote string) to the SDK's Figma layouts. */
object NativeTemplates {

    @LayoutRes
    fun layoutFor(template: NativeTemplate): Int = when (template) {
        NativeTemplate.CTA_BOTTOM -> R.layout.ob_layout_native_cta_bottom
        NativeTemplate.CTA_TOP -> R.layout.ob_layout_native_cta_top
        NativeTemplate.COMPACT -> R.layout.ob_layout_native_compact
        NativeTemplate.FULL_SCREEN -> R.layout.ob_layout_native_fullscreen
        NativeTemplate.DIALOG -> R.layout.ob_layout_native_dialog
    }

    /**
     * The layout a placement's native is inflated with — the single answer for both the preload
     * and the screen that binds it.
     *
     * A native is inflated at request time, so a screen resolving the template differently from
     * the preload chain silently re-requests instead of using what is already buffered.
     */
    @LayoutRes
    internal fun layoutForPlacement(placement: AdPlacement): Int =
        layoutFor(templateForPlacement(placement))

    /**
     * The template a placement renders with, from [io.onboardkit.config.AdsConfig].
     *
     * The template only picks the layout frame. Which blocks show and in what order is `components`
     * in the ad config, applied at bind time — so one edit there moves every slot, onboarding
     * included. Explicit onboarding template overrides pick the SDK frame; otherwise ad_config's
     * positionCTA is used before the host template. Colors, height and components stay in ad_config.
     */
    internal fun templateForPlacement(placement: AdPlacement): NativeTemplate {
        val ads = OnboardingSdk.configOrNull()?.ads
        val values = OnboardingSettings.values
        val templatePaths = when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 -> listOf("lfo.native_template")
            is AdPlacement.StepNative -> listOf("onboarding.steps.${placement.stepId.value}.native_template", "onboarding.ads.content_template")
            AdPlacement.QuestionNative -> listOf("question.native.template")
            else -> emptyList()
        }
        templatePaths.firstNotNullOfOrNull { path ->
            values.string(path, "").takeIf { values.hasOverride(path) && it.isNotBlank() }
        }?.let { return NativeTemplate.valueOf(it) }
        if (placement == AdPlacement.Language1 || placement == AdPlacement.Language2 ||
            placement is AdPlacement.StepNative || placement == AdPlacement.QuestionNative) {
            val key = ads?.placementKeyFor(placement)
            when (key?.let { AdRemoteConfig.getInstance().ads[it]?.positionCTA }) {
                "TOP" -> return NativeTemplate.CTA_TOP
                "BOTTOM" -> return NativeTemplate.CTA_BOTTOM
            }
        }
        return when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 ->
                ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM

            // Fixed, not configurable: the modal is 328dp wide and sized to a horizontal card.
            // Any other template overflows it, so this is not a slot a partner may re-skin.
            AdPlacement.LanguageConfirm -> NativeTemplate.DIALOG

            is AdPlacement.StepNative -> ads?.contentStepTemplate ?: NativeTemplate.CTA_BOTTOM

            AdPlacement.QuestionNative -> ads?.questionTemplate ?: NativeTemplate.CTA_BOTTOM

            is AdPlacement.StepFullScreen, AdPlacement.Ob5 -> NativeTemplate.FULL_SCREEN

            AdPlacement.SplashBanner,
            AdPlacement.SplashInterstitial,
            AdPlacement.AfterOnboardingInterstitial,
            AdPlacement.QuestionInterstitial,
            AdPlacement.AppResume,
            -> NativeTemplate.CTA_BOTTOM
        }
    }
}
