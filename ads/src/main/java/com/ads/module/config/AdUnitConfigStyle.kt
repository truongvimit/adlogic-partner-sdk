package com.ads.module.config

import androidx.core.graphics.toColorInt
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeComponent

/** Clamp for the CTA height: below this the button stops being tappable. */
private const val MIN_CTA_HEIGHT_DP = 36

/** Above this it starts crowding the rest of the ad. */
private const val MAX_CTA_HEIGHT_DP = 52

/**
 * Maps a configured ad unit onto the presentation style, applied to both the loaded ad and the
 * auto-derived loading skeleton.
 *
 * Every value is clamped or dropped rather than trusted: these come from a remote console where a
 * typo must degrade to the default, not produce an unusable ad.
 */
fun AdUnitConfig.toNativeStyle(): NativeAdStyle = NativeAdStyle(
    // Empty keeps the XML layout instead of hiding every block.
    components = components.mapNotNull { NativeComponent.fromKey(it) }.distinct().ifEmpty { null },
    ctaHeightDp = heightCTA.coerceIn(MIN_CTA_HEIGHT_DP, MAX_CTA_HEIGHT_DP),
    ctaBackgroundColor = colorCTA.toColorOrNull(),
    backgroundColor = colorBackground.toColorOrNull(),
)

/** `"default"`, blank and unparsable all mean "keep what the layout draws". */
private fun String.toColorOrNull(): Int? =
    takeUnless { it.equals("default", ignoreCase = true) || it.isBlank() }
        ?.let { runCatching { it.toColorInt() }.getOrNull() }
