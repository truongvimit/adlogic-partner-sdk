package io.onboardkit.ui.base

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.SystemBarConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SDK UI. Run each capture in a fresh instrumentation process; no ad provider is installed. */
@RunWith(AndroidJUnit4::class)
class ContentInsetsScreenshotDeviceTest {
    @Test
    fun captureLanguageScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val simulate = args.getString("simulateMissingOverlay") == "true"
        val name = args.getString("captureName") ?: "language"
        require(name.matches(Regex("[a-z0-9-]+")))
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (simulate) check(Build.VERSION.SDK_INT == 33) { "Simulation requires API 33 emulator" }
        instrumentation.runOnMainSync {
            OnboardingSdk.install(app) { trackkitAutoTracking(false) }
            OnboardingSdk.configure(onboardKitConfig {
                language = LanguageConfig(defaultCode = "en-US", tapHintEnabled = false)
                system = SystemBarConfig(showStatusBar = true, showNavigationBar = true)
            }.getOrThrow()).getOrThrow()
        }
        ActivityScenario.launch<ObLanguageActivity>(
            ObLanguageActivity.intentFor(app, LanguageScreenMode.FIRST_OPEN),
        ).use { scenario ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1_500)
            if (simulate) {
                scenario.onActivity { activity ->
                    val content = activity.findViewById<View>(android.R.id.content)
                    content.post {
                        // Only this synchronous dispatch reports API 34 to AndroidX. Restore the
                        // real API even if the pre-fix listener throws. No ROM/property mutation.
                        val sdkField = Build.VERSION::class.java.getField("SDK_INT")
                        sdkField.isAccessible = true
                        val actualSdk = Build.VERSION.SDK_INT
                        try {
                            sdkField.setInt(null, 34)
                            content.dispatchApplyWindowInsets(checkNotNull(content.rootWindowInsets))
                        } finally {
                            sdkField.setInt(null, actualSdk)
                        }
                    }
                }
                SystemClock.sleep(1_500)
                instrumentation.waitForIdleSync()
            }
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                assertTrue(content.width > 0 && content.height > 0)
                File(app.filesDir, "insets-$name.txt").writeText(
                    "model=${Build.MODEL} api=${Build.VERSION.SDK_INT} simulate=$simulate\n" +
                        "content=${content.width}x${content.height}\n" +
                        "padding=${content.paddingLeft},${content.paddingTop},${content.paddingRight},${content.paddingBottom}\n",
                )
            }
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.filesDir, "insets-$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
    }
}
