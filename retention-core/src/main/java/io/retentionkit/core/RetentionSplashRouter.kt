package io.retentionkit.core

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Vendor-free front door for non-Onboard hosts. Both cold and warm taps start the host Splash.
 * Core adds the exact typed envelope; the host forwards it after its own Splash/ad completion.
 * Final feature navigation uses a separate router and must not call this front door recursively.
 */
class RetentionSplashRouter(private val splashActivity: Class<out Activity>) : RetentionRouter {
    override fun createIntent(context: Context, entry: RetentionEntry): Intent =
        Intent(context, splashActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
