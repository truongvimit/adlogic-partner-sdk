package io.onboardkit.ui.base

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
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

    /** Splash and dedicated ad screens stay excluded; content screens opt into genuine returns. */
    protected open val excludeFromAppResume: Boolean = true

    /** Queried again at return/show time; never registers a transient page as a permanent exclusion. */
    internal open val resumeBlockedByScreen: Boolean
        get() = excludeFromAppResume || restartedByGuard

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
        lockPortraitIfConfigured()
        configureEdgeToEdge()
        applySystemBars()
        if (excludeFromAppResume) OnboardingSdk.appResume().excludeScreen(javaClass)
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
     * Reaches the one screen the SDK's manifest cannot: the app's own splash, which subclasses
     * [io.onboardkit.ui.splash.ObSplashActivity] and is therefore declared by the app.
     *
     * Read through `configOrNull` rather than `requireConfig`: the readiness guard above has
     * already returned for a null config, but a screen reached during a restart should not crash
     * over an orientation.
     *
     * `runCatching` because Android 12 throws when an Activity in a translucent theme asks for a
     * fixed orientation — a splash theme is a common place for one, and losing the lock there is
     * better than losing the launch.
     */
    private fun lockPortraitIfConfigured() {
        if (OnboardingSdk.configOrNull()?.behavior?.lockPortrait != true) return
        runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            .onFailure { ObLog.w(ObLog.Section.SCREEN, "portrait lock refused: ${it.message}") }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Consent forms, full-screen ads and dialogs run in windows with their own bar state;
        // the hide request must be re-asserted every time this window takes focus back.
        if (hasFocus) applySystemBars()
    }

    /**
     * The window is edge-to-edge on every API level; [applySystemBars] then only toggles bar
     * visibility, and the content root pads itself by the insets of whichever bars stay visible
     * (hidden bars report zero). Android 15+ enforces this window state anyway.
     */
    private fun configureEdgeToEdge() {
        if (OnboardingSdk.configOrNull() == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val content = findViewById<View>(android.R.id.content)
        val insetsReader = ContentInsetsReader()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insetsReader.getInsets(insets)
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

    private fun applySystemBars() {
        val system = OnboardingSdk.configOrNull()?.system ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // Transient-by-swipe (sticky immersive below API 30) is the one behavior under which a
        // swiped-in bar overlays the content and hides again by itself.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!system.showStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
        if (!system.showNavigationBar) controller.hide(WindowInsetsCompat.Type.navigationBars())
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
