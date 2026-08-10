package io.onboardkit.ads

import androidx.annotation.LayoutRes
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

    @LayoutRes
    fun shimmerFor(template: NativeTemplate): Int = when (template) {
        NativeTemplate.CTA_BOTTOM -> R.layout.ob_shimmer_native_cta_bottom
        NativeTemplate.CTA_TOP -> R.layout.ob_shimmer_native_cta_top
        NativeTemplate.COMPACT -> R.layout.ob_shimmer_native_compact
        NativeTemplate.FULL_SCREEN -> R.layout.ob_shimmer_native_fullscreen
    }
}
