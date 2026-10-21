package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.ui.resume.ObResumeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Start and back on both native resume screens: inter_resume first, AFTER_AD; the screen closes on the next press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.resume"])
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeStartTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val provider get() = PoolUiSdk.provider
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before fun setUp() {
        PoolUiSdk.install(app, ApFakeLoader())
        val flow = poolSnapshot()
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { it.close() }
        PoolUiSdk.tearDown()
    }

    private fun withResumeInter() =
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeInterstitial = InterstitialAdUnit("resume-inter")))

    private fun <T : AppCompatActivity> open(type: Class<T>): T =
        Robolectric.buildActivity(type).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
            main.idle()
        }.get()

    private fun AppCompatActivity.tapStart() {
        findViewById<android.view.View>(R.id.ob_resume_start).performClick()
        main.idle()
    }

    private fun AppCompatActivity.pressBack() {
        onBackPressedDispatcher.onBackPressed()
        main.idle()
    }

    private fun screens() = listOf(ObResumeActivity::class.java, ObPoolResumeActivity::class.java)

    /** What the vendor reports for a shown ad: on screen, then closed. */
    private fun closeShownInter() {
        val shown = provider.presentations.removeAt(provider.presentations.lastIndex)
        shown.onNextAction()
        shown.onAdClosed()
        main.idle()
    }

    @Test fun `Start shows inter_resume AFTER_AD, stays up once it is gone and closes on the next Start`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        assertTrue("${type.simpleName} warms the fill on open", AdPlacement.ResumeInterstitial in provider.interstitialLoads)

        screen.tapStart()
        screen.tapStart()
        assertEquals("${type.simpleName}: one presentation per Start", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertFalse("${type.simpleName} stays under its ad", screen.isFinishing)

        closeShownInter()
        assertFalse("${type.simpleName}: back on the screen once the ad is gone", screen.isFinishing)
        assertEquals(0, PoolUiSdk.suppressDepth())

        screen.tapStart()
        assertEquals("${type.simpleName}: no second interstitial", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertTrue("${type.simpleName}: the next Start closes it", screen.isFinishing)
    }

    @Test fun `back shows inter_resume like Start, stays up once it is gone and closes on the next back`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.pressBack()
        screen.pressBack()
        assertEquals("${type.simpleName}: one presentation per back", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertFalse("${type.simpleName} stays under its ad", screen.isFinishing)

        closeShownInter()
        assertFalse("${type.simpleName}: back on the screen once the ad is gone", screen.isFinishing)

        screen.pressBack()
        assertEquals(listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertTrue("${type.simpleName}: the next back closes it", screen.isFinishing)
    }

    @Test fun `an inter_resume that does not show closes the screen at once`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.tapStart()
        provider.presentations.removeAt(provider.presentations.lastIndex).onAdSkipped(AdSkipReason.NOT_READY)
        main.idle()
        assertTrue("${type.simpleName} closes at once", screen.isFinishing)
    }

    @Test fun `without inter_resume back closes the screen at once`() = screens().forEach { type ->
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.pressBack()
        assertTrue(provider.loadAndShows.isEmpty())
        assertTrue("${type.simpleName} closes at once", screen.isFinishing)
    }

    @Test fun `without inter_resume Start closes the screen at once`() = screens().forEach { type ->
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.tapStart()
        assertTrue(provider.loadAndShows.isEmpty())
        assertTrue("${type.simpleName} closes at once", screen.isFinishing)
    }
}
