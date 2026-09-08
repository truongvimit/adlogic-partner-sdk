package io.onboardkit.ui

import android.view.View
import android.widget.FrameLayout
import io.onboardkit.R
import io.onboardkit.config.FullScreenSkipStyle

/** Both variants share the same click target, accessibility label and visibility timer. */
internal fun FrameLayout.applyFullScreenSkipStyle(style: FullScreenSkipStyle) {
    val close = style == FullScreenSkipStyle.CLOSE_ICON
    findViewById<View>(R.id.ob_skip_close).apply {
        visibility = if (close) View.VISIBLE else View.GONE
        setOnClickListener { this@applyFullScreenSkipStyle.performClick() }
    }
    findViewById<View>(R.id.ob_skip_text).apply {
        visibility = if (close) View.GONE else View.VISIBLE
        setOnClickListener { this@applyFullScreenSkipStyle.performClick() }
    }
}
