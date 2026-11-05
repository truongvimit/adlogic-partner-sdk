package io.onboardkit.ui.language

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.widget.ImageView
import io.onboardkit.R

/**
 * Pulses the "tap here" hand while [show], and stops it otherwise so a hidden or recycled view
 * cannot keep animating. Re-binding a hand that is already running is a no-op; restarting would
 * jump the pulse.
 */
internal fun ImageView.bindTapHint(show: Boolean) {
    val running = getTag(R.id.ob_tap_hint_animator) as? ValueAnimator
    if (!show) {
        if (visibility != View.GONE) {
            running?.cancel()
            setTag(R.id.ob_tap_hint_animator, null)
            scaleX = 1f
            scaleY = 1f
            setImageDrawable(null)
            visibility = View.GONE
        }
        return
    }
    if (visibility == View.VISIBLE && running != null) return
    visibility = View.VISIBLE
    setImageResource(R.drawable.ob_ic_tap_hand)
    val pulse = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.82f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.82f),
    ).apply {
        duration = 450
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }
    setTag(R.id.ob_tap_hint_animator, pulse)
    pulse.start()
}
