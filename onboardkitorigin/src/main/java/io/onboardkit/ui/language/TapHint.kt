package io.onboardkit.ui.language

import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import io.onboardkit.R

/**
 * Loops the animated "tap here" hand while [show], and clears it otherwise so a hidden or
 * recycled view cannot keep animating. Re-binding a hand that is already running is a no-op;
 * reloading would restart the loop.
 */
internal fun ImageView.bindTapHint(show: Boolean) {
    if (!show) {
        if (visibility != View.GONE) {
            Glide.with(this).clear(this)
            setImageDrawable(null)
            visibility = View.GONE
        }
        return
    }
    if (isVisible && drawable != null) return
    visibility = View.VISIBLE
    Glide.with(this).load(R.raw.ob_anim_hand_tap).into(this)
}
