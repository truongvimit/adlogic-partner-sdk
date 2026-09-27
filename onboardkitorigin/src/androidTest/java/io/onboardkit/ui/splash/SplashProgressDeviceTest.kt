package io.onboardkit.ui.splash

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real splash layout/focus on a device; the pending interstitial never contacts an ad network. */
@RunWith(AndroidJUnit4::class)
class SplashProgressDeviceTest {
    @Test fun progressPausesBehindPopupAndResumesWithMatchingPercentage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        instrumentation.runOnMainSync {
            assertFalse("Run in a fresh instrumentation process", OnboardingSdk.isReady())
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            OnboardingSdk.install(app) {
                adProvider = object : FakeAdProvider() {}
                trackkitAutoTracking(false)
            }
            OnboardingSettings.document.acceptSuccessfulFetch(null)
            OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags(splashAdBudgetMs = 60_000))
            OnboardingSdk.configure(onboardKitConfig {
                splash = SplashConfig(
                    noInternetPromptEnabled = false,
                    notificationPermissionEnabled = false,
                )
                ads = AdsConfig(splashInterstitial = InterstitialAdUnit("progress-device-pending"))
            }.getOrThrow()).getOrThrow()
        }
        runBlocking { OnboardingSdk.reset() }
        try {
            ActivityScenario.launch(SplashProgressDeviceActivity::class.java).use { scenario ->
                fun progress(): Int {
                    var value = -1
                    scenario.onActivity { activity ->
                        val bar = activity.findViewById<ProgressBar>(R.id.ob_splash_progress)
                        assertFalse(bar.isIndeterminate)
                        assertEquals(100, bar.max)
                        value = bar.progress
                        assertEquals("$value%", activity.findViewById<TextView>(R.id.ob_splash_progress_percent).text.toString())
                        assertFalse(activity.isFinishing)
                    }
                    return value
                }
                fun focused(): Boolean {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    return focused
                }
                eventually("The splash test window must be visible and focused") { focused() }
                eventually("Visible splash starts progressing") { progress() >= 18 }
                lateinit var dialog: AlertDialog
                scenario.onActivity { activity ->
                    dialog = AlertDialog.Builder(activity)
                        .setMessage("Progress pause test")
                        .setPositiveButton("Continue", null)
                        .show()
                }
                eventually("The real popup takes splash focus") { !focused() }
                val paused = progress()
                SystemClock.sleep(2_200)
                assertEquals("Popup reading time must not advance splash progress", paused, progress())
                scenario.onActivity { dialog.dismiss() }
                eventually("The splash regains focus after the popup closes") { focused() }
                eventually("Progress resumes after the popup closes") { progress() > paused }
                eventually("Capture the first portion of the progress curve") { progress() >= 45 }
                capture(app, "splash-progress-half.png")
                eventually("The first ten visible seconds reach 90 percent", 12_000) { progress() >= 90 }
                capture(app, "splash-progress-90.png")
                assertTrue("The final ten percent must remain gradual", progress() <= 93)
            }
        } finally {
            instrumentation.runOnMainSync { ConsentCenter.clearHostConsent() }
        }
    }

    private fun capture(app: Application, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.filesDir, name).outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun eventually(message: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}

class SplashProgressDeviceActivity : ObSplashActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Only this test window wakes/shows over keyguard; it does not unlock the device or
        // change the user's display/security settings. Its flags disappear with the Activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
    }

    override suspend fun onConsentRequired(): Boolean {
        ConsentCenter.setHostConsent(true, false)
        return true
    }
}
