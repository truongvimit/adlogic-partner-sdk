package io.onboardkit.ads

import com.ads.module.config.AdRemoteConfig
import androidx.annotation.LayoutRes
import io.onboardkit.OnboardingSdk
import io.onboardkit.R

/**
 * Which SDK layout each native placement inflates: the `ad_config.<key>.templateId` catalog for
 * card slots, a fixed frame for the confirm modal and the full-screen format.
 *
 * Only the frame is chosen here. Block order is the placement's `components`, applied at bind
 * time by the templates that can reorder.
 */
object NativeTemplates {

    private const val TEMPLATE_LFO = 1
    private const val TEMPLATE_MEDIA_LEFT = 2

    /** The catalog behind `ad_config.<key>.templateId`: the one place a number becomes a layout. */
    private val templateLayouts: Map<Int, Int> = mapOf(
        TEMPLATE_LFO to R.layout.ob_layout_native_lfo,
        TEMPLATE_MEDIA_LEFT to R.layout.ob_layout_native_media_left,
        3 to R.layout.ob_layout_native_med_1_91,
    )

    /** Null for a number the catalog does not know, which leaves the placement on its default. */
    @LayoutRes
    internal fun layoutForTemplateId(templateId: Int): Int? = templateLayouts[templateId]

    /** True for a layout the SDK owns, as opposed to one a host supplied. */
    internal fun isSdkLayout(@LayoutRes layoutRes: Int): Boolean =
        layoutRes in templateLayouts.values ||
            layoutRes == R.layout.ob_layout_native_dialog ||
            layoutRes == R.layout.ob_layout_native_fullscreen

    /**
     * The layout a placement's native is inflated with — the single answer for both the preload
     * and the screen that binds it.
     *
     * A native is inflated at request time, so a screen resolving the template differently from
     * the preload chain silently re-requests instead of using what is already buffered.
     */
    @LayoutRes
    internal fun layoutForPlacement(placement: AdPlacement): Int =
        templateIdFor(placement)?.let(templateLayouts::getValue) ?: when (placement) {
            // The modal is 328dp wide and sized to a horizontal card; any other frame overflows it.
            AdPlacement.LanguageConfirm -> R.layout.ob_layout_native_dialog
            else -> R.layout.ob_layout_native_fullscreen
        }

    /** The analytics variant: the template a card slot renders, else the fixed frame's name. */
    internal fun variantFor(placement: AdPlacement): String =
        templateIdFor(placement)?.let { "TEMPLATE_$it" } ?: when (placement) {
            AdPlacement.LanguageConfirm -> "DIALOG"
            else -> "FULL_SCREEN"
        }

    /**
     * The template a card slot renders: its catalogued `templateId`, else the slot's default.
     * Null for the confirm modal and the full-screen format, which keep their fixed frame.
     */
    private fun templateIdFor(placement: AdPlacement): Int? {
        val default = when (placement) {
            AdPlacement.LanguageConfirm,
            AdPlacement.SplashNative,
            AdPlacement.Ob5,
            is AdPlacement.StepFullScreen,
            -> return null
            // Horizontal slots: a splash bottom bar and the Privacy/Goal card.
            AdPlacement.SplashInlineNative -> TEMPLATE_MEDIA_LEFT
            is AdPlacement.StepNative -> if (placement.isPrivacyGoalsNative) TEMPLATE_MEDIA_LEFT else TEMPLATE_LFO
            else -> TEMPLATE_LFO
        }
        val key = OnboardingSdk.configOrNull()?.ads?.placementKeyFor(placement)
        val declared = key?.let { AdRemoteConfig.getInstance().ads[it]?.templateId }
        return declared?.takeIf { it in templateLayouts } ?: default
    }
}
