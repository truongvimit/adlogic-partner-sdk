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
    }

    /** Remote value → template; unknown values keep the compile-time default. */
    fun fromRemote(value: String, fallback: NativeTemplate): NativeTemplate = when (value) {
        "cta_bottom" -> NativeTemplate.CTA_BOTTOM
        "cta_top" -> NativeTemplate.CTA_TOP
        "compact" -> NativeTemplate.COMPACT
        "fullscreen" -> NativeTemplate.FULL_SCREEN
        else -> fallback
    }

    /**
     * The layout a placement's native is inflated with — the single answer for both the preload
     * and the screen that binds it.
     *
     * A native is inflated at request time, so a screen resolving the template differently from
     * the preload chain silently re-requests instead of using what is already buffered.
     */
    @LayoutRes
    internal fun layoutForPlacement(placement: AdPlacement): Int {
        val ads = OnboardingSdk.configOrNull()?.ads
        val flags = OnboardingSdk.flags()
        val template = when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 ->
                fromRemote(flags.templateLanguage, ads?.languageTemplate ?: NativeTemplate.CTA_BOTTOM)

            is AdPlacement.StepNative ->
                fromRemote(flags.templateContent, ads?.contentStepTemplate ?: NativeTemplate.CTA_TOP)

            AdPlacement.QuestionNative ->
                fromRemote(flags.templateQuestion, ads?.questionTemplate ?: NativeTemplate.CTA_BOTTOM)

            is AdPlacement.StepFullScreen, AdPlacement.Ob5 -> NativeTemplate.FULL_SCREEN

            AdPlacement.SplashBanner,
            AdPlacement.SplashInterstitial,
            AdPlacement.QuestionInterstitial,
            AdPlacement.AppResume,
            -> NativeTemplate.CTA_BOTTOM
        }
        return layoutFor(template)
    }
}
