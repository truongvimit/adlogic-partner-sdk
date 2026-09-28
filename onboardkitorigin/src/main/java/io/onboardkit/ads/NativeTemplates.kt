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
        // These horizontal slots use the same 4:3 media-left frame at preload and bind time.
        // A content-template / positionCTA override must not turn one of the ALT ads vertical.
        if (placement == AdPlacement.SplashInlineNative || placement.isPrivacyGoalsNative)
            R.layout.ob_layout_native_media_left
        else layoutFor(templateForPlacement(placement))

    /**
     * The template a placement renders with, from [io.onboardkit.config.AdsConfig].
     *
     * The template only picks the layout frame. Which blocks show and in what order is `components`
     * in the ad config, applied at bind time — so one edit there moves every slot, onboarding
     * included. Remote outranks the app at every layer: a remote onboarding template, then a remote
     * ad_config positionCTA, then the app asset's template, then the shipped ad_config positionCTA,
     * then the host template. Colors, height and components stay in ad_config.
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
        fun explicit(read: (String) -> Any?): NativeTemplate? = templatePaths.firstNotNullOfOrNull { path ->
            (read(path) as? String)?.takeIf { it.isNotBlank() }
        }?.let(NativeTemplate::valueOf)
        fun present(read: (String) -> Any?): Boolean = templatePaths.any { read(it) != null }
        fun positionCta(): NativeTemplate? {
            if (placement != AdPlacement.Language1 && placement != AdPlacement.Language2 &&
                placement !is AdPlacement.StepNative && placement != AdPlacement.QuestionNative) return null
            return when (ads?.placementKeyFor(placement)?.let { AdRemoteConfig.getInstance().ads[it]?.positionCTA }) {
                "TOP" -> NativeTemplate.CTA_TOP
                "BOTTOM" -> NativeTemplate.CTA_BOTTOM
                else -> null
            }
        }
        val adKey = ads?.placementKeyFor(placement)
        val remoteAdConfig = adKey?.let(AdRemoteConfig::remoteDeclares) == true
        val remotePosition = adKey?.let { AdRemoteConfig.remoteDeclaresField(it, "positionCTA") } == true
        explicit(values::remoteValue)?.let { return it }
        if (present(values::remoteValue)) {
            if (remotePosition) positionCta()?.let { return it }
            // An explicit empty template clears a lower source. The resolved host config is the
            // code/default tier and may itself contain a broader same-source template.
            return when (placement) {
                AdPlacement.Language1, AdPlacement.Language2 -> ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM
                is AdPlacement.StepNative -> ads?.contentStepTemplate ?: NativeTemplate.CTA_BOTTOM
                AdPlacement.QuestionNative -> ads?.questionTemplate ?: NativeTemplate.CTA_BOTTOM
                else -> NativeTemplate.CTA_BOTTOM
            }
        }
        if (remoteAdConfig && remotePosition) positionCta()?.let { return it }
        explicit(values::assetValue)?.let { return it }
        if (present(values::assetValue)) return when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 -> ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM
            is AdPlacement.StepNative -> ads?.contentStepTemplate ?: NativeTemplate.CTA_BOTTOM
            AdPlacement.QuestionNative -> ads?.questionTemplate ?: NativeTemplate.CTA_BOTTOM
            else -> NativeTemplate.CTA_BOTTOM
        }
        if (!remoteAdConfig) positionCta()?.let { return it }
        return when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 ->
                ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM

            // Fixed, not configurable: the modal is 328dp wide and sized to a horizontal card.
            // Any other template overflows it, so this is not a slot a partner may re-skin.
            AdPlacement.LanguageConfirm -> NativeTemplate.DIALOG

            is AdPlacement.StepNative -> ads?.contentStepTemplate ?: NativeTemplate.CTA_BOTTOM

            AdPlacement.QuestionNative -> ads?.questionTemplate ?: NativeTemplate.CTA_BOTTOM

            is AdPlacement.StepFullScreen, AdPlacement.Ob5, AdPlacement.SplashNative -> NativeTemplate.FULL_SCREEN

            // SplashInlineNative never reaches here — layoutForPlacement answers it directly.
            AdPlacement.SplashBanner,
            AdPlacement.SplashInlineNative,
            AdPlacement.SplashInterstitial,
            AdPlacement.AfterOnboardingInterstitial,
            AdPlacement.QuestionInterstitial,
            AdPlacement.AppResume,
            -> NativeTemplate.CTA_BOTTOM
        }
    }
}
