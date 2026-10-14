package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import io.onboardkit.R
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.PoolGlueViewShadow
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit.MILLISECONDS

/** App-resume over the pooled LFO and the pooled resume screen, read live as `ObAppResume` does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.base"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolAppResumeEligibilityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val manager get() = AppOpenManager.getInstance()
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before fun setUp() {
        PoolUiSdk.install(app, ApFakeLoader())
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { it.close() }
        PoolUiSdk.tearDown()
    }

    private val blocked = AdSkipReason.SUPPRESSED_BY_FLOW.key

    @Test fun `the LFO stage blocks app-resume only while its dialog shows or its exit runs`() {
        val host = launchPoolFlow(app).also { controllers += it }.get()
        assertFalse(manager.isResumeSuppressedFor(host))
        assertNull(manager.resumeSkipReasonFor(host))

        host.tapLanguage(0)
        assertNull("A pick alone keeps the screen eligible", manager.resumeSkipReasonFor(host))
        host.tapLanguage(0)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertEquals(blocked, manager.resumeSkipReasonFor(host))
        dialog.findViewById<View>(R.id.ob_confirm_close).performClick()
        main.idle()
        assertNull(manager.resumeSkipReasonFor(host))
        assertEquals(0, PoolUiSdk.suppressDepth())

        main.idleFor(3_000, MILLISECONDS)
        host.findViewById<View>(R.id.ob_language_confirm).performClick()
        main.idle()
        val presentation = PoolUiSdk.provider.presentations.single()
        assertEquals(blocked, manager.resumeSkipReasonFor(host))
        presentation.onAdClosed()
        assertEquals("The interstitial released its hold", 0, PoolUiSdk.suppressDepth())
        assertTrue("The latched exit still blocks until the next stage takes over", host.resumeBlockedByScreen)
    }

    @Test fun `the pooled resume screen is eligible and never suppresses`() {
        launchPoolFlow(app).also { controllers += it }
        val screen = Robolectric.buildActivity(ObPoolResumeActivity::class.java).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
        }
        main.idle()
        assertFalse(screen.get().isFinishing)
        assertFalse(manager.isResumeSuppressedFor(screen.get()))
        assertNull(manager.resumeSkipReasonFor(screen.get()))
        assertEquals(0, PoolUiSdk.suppressDepth())

        screen.pause().stop().restart().start().resume()
        main.idle()
        screen.get().findViewById<View>(R.id.ob_resume_start).performClick()
        screen.close()
        assertEquals(0, PoolUiSdk.suppressDepth())
    }
}
