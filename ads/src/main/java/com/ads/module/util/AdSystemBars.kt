package com.ads.module.util

import android.view.Window
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** System-bar policy shared by SDK windows and partner screens. */
object AdSystemBars {
    /**
     * Defaults to hiding only navigation. Status and desktop caption bars remain visible.
     * Call again when the window regains focus after a dialog or ad. This changes visibility;
     * the host remains responsible for applying visible-bar insets to its content.
     */
    @JvmStatic
    @JvmOverloads
    fun setFullscreen(
        window: Window,
        showStatusBar: Boolean = true,
        showNavigationBar: Boolean = false,
        showCaptionBar: Boolean = true,
    ) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fun visibility(type: Int, visible: Boolean) {
            if (visible) controller.show(type) else controller.hide(type)
        }
        visibility(WindowInsetsCompat.Type.statusBars(), showStatusBar)
        visibility(WindowInsetsCompat.Type.navigationBars(), showNavigationBar)
        visibility(WindowInsetsCompat.Type.captionBar(), showCaptionBar)
    }
}
