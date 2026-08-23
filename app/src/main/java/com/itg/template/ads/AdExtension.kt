package com.itg.template.ads

import android.graphics.Color
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeComponent
import androidx.core.graphics.toColorInt

/**
 * Maps the remote-config ad unit onto the SDK's presentation style. App product policy
 * lives here — the CTA height clamp and the color-string format — while the SDK stays
 * agnostic of the app's config model. The SDK applies the style to both the loaded ad and
 * the auto-derived loading skeleton.
 */
fun AdUnitConfig.toNativeStyle(): NativeAdStyle = NativeAdStyle(
    // All keys unknown -> canonical order, matching the old binder's normalization
    components = components.mapNotNull { NativeComponent.fromKey(it) }.distinct()
        .ifEmpty { NativeComponent.entries },
    ctaHeightDp = heightCTA.coerceIn(36, 52),
    ctaBackgroundColor = colorCTA
        .takeUnless { it == "default" || it.isBlank() }
        ?.let { runCatching { it.toColorInt() }.getOrNull() },
)
