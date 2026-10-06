package com.ads.module.helper.adnative

import com.ads.module.config.settings.AdBehavior
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
     * `@id/ad_media`, `@id/ad_call_to_action`. A media wrapped in a ratio well puts
     * `@id/block_media` on the well instead. Blocks not listed are removed. Null keeps
     * the XML order untouched, and so does a layout without that container.
     */
    val components: List<NativeComponent>? = null,

    /** Height for `@id/ad_call_to_action` in dp. Null keeps the XML height. */
    val ctaHeightDp: Int? = null,

    /** CTA and attribution background color. Applied to both the real ad and its skeleton. */
    @ColorInt val ctaBackgroundColor: Int? = null,

    /**
     * The card's background color, painted on `@id/ad_background`, else `@id/ad_container`;
     * the drawable's shape and stroke stay. Null keeps the XML background.
     */
    @ColorInt val backgroundColor: Int? = null,

    /** Corner radius of the CTA background when [ctaBackgroundColor] is set. */
    val ctaCornerRadiusDp: Int = AdBehavior.defaultNumber("native.presentation.cta_corner_radius_dp").toInt(),
) {
    companion object {
        @JvmField val DEFAULT_CTA_CORNER_RADIUS_DP = AdBehavior.defaultNumber("native.presentation.cta_corner_radius_dp").toInt()
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
