package io.onboardkit.ads

import com.ads.module.config.AdRemoteConfig
import androidx.annotation.LayoutRes
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.config.NativeTemplate

/** Maps host fallback frames and ad_config CTA positions to SDK layouts. */
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
        // These horizontal slots use the same 4:3 media-left frame at preload and bind time.
        // A positionCTA override must not turn one of the ALT ads vertical.
        if (placement == AdPlacement.SplashInlineNative || placement.isPrivacyGoalsNative)
            R.layout.ob_layout_native_media_left
        else layoutFor(templateForPlacement(placement))

    /**
     * The template a placement renders with, from its ad_config positionCTA.
     *
     * The template only picks the layout frame. Which blocks show and in what order is `components`
     * in the ad config, applied at bind time — so one edit there moves every slot, onboarding
     * included. LFO and content onboarding frames are selected only by positionCTA. Colors, height
     * and components stay in ad_config and are resolved independently.
     */
    internal fun templateForPlacement(placement: AdPlacement): NativeTemplate {
        val ads = OnboardingSdk.configOrNull()?.ads
        fun positionCta(): NativeTemplate? {
            if (placement != AdPlacement.Language1 && placement != AdPlacement.Language2 &&
                placement != AdPlacement.WelcomeBack1 && placement != AdPlacement.WelcomeBack2 &&
                placement !is AdPlacement.StepNative) return null
            return when (ads?.placementKeyFor(placement)?.let { AdRemoteConfig.getInstance().ads[it]?.positionCTA }) {
                "TOP" -> NativeTemplate.CTA_TOP
                "BOTTOM" -> NativeTemplate.CTA_BOTTOM
                else -> null
            }
        }
        // AdRemoteConfig already merges remote > asset > code per field, including explicit clears.
        positionCta()?.let { return it }
        return when (placement) {
            // Welcome Back renders exactly like the LFO slot.
            AdPlacement.Language1, AdPlacement.Language2,
            AdPlacement.WelcomeBack1, AdPlacement.WelcomeBack2,
            -> ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM

            // Fixed, not configurable: the modal is 328dp wide and sized to a horizontal card.
            // Any other template overflows it, so this is not a slot a partner may re-skin.
            AdPlacement.LanguageConfirm -> NativeTemplate.DIALOG

            is AdPlacement.StepNative -> ads?.contentStepTemplate ?: NativeTemplate.CTA_BOTTOM

            is AdPlacement.StepFullScreen, AdPlacement.Ob5, AdPlacement.SplashNative -> NativeTemplate.FULL_SCREEN

            // SplashInlineNative never reaches here — layoutForPlacement answers it directly.
            AdPlacement.SplashBanner,
            AdPlacement.SplashInlineNative,
            AdPlacement.SplashInterstitial,
            AdPlacement.AfterOnboardingInterstitial,
            AdPlacement.AppResume,
            -> NativeTemplate.CTA_BOTTOM
        }
    }
}
