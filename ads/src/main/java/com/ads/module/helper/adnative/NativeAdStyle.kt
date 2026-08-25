package com.ads.module.helper.adnative

import androidx.annotation.ColorInt

/**
 * Presentation options for one native placement, applied by the SDK to both the loaded ad
 * view and the auto-derived loading skeleton — so the two always share the same geometry.
 *
 * Purely visual: no gating, waterfall, or remote-config semantics belong here. Map your
 * own config model onto this (clamping, color parsing, key mapping stay app-side) and pass
 * it via [NativeAdHelper.setNativeStyle]. No style set = the layout renders exactly as its
 * XML declares.
 */
data class NativeAdStyle(
    /**
     * Blocks to show, in top-to-bottom order. Requires the layout convention: a vertical
     * LinearLayout `@id/ad_container` holding `@id/block_icon_headline`, `@id/ad_body`,
     * `@id/ad_media`, `@id/ad_call_to_action`. Blocks not listed are removed. Null keeps
     * the XML order untouched.
     */
    val components: List<NativeComponent>? = null,

    /**
     * Where the CTA sits, when the screen ships one layout per position instead of reordering.
     *
     * Non-null pins the order to whatever the chosen layout declares, so [components] is read for
     * visibility only. Null hands the order to [components].
     */
    val ctaPosition: String? = null,

    /** Height for `@id/ad_call_to_action` in dp. Null keeps the XML height. */
    val ctaHeightDp: Int? = null,

    /** CTA background color. Applied to the real ad only — a skeleton stays grey. */
    @ColorInt val ctaBackgroundColor: Int? = null,

    /** Corner radius of the CTA background when [ctaBackgroundColor] is set. */
    val ctaCornerRadiusDp: Int = DEFAULT_CTA_CORNER_RADIUS_DP,
) {
    companion object {
        const val DEFAULT_CTA_CORNER_RADIUS_DP = 20
    }
}

/** The reorderable blocks of a native layout following the SDK's id convention. */
enum class NativeComponent(val key: String) {
    ICON_HEADLINE("icon_headline"),
    BODY("body"),
    MEDIA("media"),
    CTA("cta");

    companion object {
        /** Resolves a remote-config key ("icon_headline", "cta", ...); null if unknown. */
        @JvmStatic
        fun fromKey(key: String): NativeComponent? = entries.firstOrNull { it.key == key }
    }
}
