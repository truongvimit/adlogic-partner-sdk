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
import io.onboardkit.core.StepId
import io.onboardkit.ui.resume.ObResumeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `the resume screen never owns and shows the underlying owner's ad`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val owner = fill("language1")
        val screen = openResume()
        assertEquals("language1", engine.owner)
        assertSame(owner.admobNativeAd, screen.slot().shownAd())
        assertEquals(View.VISIBLE, screen.block().visibility)
        assertEquals("Its own POLITE request goes out with the gate open", 1, loader.callsFor("native_resume").size)

        fill("native_resume")
        screen.backAndForth()
        assertEquals("language1", engine.owner)
        assertSame("The owner's ad beats the screen's own fill", owner.admobNativeAd, screen.slot().shownAd())
        assertEquals(0, PoolUiSdk.suppressDepth())
    }

    @Test fun `D-BIND - the resume screen binds a fill only while resumed`() {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val screen = openResume()
        screen.pause()
        main.idle()
        val ad = fill("language1")
        assertNull("Nothing bound while only started", screen.slot().shownAd())
        screen.resume()
        main.idle()
        assertSame(ad.admobNativeAd, screen.slot().shownAd())
    }

    @Test fun `screen_slots_bind_started binds the resume screen's fill while it is only started`() {
        OnboardingSdk.session.nativeFlow = poolSnapshot(SYNC_PARITY.copy(screenSlotsBindStarted = true))
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        val screen = openResume()
        screen.pause()
        main.idle()
        val ad = fill("language1")
        assertSame(ad.admobNativeAd, screen.slot().shownAd())
    }

    @Test fun `native_resume is asked POLITE on create and on resume, refreshed on resume`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        val screen = openResume()
        assertTrue("Gated on create and on its first resume", loader.callsFor("native_resume").isEmpty())

        fill("step_ob1")
        screen.backAndForth()
        assertEquals("A later resume asks again", 1, loader.callsFor("native_resume").size)

        fill("native_resume")
        screen.backAndForth()
        assertEquals("The held fill is refreshed, the POLITE load is a no-op", 2, loader.callsFor("native_resume").size)
        assertEquals("step_ob1", engine.owner)
    }

    @Test fun `the host's refresh runs before the resume screen's own`() {
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

    @Test fun `while the host's owner refreshes the screen's POLITE load is skipped and the slot follows the owner`() {
        val host = hub()
        val old = fill("language1")
        val screen = returnOver(host)
        assertTrue(requireNotNull(engine.debugSnapshot().loader("language1")).refreshing)
        assertTrue("Case B: no native_resume request under the host's refresh", loader.callsFor("native_resume").isEmpty())
        assertEquals("language1", engine.owner)
        assertSame("The old owner ad shows meanwhile", old.admobNativeAd, screen.slot().shownAd())

        val refreshed = fill("language1")
        assertSame(refreshed.admobNativeAd, screen.slot().shownAd())
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

    @Test fun `back from its ad's landing page the screen keeps no ad and requests nothing`() {
        val screen = openResume()
        val own = fill("native_resume")
        assertSame(own.admobNativeAd, screen.slot().shownAd())
        val view = screen.slot().getChildAt(0)
        val requests = loader.attempts.size

        loader.click(own)
        main.idle()
        screen.backAndForth()
        assertEquals("DashDrama's p(false)", View.GONE, screen.block().visibility)
        assertEquals("No refresh, no request", requests, loader.attempts.size)
        assertSame("Nothing drawn again", view, screen.slot().getChildAt(0))

        OnboardingPool.claim(ob1, LoadMode.FORCED)
        fill("step_ob1")
        screen.backAndForth()
        assertEquals("For the rest of its life", View.GONE, screen.block().visibility)
        assertSame(view, screen.slot().getChildAt(0))
        assertEquals(requests + 1, loader.attempts.size)

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
        assertEquals("A screen destroyed under the landing page comes back without its ad", View.GONE,
            restored.block().visibility)
        assertEquals(requests + 1, loader.attempts.size)
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
    }

    @Test fun `prepare stays out of the provider while routed and warms the pool only under resume_background_preload`() {
        ObNativeResume.prepare(app)
        main.idle()
        assertTrue(loader.callsFor("native_resume").isEmpty())
        assertTrue(PoolUiSdk.provider.backgroundPreloads.isEmpty())

        OnboardingSdk.session.nativeFlow = poolSnapshot(SYNC_PARITY.copy(resumeBackgroundPreload = true))
        ObNativeResume.prepare(app)
        main.idle()
        assertEquals(1, loader.callsFor("native_resume").size)
        assertNull("A preload never owns", engine.owner)
        assertTrue(PoolUiSdk.provider.backgroundPreloads.isEmpty())

        OnboardingPool.onClassicRun()
        main.idle()
        ObNativeResume.prepare(app)
        assertEquals(listOf<AdPlacement>(AdPlacement.ResumeNative), PoolUiSdk.provider.backgroundPreloads)
    }
}
