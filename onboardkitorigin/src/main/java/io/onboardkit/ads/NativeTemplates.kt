package io.onboardkit.ads

import androidx.annotation.LayoutRes
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.NativeTemplate

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
     * included. There is no remote override for the template: a second source for the same decision
     * let a global change land everywhere except here.
     */
    internal fun templateForPlacement(placement: AdPlacement): NativeTemplate {
        val ads = OnboardingSdk.configOrNull()?.ads
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
            AdPlacement.QuestionInterstitial,
            AdPlacement.AppResume,
            -> NativeTemplate.CTA_BOTTOM
        }
    }
}
