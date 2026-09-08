package io.onboardkit.ui

import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.onboardkit.R
import io.onboardkit.config.FullScreenSkipStyle

/** Both variants share the same click target, accessibility label and visibility timer. */
internal fun TextView.applyFullScreenSkipStyle(style: FullScreenSkipStyle) {
    val close = style == FullScreenSkipStyle.CLOSE_ICON
    val dp = resources.displayMetrics.density
    minWidth = (48 * dp).toInt()
    minHeight = (48 * dp).toInt()
    gravity = Gravity.CENTER
    contentDescription = context.getString(R.string.ob_skip)
    text = if (close) "" else context.getString(R.string.ob_skip)
    val icon = if (close) ContextCompat.getDrawable(context, R.drawable.ob_ic_skip_close) else null
    setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    if (close) setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
}
