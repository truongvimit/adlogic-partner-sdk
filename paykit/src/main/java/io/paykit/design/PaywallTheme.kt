package io.paykit.design

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.paykit.R

/** Name of one entry in the remote `tokens` block. */
@JvmInline
value class ColorToken(val key: String)

/** Resolved ARGB colours for one paywall presentation. */
data class PaywallTheme(
    @ColorInt val textPrimary: Int,
    @ColorInt val textSecondary: Int,
    @ColorInt val accent: Int,
    @ColorInt val background: Int,
    @ColorInt val surface: Int,
    @ColorInt val onAccent: Int,
    val ctaGradient: IntArray?,
) {

    // IntArray compares by identity, so the generated equals would call two identical themes
    // different and force the renderer to redraw on every state emission.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaywallTheme) return false
        return textPrimary == other.textPrimary &&
            textSecondary == other.textSecondary &&
            accent == other.accent &&
            background == other.background &&
            surface == other.surface &&
            onAccent == other.onAccent &&
            (ctaGradient?.contentEquals(other.ctaGradient) ?: (other.ctaGradient == null))
    }

    override fun hashCode(): Int {
        var result = textPrimary
        result = 31 * result + textSecondary
        result = 31 * result + accent
        result = 31 * result + background
        result = 31 * result + surface
        result = 31 * result + onAccent
        result = 31 * result + (ctaGradient?.contentHashCode() ?: 0)
        return result
    }

    companion object {

        /** The palette compiled into the SDK; every remote token falls back to this per key. */
        fun bundled(context: Context): PaywallTheme = PaywallTheme(
            textPrimary = ContextCompat.getColor(context, R.color.pw_text_primary),
            textSecondary = ContextCompat.getColor(context, R.color.pw_text_secondary),
            accent = ContextCompat.getColor(context, R.color.pw_accent),
            background = ContextCompat.getColor(context, R.color.pw_background),
            surface = ContextCompat.getColor(context, R.color.pw_surface),
            onAccent = ContextCompat.getColor(context, R.color.pw_on_accent),
            ctaGradient = intArrayOf(
                ContextCompat.getColor(context, R.color.pw_cta_gradient_start),
                ContextCompat.getColor(context, R.color.pw_cta_gradient_end),
            ),
        )
    }
}
