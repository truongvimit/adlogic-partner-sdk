package io.onboardkit.ui.base

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.ObLog
import io.trackkit.Tracker
import java.util.Locale

/**
 * Base for every SDK screen: applies the chosen locale before inflate, system-bar policy,
 * a process-death guard (restart via launcher, then RETURN — never build UI on empty config),
 * and a single back-press hook.
 */
abstract class BaseOnboardActivity : AppCompatActivity() {

    protected val sdk: OnboardingSdk get() = OnboardingSdk

    /** True when the activity is being relaunched after process death with no config. */
    protected var restartedByGuard: Boolean = false
        private set

    override fun attachBaseContext(newBase: Context) {
        val code = OnboardingSdk.selectedLanguageOrNull()
        super.attachBaseContext(if (code == null) newBase else wrapLocale(newBase, code))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObLog.d(ObLog.Section.SCREEN, "create ${javaClass.simpleName} ready=${OnboardingSdk.isReady()}")
        if (!OnboardingSdk.isReady()) {
            ObLog.w(ObLog.Section.SCREEN, "${javaClass.simpleName} SDK not ready — restarting from launcher")
            restartedByGuard = true
            OnboardingSdk.restartFromLauncher(this)
            finish()
            return
        }
        applySystemBars()
        // Every SDK screen is off-limits to app-resume ads: they all either show a full-screen ad
        // of their own or are a step the user is mid-way through. Doing it here means a partner
        // cannot forget one — the audited app listed them by hand in Application.onCreate.
        OnboardingSdk.appResume().excludeScreen(javaClass)
        // Before onCreateSafe: a screen that navigates away from its own onCreate would otherwise
        // never be counted as viewed.
        screenName?.let { Tracker.screen(it, javaClass.simpleName) }
        onCreateSafe(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    this@BaseOnboardActivity.handleBack()
                }
            },
        )
    }

    /**
     * `screen_view` name for this screen, or null to opt out. Kept separate from the class name so
     * an app subclassing [io.onboardkit.ui.splash.ObSplashActivity] still reports as `ob_splash`
     * instead of under whatever it called its own class.
     */
    protected open val screenName: String? = null

    /** Called only when the SDK is ready — subclasses build their UI here. */
    protected abstract fun onCreateSafe(savedInstanceState: Bundle?)

    /** Default policy: leave the app. Screens that can go back override this. */
    protected open fun handleBack() {
        finishAffinity()
    }

    private fun applySystemBars() {
        val system = OnboardingSdk.configOrNull()?.system ?: return
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (!system.showStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        if (!system.showNavigationBar) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    protected fun hideViewCompletely(view: View) {
        view.visibility = View.GONE
    }

    private fun wrapLocale(base: Context, code: String): Context {
        val parts = code.split('-')
        val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }
}
