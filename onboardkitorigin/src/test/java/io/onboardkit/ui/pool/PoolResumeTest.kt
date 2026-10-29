package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.NativePoolEngine
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.ObNativeResume
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.SYNC_PARITY
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.StepId
import io.onboardkit.ui.resume.ObResumeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

/** Native resume while a SHARED_POOL run routes it: leftovers, the owner's ad and D-REFRESH case B. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.resume"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolResumeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private val controllers = mutableListOf<ActivityController<*>>()
    private val ob1 = AdPlacement.StepNative(StepId.OB1)

    @Before fun setUp() {
        loader = ApFakeLoader()
        engine = PoolUiSdk.install(app, loader)
        val flow = poolSnapshot()
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { it.close() }
        PoolUiSdk.tearDown()
    }

    private fun fill(key: String): ApNativeAd = loader.fill(key).also { main.idle() }

    private fun openResume(intent: Intent? = null): ActivityController<ObPoolResumeActivity> =
        Robolectric.buildActivity(ObPoolResumeActivity::class.java, intent).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
            main.idle()
        }

    private fun hub(): ActivityController<ObPoolFlowActivity> = launchPoolFlow(app).also { controllers += it }

    /** What `ResumeAds` does at the process's ON_START: the host is back in front, then the screen opens. */
    private fun returnOver(host: ActivityController<out Activity>): ActivityController<ObPoolResumeActivity> {
        host.pause().stop().restart().start().resume()
        main.idle()
        ObNativeResume.show(host.get())
        val started = shadowOf(host.get()).nextStartedActivity
        assertEquals(ObPoolResumeActivity::class.java.name, started.component?.className)
        return openResume(started)
    }

    private fun ActivityController<ObPoolResumeActivity>.slot(): FrameLayout = get().findViewById(R.id.ob_resume_ad)

    private fun ActivityController<ObPoolResumeActivity>.block(): View = get().findViewById(R.id.ob_resume_ad_block)

    private fun ActivityController<ObPoolResumeActivity>.backAndForth() {
        pause().stop().restart().start().resume()
        main.idle()
    }

    @Test fun `the resume screen never owns and shows its own fill ahead of the owner's ad`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val owner = fill("language1")
        val screen = openResume()
        assertEquals("language1", engine.owner)
        assertSame("It borrows the owner's ad until its own fills", owner.admobNativeAd, screen.slot().shownAd())
        assertEquals(View.VISIBLE, screen.block().visibility)
        assertEquals(1, loader.callsFor("native_resume").size)

        val own = fill("native_resume")
        assertSame("Its own fill takes over at once", own.admobNativeAd, screen.slot().shownAd())
        screen.backAndForth()
        assertSame(own.admobNativeAd, screen.slot().shownAd())
        assertEquals("The owner is untouched", "language1", engine.owner)
        assertEquals(0, PoolUiSdk.suppressDepth())
    }

    @Test fun `the resume screen binds a fill while it is only started`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val screen = openResume()
        screen.pause()
        main.idle()
        val ad = fill("language1")
        assertSame(ad.admobNativeAd, screen.slot().shownAd())
    }

    @Test fun `native_resume goes out FORCED past another load, never twice while its own runs`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val screen = openResume()
        assertEquals("FORCED past ob1's load", 1, loader.callsFor("native_resume").size)

        screen.backAndForth()
        assertEquals("Its own load still runs: no second request", 1, loader.callsFor("native_resume").size)

        fill("step_ob1")
        val own = fill("native_resume")
        assertSame(own.admobNativeAd, screen.slot().shownAd())
        screen.backAndForth()
        assertEquals("The held fill is refreshed", 2, loader.callsFor("native_resume").size)
        assertTrue(requireNotNull(engine.debugSnapshot().loader("native_resume")).refreshing)
        assertSame("The held ad stays drawn meanwhile", own.admobNativeAd, screen.slot().shownAd())
        assertEquals("step_ob1", engine.owner)
    }

    @Test fun `a native_resume that filled after its screen closed shows first on the next one`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val first = openResume()
        first.close()
        controllers -= first
        val owner = fill("step_ob1")
        val behind = fill("native_resume")

        val next = openResume()
        assertSame(behind.admobNativeAd, next.slot().shownAd())
        assertTrue("Still in the pool for the next screen", owner.admobNativeAd !== next.slot().shownAd())
    }

    @Test fun `after an ad click the screen neither loads nor shows inter_resume`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeInterstitial = InterstitialAdUnit("resume-inter")))
        val host = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()
        ObNativeResume.show(host, afterAdClick = true)
        val screen = openResume(shadowOf(host).nextStartedActivity)
        assertTrue(PoolUiSdk.provider.interstitialLoads.isEmpty())
        assertEquals("Its native still goes out", 1, loader.callsFor("native_resume").size)

        screen.get().findViewById<View>(R.id.ob_resume_start).performClick()
        main.idle()
        assertTrue(PoolUiSdk.provider.presentations.isEmpty())
        assertTrue("Start only closes the screen", screen.get().isFinishing)
    }

    @Test fun `without an ad click the screen preloads inter_resume and Start shows it`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeInterstitial = InterstitialAdUnit("resume-inter")))
        val host = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()
        ObNativeResume.show(host, afterAdClick = false)
        val screen = openResume(shadowOf(host).nextStartedActivity)
        assertEquals(listOf<AdPlacement>(AdPlacement.ResumeInterstitial), PoolUiSdk.provider.interstitialLoads)

        screen.get().findViewById<View>(R.id.ob_resume_start).performClick()
        main.idle()
        assertEquals(1, PoolUiSdk.provider.presentations.size)
        assertFalse(screen.get().isFinishing)
        PoolUiSdk.provider.endPresentations()
    }

    @Test fun `after the flow, back from inter_resume draws the native_resume that filled behind it, then closes`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeInterstitial = InterstitialAdUnit("resume-inter")))
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val leftover = fill("step_ob1")
        OnboardingPool.setOwner(null)
        val screen = openResume()
        assertSame(leftover.admobNativeAd, screen.slot().shownAd())
        assertEquals("Its own request", 1, loader.callsFor("native_resume").size)

        screen.get().findViewById<View>(R.id.ob_resume_start).performClick()
        main.idle()
        val inter = PoolUiSdk.provider.presentations.last()
        inter.onNextAction()
        screen.pause().stop()
        main.idle()
        val behind = fill("native_resume")
        inter.onAdClosed()
        main.idle()
        assertFalse("Not closed while the ad still covers it", screen.get().isFinishing)

        val before = loader.attempts.size
        screen.restart().start().resume()
        main.idle()
        assertTrue("No request on the way back to close", loader.attempts.drop(before).isEmpty())
        assertSame(behind.admobNativeAd, screen.slot().shownAd())
        main.idleFor(400, MILLISECONDS)
        assertTrue(screen.get().isFinishing)
    }

    @Test fun `the host refreshes first and the screen's own refresh still goes out past it`() {
        val host = hub()
        fill("language1")
        ObNativeResume.show(host.get())
        val first = openResume(shadowOf(host.get()).nextStartedActivity)
        fill("native_resume")
        first.close()

        val before = loader.attempts.size
        returnOver(host)
        assertEquals(listOf("language1", "native_resume"), loader.attempts.drop(before).map { it.request.key })
    }

    @Test fun `with no other request in flight the screen refreshes its held native_resume`() {
        val host = hub()
        fill("language1")
        ObNativeResume.show(host.get())
        val screen = openResume(shadowOf(host.get()).nextStartedActivity)
        fill("native_resume")

        val before = loader.attempts.size
        screen.pause().resume()
        main.idle()
        assertEquals(listOf("native_resume"), loader.attempts.drop(before).map { it.request.key })
        assertTrue(requireNotNull(engine.debugSnapshot().loader("native_resume")).refreshing)
    }

    @Test fun `under the host's owner refresh the screen loads its own and borrows the owner's ad until it fills`() {
        val host = hub()
        val old = fill("language1")
        val screen = returnOver(host)
        assertTrue(requireNotNull(engine.debugSnapshot().loader("language1")).refreshing)
        assertEquals("FORCED past the host's refresh", 1, loader.callsFor("native_resume").size)
        assertEquals("language1", engine.owner)
        assertSame("The old owner ad shows meanwhile", old.admobNativeAd, screen.slot().shownAd())

        val refreshed = fill("language1")
        assertSame(refreshed.admobNativeAd, screen.slot().shownAd())
        val own = fill("native_resume")
        assertSame(own.admobNativeAd, screen.slot().shownAd())
        assertEquals("language1", engine.owner)
    }

    @Test fun `a failed host refresh keeps the old ad on the resume screen`() {
        val host = hub()
        val old = fill("language1")
        val screen = returnOver(host)
        val view = screen.slot().getChildAt(0)

        loader.exhaust("language1")
        main.idle()
        assertSame(old.admobNativeAd, screen.slot().shownAd())
        assertSame("Nothing was rebound", view, screen.slot().getChildAt(0))
    }

    @Test fun `after the flow the resume screen shows the newest leftover`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val leftover = fill("step_ob1")
        OnboardingPool.setOwner(null)
        val screen = openResume()
        assertNull(engine.owner)
        assertSame(leftover.admobNativeAd, screen.slot().shownAd())
        assertEquals(View.VISIBLE, screen.block().visibility)
    }

    @Test fun `back works at once over a leftover that already made its impression`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val leftover = fill("step_ob1")
        loader.impress(leftover)
        main.idle()
        OnboardingPool.setOwner(null)
        val screen = openResume()
        assertSame(leftover.admobNativeAd, screen.slot().shownAd())
        assertEquals("native_resume itself is still loading", 1, loader.callsFor("native_resume").size)

        screen.get().onBackPressedDispatcher.onBackPressed()
        main.idle()
        assertTrue("No inter_resume configured: back closes at once", screen.get().isFinishing)
    }

    @Test fun `with nothing to show the block collapses`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeNative = null))
        val screen = openResume()
        assertEquals(View.GONE, screen.block().visibility)
        assertTrue(loader.attempts.isEmpty())
    }

    @Test fun `a refused native_resume collapses its block instead of lending the owner's ad`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(resumeNative = null))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        fill("language1")
        val screen = openResume()
        assertTrue(loader.callsFor("native_resume").isEmpty())
        assertEquals("DashDrama's p(k()) hides a refused native_resume", View.GONE, screen.block().visibility)
        assertNull(screen.slot().shownAd())
        assertEquals("It still never owns", "language1", engine.owner)
    }

    @Test fun `a resume screen restored with the pool inactive finishes at once`() {
        OnboardingPool.resetForTest()
        val screen = openResume()
        assertTrue(screen.get().isFinishing)
        assertTrue(loader.attempts.isEmpty())
    }

    @Test fun `back from its ad's landing page restores the native resume layout`() {
        val screen = openResume()
        val own = fill("native_resume")
        assertSame(own.admobNativeAd, screen.slot().shownAd())
        val view = screen.slot().getChildAt(0)
        val requests = loader.attempts.size

        loader.click(own)
        main.idle()
        screen.backAndForth()
        assertEquals(View.VISIBLE, screen.block().visibility)
        assertEquals("The click return does not start a replacement", requests, loader.attempts.size)
        assertTrue("The held native is drawn again", screen.slot().getChildAt(0) !== view)
        assertSame(own.admobNativeAd, screen.slot().shownAd())

        // The same held native is restored after a recreation while the landing page owns the
        // window. The click-return path above already proves that no replacement was requested.
        loader.click(own)
        main.idle()
        val state = Bundle()
        screen.pause().saveInstanceState(state).stop().destroy()
        controllers -= screen
        main.idle()
        val restored = Robolectric.buildActivity(ObPoolResumeActivity::class.java).also {
            controllers += it
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup(state)
            main.idle()
        }
        assertEquals("A screen destroyed under the landing page restores its ad", View.VISIBLE,
            restored.block().visibility)
        assertSame(own.admobNativeAd, restored.slot().shownAd())
        assertEquals(requests, loader.attempts.size)
    }

    @Test fun `native resume opens the pooled screen only while the run routes it`() {
        val host = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()
        ObNativeResume.show(host)
        assertEquals(ObPoolResumeActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)

        val screen = openResume()
        ObNativeResume.show(screen.get())
        assertNull("A return to the screen itself opens nothing", shadowOf(screen.get()).nextStartedActivity)

        OnboardingPool.onClassicRun()
        main.idle()
        ObNativeResume.show(host)
        assertEquals(ObResumeActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        ObNativeResume.show(screen.get())
        assertNull("The classic screen never opens over the pooled one", shadowOf(screen.get()).nextStartedActivity)
    }

    @Test fun `prepare stays out of the provider and the pool while routed`() {
        ObNativeResume.prepare(app)
        main.idle()
        assertTrue(loader.callsFor("native_resume").isEmpty())
        assertTrue(PoolUiSdk.provider.backgroundPreloads.isEmpty())

        OnboardingPool.onClassicRun()
        main.idle()
        ObNativeResume.prepare(app)
        assertEquals(listOf<AdPlacement>(AdPlacement.ResumeNative), PoolUiSdk.provider.backgroundPreloads)
    }
}
