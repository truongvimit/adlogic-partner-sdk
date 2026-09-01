package com.ads.module.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.Window;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Copies the host activity window's system-bar visibility onto a dialog window. A full-window
 * dialog otherwise shows the bars for as long as it is up, undoing any immersive state the
 * activity holds (splash and onboarding hide the navigation bar while a loading cover waits
 * for an ad).
 */
final class HostWindowBars {

    private HostWindowBars() {
    }

    static void mirror(Dialog dialog) {
        Window window = dialog.getWindow();
        Activity host = activityFrom(dialog.getContext());
        if (window == null || host == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Bar visibility lives in legacy view flags here and is not readable from insets.
            window.getDecorView().setSystemUiVisibility(
                    host.getWindow().getDecorView().getSystemUiVisibility());
            return;
        }
        WindowInsetsCompat insets =
                ViewCompat.getRootWindowInsets(host.getWindow().getDecorView());
        if (insets == null) return;
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(window, window.getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
            controller.hide(WindowInsetsCompat.Type.statusBars());
        }
        if (!insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
            controller.hide(WindowInsetsCompat.Type.navigationBars());
        }
    }

    private static Activity activityFrom(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
