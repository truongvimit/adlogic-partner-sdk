package com.ads.module.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Copies the host activity window's system-bar visibility onto a dialog window. A full-window
 * dialog otherwise shows the bars for as long as it is up, undoing any immersive state the
 * activity holds (splash and onboarding hide the navigation bar while a loading cover waits
 * for an ad).
 */
internal object HostWindowBars {

    @JvmStatic
    fun mirror(dialog: Dialog) {
        val window = dialog.window
        val host = activityFrom(dialog.context)
        if (window == null || host == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Bar visibility lives in legacy view flags here and is not readable from insets.
            window.decorView.systemUiVisibility =
                host.window.decorView.systemUiVisibility
            return
        }
        val insets: WindowInsetsCompat? =
            ViewCompat.getRootWindowInsets(host.window.decorView)
        if (insets == null) return
        val controller =
            WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (!insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun activityFrom(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
