package io.onboardkit.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.onboardkit.R
import io.onboardkit.config.FullScreenSkipPosition
import io.onboardkit.config.FullScreenSkipStyle

/** Both variants share the same click target, accessibility label and visibility timer. */
internal fun FrameLayout.applyFullScreenSkip(
    style: FullScreenSkipStyle,
    position: FullScreenSkipPosition,
) {
    val close = style == FullScreenSkipStyle.CLOSE_ICON
    findViewById<View>(R.id.ob_skip_close).apply {
        visibility = if (close) View.VISIBLE else View.GONE
        setOnClickListener { this@applyFullScreenSkip.performClick() }
    }
    findViewById<View>(R.id.ob_skip_text).apply {
        visibility = if (close) View.GONE else View.VISIBLE
        setOnClickListener { this@applyFullScreenSkip.performClick() }
    }
    applyPosition(position)
}

/**
 * The layout declares the inset on one edge only, so flipping gravity alone would leave the
 * button flush against the other edge. Mirroring both margins keeps the two sides the same
 * distance from their edge, and the top margin is untouched either way.
 */
private fun FrameLayout.applyPosition(position: FullScreenSkipPosition) {
    val params = layoutParams as? FrameLayout.LayoutParams ?: return
    val inset = maxOf(params.marginStart, params.marginEnd)
    params.marginStart = inset
    params.marginEnd = inset
    // START/END, not LEFT/RIGHT: RIGHT is what the screen already did before this flag existed,
    // and an RTL locale keeps mirroring the whole screen as it does today.
    params.gravity = Gravity.TOP or when (position) {
        FullScreenSkipPosition.RIGHT -> Gravity.END
        FullScreenSkipPosition.LEFT -> Gravity.START
    }
    layoutParams = params
}
