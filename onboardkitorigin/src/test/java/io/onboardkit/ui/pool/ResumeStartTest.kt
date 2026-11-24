package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.pool.FakeAdLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.remote.OnboardingSettings
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
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Start and back on both native resume screens: inter_resume first, AFTER_AD; the screen closes once back in front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.resume"])
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeStartTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val provider get() = PoolUiSdk.provider
    private val controllers = mutableListOf<ActivityController<*>>()

    private lateinit var loader: FakeAdLoader

    @Before fun setUp() {
        loader = FakeAdLoader()
        PoolUiSdk.install(app, loader)
        val flow = poolSnapshot()
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { it.close() }
        PoolUiSdk.tearDown()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun skipAfterNativeClick(skip: Boolean?) {
        OnboardingSettings.document.acceptSuccessfulFetch(
            skip?.let { """{"native_resume":{"interstitial":{"skip_after_native_click":$it}}}""" },
        )
    }

    /** The screen as a return from a native ad's landing page opens it. */
    private fun <T : AppCompatActivity> openAfterNativeClick(type: Class<T>): ActivityController<T> {
        val host = Robolectric.buildActivity(android.app.Activity::class.java).also { controllers += it }.setup().get()
        if (type == ObResumeActivity::class.java) ObResumeActivity.start(host, afterAdClick = true)
        else ObPoolResumeActivity.start(host, afterAdClick = true)
        return Robolectric.buildActivity(type, shadowOf(host).nextStartedActivity).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
            main.idle()
        }
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

    /** The screen's native fills and makes its impression. */
    private fun nativeShown(type: Class<*>) {
        if (type == ObResumeActivity::class.java) {
            val listener = requireNotNull(provider.nativeListeners[AdPlacement.ResumeNative])
            listener.onLoaded()
            listener.onImpression()
        } else {
            val ad = loader.fill("native_resume")
            main.idle()
            loader.impress(ad)
        }
        main.idle()
    }

    /** The screen's native will not show. */
    private fun nativeUnavailable(type: Class<*>) {
        if (type == ObResumeActivity::class.java) {
            requireNotNull(provider.nativeListeners[AdPlacement.ResumeNative]).onFailedToLoad()
        } else {
            loader.exhaust("native_resume")
        }
        main.idle()
    }

    private fun screens() = listOf(ObResumeActivity::class.java, ObPoolResumeActivity::class.java)

    /**
     * What a shown ad does to the screen: it covers it, then reports its close while still on top,
     * and only after that is the screen back in front.
     */
    private fun showAndCloseInter(screen: ActivityController<*>) {
        val shown = provider.presentations.removeAt(provider.presentations.lastIndex)
        shown.onNextAction()
        screen.pause().stop()
        main.idle()
        shown.onAdClosed()
        main.idle()
    }

    private fun backInFront(screen: ActivityController<*>) {
        screen.restart().start().resume()
        main.idle()
        main.idleFor(400, MILLISECONDS)
    }

    @Test fun `Start shows inter_resume and closes the screen AFTER_AD, once it is back in front`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        val controller = controllers.last()
        assertTrue("${type.simpleName} warms the fill on open", AdPlacement.ResumeInterstitial in provider.interstitialLoads)

        screen.tapStart()
        screen.tapStart()
        assertEquals("${type.simpleName}: one presentation per Start", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertFalse("${type.simpleName} stays under its ad", screen.isFinishing)

        showAndCloseInter(controller)
        assertFalse("${type.simpleName}: not closed while the ad still covers it", screen.isFinishing)
        assertEquals(0, PoolUiSdk.suppressDepth())

        backInFront(controller)
        assertTrue("${type.simpleName}: closed once back in front", screen.isFinishing)
        assertEquals(listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
    }

    @Test fun `back shows inter_resume like Start and closes the screen AFTER_AD`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        val controller = controllers.last()
        nativeShown(type)
        screen.pressBack()
        screen.pressBack()
        assertEquals("${type.simpleName}: one presentation per back", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        assertFalse("${type.simpleName} stays under its ad", screen.isFinishing)

        showAndCloseInter(controller)
        assertFalse(screen.isFinishing)
        backInFront(controller)
        assertTrue("${type.simpleName}: the ad's close ends the screen", screen.isFinishing)
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

    @Test fun `without inter_resume back closes the screen at once once its native is shown`() = screens().forEach { type ->
        provider.loadAndShows.clear()
        val screen = open(type)
        nativeShown(type)
        screen.pressBack()
        assertTrue(provider.loadAndShows.isEmpty())
        assertTrue("${type.simpleName} closes at once", screen.isFinishing)
    }

    @Test fun `back does nothing while the native loads, Start still runs`() = screens().forEach { type ->
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.pressBack()
        assertTrue("${type.simpleName}: back held while native_resume loads", provider.loadAndShows.isEmpty())
        assertFalse(screen.isFinishing)

        screen.tapStart()
        assertEquals("${type.simpleName}: Start is not held", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        provider.endPresentations()
        main.idle()
    }

    @Test fun `after an ad click the classic screen neither loads nor shows inter_resume`() {
        withResumeInter()
        provider.loadAndShows.clear()
        provider.interstitialLoads.clear()
        val host = Robolectric.buildActivity(android.app.Activity::class.java).also { controllers += it }.setup().get()
        ObResumeActivity.start(host, afterAdClick = true)
        val screen = Robolectric.buildActivity(ObResumeActivity::class.java, shadowOf(host).nextStartedActivity).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
            main.idle()
        }.get()
        assertFalse("No inter_resume warm-up", AdPlacement.ResumeInterstitial in provider.interstitialLoads)

        screen.tapStart()
        assertTrue(provider.loadAndShows.isEmpty())
        assertTrue("Start only closes it", screen.isFinishing)
    }

    @Test fun `after a native click Start and back skip inter_resume by default and when remote says so`() =
        listOf(null, true).forEach { remote ->
            skipAfterNativeClick(remote)
            screens().forEach { type ->
                val label = "${type.simpleName} remote=$remote"
                withResumeInter()
                provider.interstitialLoads.clear()
                provider.loadAndShows.clear()
                val started = openAfterNativeClick(type).get()
                assertFalse("$label: no inter_resume warm-up", AdPlacement.ResumeInterstitial in provider.interstitialLoads)
                started.tapStart()
                assertTrue(label, provider.loadAndShows.isEmpty())
                assertTrue("$label: Start only closes it", started.isFinishing)

                val backed = openAfterNativeClick(type).get()
                nativeShown(type)
                backed.pressBack()
                assertTrue(label, provider.loadAndShows.isEmpty())
                assertTrue(label, provider.presentations.isEmpty())
                assertTrue("$label: back only closes it", backed.isFinishing)
            }
        }

    @Test fun `with skip_after_native_click false a native click return shows inter_resume like any return`() {
        skipAfterNativeClick(false)
        screens().forEach { type ->
            withResumeInter()
            provider.interstitialLoads.clear()
            provider.loadAndShows.clear()
            val controller = openAfterNativeClick(type)
            val screen = controller.get()
            assertTrue("${type.simpleName} warms the fill on open", AdPlacement.ResumeInterstitial in provider.interstitialLoads)

            screen.tapStart()
            screen.tapStart()
            assertEquals("${type.simpleName}: one presentation per Start", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
            assertFalse("${type.simpleName} stays under its ad", screen.isFinishing)
            showAndCloseInter(controller)
            assertFalse(screen.isFinishing)
            assertEquals(0, PoolUiSdk.suppressDepth())
            backInFront(controller)
            assertTrue("${type.simpleName}: closed AFTER_AD", screen.isFinishing)

            provider.loadAndShows.clear()
            val backController = openAfterNativeClick(type)
            nativeShown(type)
            backController.get().pressBack()
            assertEquals("${type.simpleName}: back shows it too", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
            showAndCloseInter(backController)
            backInFront(backController)
            assertTrue(backController.get().isFinishing)
        }
    }

    @Test fun `the remote in force at Start decides, whatever it was when the screen opened`() = screens().forEach { type ->
        withResumeInter()
        skipAfterNativeClick(true)
        provider.interstitialLoads.clear()
        provider.loadAndShows.clear()
        val turnedOff = openAfterNativeClick(type)
        assertTrue(provider.interstitialLoads.isEmpty())
        skipAfterNativeClick(false)
        turnedOff.get().tapStart()
        assertEquals("${type.simpleName}: shown without a warm-up", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        provider.endPresentations()
        main.idle()

        skipAfterNativeClick(false)
        provider.loadAndShows.clear()
        val turnedOn = openAfterNativeClick(type)
        skipAfterNativeClick(true)
        turnedOn.get().tapStart()
        assertTrue("${type.simpleName}: the warmed fill is left unused", provider.loadAndShows.isEmpty())
        assertTrue(turnedOn.get().isFinishing)
    }

    @Test fun `without a native click the setting changes nothing`() = listOf(false, true).forEach { skip ->
        skipAfterNativeClick(skip)
        screens().forEach { type ->
            withResumeInter()
            provider.interstitialLoads.clear()
            provider.loadAndShows.clear()
            val controller = Robolectric.buildActivity(type).also {
                controllers += it
                it.get().setTheme(R.style.ob_Theme_OnboardKit)
                it.setup()
                main.idle()
            }
            assertTrue(AdPlacement.ResumeInterstitial in provider.interstitialLoads)
            controller.get().tapStart()
            assertEquals("${type.simpleName} skip=$skip", listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
            showAndCloseInter(controller)
            backInFront(controller)
            assertTrue(controller.get().isFinishing)
        }
    }

    @Test fun `a replacement bound over a seen ad holds back again until its own impression`() {
        withResumeInter()
        provider.loadAndShows.clear()
        val screen = open(ObResumeActivity::class.java)
        val listener = requireNotNull(provider.nativeListeners[AdPlacement.ResumeNative])
        listener.onLoaded()
        listener.onImpression()
        listener.onLoaded()
        main.idle()
        screen.pressBack()
        assertTrue("The new ad is not seen yet", provider.loadAndShows.isEmpty())

        listener.onImpression()
        main.idle()
        screen.pressBack()
        assertEquals(listOf(AdPlacement.ResumeInterstitial), provider.loadAndShows)
        provider.endPresentations()
        main.idle()
    }

    @Test fun `back works once the native will not show`() = screens().forEach { type ->
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.pressBack()
        assertFalse(screen.isFinishing)
        nativeUnavailable(type)
        screen.pressBack()
        assertTrue("${type.simpleName} closes on back after a no-fill", screen.isFinishing)
    }

    @Test fun `without inter_resume Start closes the screen at once`() = screens().forEach { type ->
        provider.loadAndShows.clear()
        val screen = open(type)
        screen.tapStart()
        assertTrue(provider.loadAndShows.isEmpty())
        assertTrue("${type.simpleName} closes at once", screen.isFinishing)
    }
}
