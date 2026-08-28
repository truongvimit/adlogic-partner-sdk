package io.onboardkit.ui.splash

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The entries that route through the splash besides the launcher tap, and the `ad_config` key
 * each one spends on the splash interstitial.
 *
 * Every partner app has these three entries or a subset, always with this wiring, which is why
 * the enum lives in the SDK: an app that follows the standard keys declares nothing. The tap
 * fires [intent], [ObSplashActivity] resolves the entry's [interKey] on its own — full
 * `<key>_high`, `<key>_high1`, …, `<key>` waterfall — and a key that is missing or disabled in
 * config falls back to the regular splash unit, so an entry costs nothing until its id is
 * actually configured. Only an app that diverges — different keys, its own segmentation —
 * overrides [ObSplashActivity.splashInterstitialOverride].
 */
enum class SplashEntry(val interKey: String) {
    NOTIFICATION("inter_noti"),
    WIDGET("inter_widget"),
    UNINSTALL("inter_uninstall");

    /**
     * The intent the tap fires — a notification trampoline, a widget PendingIntent, the uninstall
     * shortcut. [splashActivity] is the app's [ObSplashActivity] subclass; feature extras may be
     * added on top and ride through the flow as the passthrough.
     */
    fun intent(context: Context, splashActivity: Class<out ObSplashActivity>): Intent =
        Intent(context, splashActivity).apply {
            putExtra(EXTRA_ENTRY, name)
            // CLEAR_TASK as well as NEW_TASK: without it a task left over from an earlier
            // session is brought forward and the splash — with it the ad — never runs.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    companion object {
        /** Namespaced so it cannot collide with a feature extra the app puts on the same intent. */
        private const val EXTRA_ENTRY = "ob_splash_entry"

        /** The entry a launch came through, or `null` for a plain launcher tap. */
        fun from(intent: Intent?): SplashEntry? = from(intent?.extras)

        /**
         * The same answer read from the passthrough the terminal outcome hands back, which is how
         * an `OnboardingListener` routes an entry to its own destination screen.
         */
        fun from(extras: Bundle?): SplashEntry? =
            extras?.getString(EXTRA_ENTRY)?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
