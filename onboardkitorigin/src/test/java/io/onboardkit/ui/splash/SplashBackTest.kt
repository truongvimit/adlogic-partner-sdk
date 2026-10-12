package io.onboardkit.ui.splash

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.ui.base.BaseOnboardActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() is process-scoped and other splash suites install their own provider.
@Config(sdk = [34], application = Application::class, instrumentedPackages = ["io.onboardkit.ui.splash"])
@LooperMode(LooperMode.Mode.PAUSED)
class SplashBackTest {
    private val main get() = shadowOf(Looper.getMainLooper())
    private var controller: ActivityController<out BaseOnboardActivity>? = null

    @Before fun setup() {
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) { trackkitAutoTracking(false) }
        OnboardingSdk.configure(onboardKitConfig {
            step(ContentStepDefinition(StepId.OB1, title = "Introduction"))
        }.getOrThrow()).getOrThrow()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        main.idle()
    }

    private fun <T : BaseOnboardActivity> launch(type: Class<T>): T {
        val launched = Robolectric.buildActivity(type)
        launched.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        launched.setup().visible()
        controller = launched
        main.idle()
        return launched.get()
    }

    @Test fun `back never leaves the splash`() {
        val splash = launch(BackProbeSplash::class.java)
        repeat(2) { splash.onBackPressedDispatcher.onBackPressed() }
        main.idle()
        assertFalse(splash.isFinishing)
        assertNull(shadowOf(splash).nextStartedActivity)
    }

    @Test fun `a committed back gesture never leaves the splash`() {
        val splash = launch(BackProbeSplash::class.java)
        val dispatcher = splash.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1000f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 1000f, 0.8f, BackEventCompat.EDGE_LEFT))
        dispatcher.onBackPressed()
        main.idle()
        assertFalse(splash.isFinishing)
        assertNull(shadowOf(splash).nextStartedActivity)
    }

    @Test fun `a screen without its own back policy still leaves on back`() {
        val screen = launch(BackProbeScreen::class.java)
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(screen.isFinishing)
    }
}

/** Splash with its loading workflow skipped; only the inherited back policy is exercised. */
class BackProbeSplash : ObSplashActivity() {
    override fun onCreateSafe(savedInstanceState: Bundle?) = Unit
}

class BackProbeScreen : BaseOnboardActivity() {
    override fun onCreateSafe(savedInstanceState: Bundle?) = Unit
}
