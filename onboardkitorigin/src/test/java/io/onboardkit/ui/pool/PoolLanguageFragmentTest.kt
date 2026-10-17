package io.onboardkit.ui.pool

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolSnapshot
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.PoolPlacementMapper
import io.onboardkit.ads.pool.SYNC_PARITY
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.ads.pool.setPremium
import io.onboardkit.config.GoalOption
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.ui.language.LanguageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.Locale
import java.util.concurrent.TimeUnit.MILLISECONDS

/** The pooled LFO inside its real host; only the ad loader and the interstitial vendor are fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolLanguageFragmentTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private lateinit var locale: Locale
    private var controller: ActivityController<ObPoolFlowActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    private val stageFragment get() = activity.supportFragmentManager.findFragmentByTag("ob_pool_stage")
    private val lfo get() = stageFragment as PoolLanguageFragment
    private val confirm get() = activity.findViewById<View>(R.id.ob_language_confirm)
    private val container get() = activity.findViewById<FrameLayout>(R.id.ob_native_container)
    private val adBlock get() = activity.findViewById<View>(R.id.ob_ad_block)
    private val list get() = activity.findViewById<RecyclerView>(R.id.ob_language_list)
    private val adapter get() = list.adapter as LanguageAdapter
    private val ob1 = AdPlacement.StepNative(StepId.OB1)

    @Before fun setUp() {
        locale = Locale.getDefault()
        loader = ApFakeLoader()
        engine = PoolUiSdk.install(app, loader)
    }

    @After fun tearDown() {
        controller?.let { c ->
            val state = c.get().lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) c.pause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) c.stop()
            if (state != Lifecycle.State.DESTROYED) c.destroy()
        }
        PoolUiSdk.tearDown()
        Locale.setDefault(locale)
    }

    private fun launch(flow: NativeFlowSnapshot = poolSnapshot()): ObPoolFlowActivity {
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java).also {
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup()
        }
        main.idle()
        // Row change animations would move the paused clock on every tap.
        list.itemAnimator = null
        return activity
    }

    private fun tap(index: Int) {
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }.itemView.performClick()
        main.idle()
    }

    private fun fill(key: String): ApNativeAd = loader.fill(key).also { main.idle() }

    private fun backgroundAndReturn() {
        requireNotNull(controller).pause().stop().restart().start().resume()
        main.idle()
    }

    private fun reveal() = main.idleFor(3_000, MILLISECONDS)

    /**
     * A configuration change, or "Don't keep activities" with an opaque ad on top: a new instance
     * gets the saved state in the same process. [between] runs while no instance exists.
     */
    private fun recreateHost(between: () -> Unit = {}) {
        val state = Bundle()
        requireNotNull(controller).pause().saveInstanceState(state).stop().destroy()
        main.idle()
        between()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java).also {
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.setup(state)
        }
        main.idle()
        activity.findViewById<RecyclerView?>(R.id.ob_language_list)?.itemAnimator = null
    }

    /** Done on es with both LFO ads filled; the leftover splash interstitial is up. */
    private fun doneOnSpanish() = launch().let {
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        done()
        PoolUiSdk.provider.presentations.single()
    }

    private fun languageCompleted() = PoolUiSdk.events<AnalyticsEvent.LanguageCompleted>().map { it.screenIndex to it.code }

    private fun idleUntil(uptimeMs: Long) = main.idleFor((uptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0), MILLISECONDS)

    private fun done() {
        confirm.performClick()
        main.idle()
    }

    /** Every pool state published while [block] runs, so an order inside one callback is visible. */
    private fun recordPool(block: () -> Unit): List<PoolSnapshot<ApNativeAd>> {
        val seen = mutableListOf<PoolSnapshot<ApNativeAd>>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { engine.snapshot.collect { seen += it } }
        try {
            block()
        } finally {
            job.cancel()
        }
        return seen
    }

    private fun shown(): NativeAd? = container.shownAd()

    @Test fun `on create the LFO claims language1 POLITE, owns it and shows a skeleton`() {
        launch()
        assertEquals(1, loader.callsFor("language1").size)
        assertEquals("language1", engine.owner)
        assertTrue(container.getChildAt(0) is ShimmerFrameLayout)
        assertEquals(View.VISIBLE, container.visibility)
        assertEquals(View.VISIBLE, adBlock.visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_ad_block_2).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_language_save).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_language_confirm_hint).visibility)
        assertEquals(View.GONE, confirm.visibility)
        assertEquals(listOf(1), PoolUiSdk.events<AnalyticsEvent.LanguageViewed>().map { it.screenIndex })

        val ad = fill("language1")
        assertSame(ad.admobNativeAd, shown())
    }

    @Test fun `the create claim is POLITE, so a load in flight elsewhere skips it while the LFO still owns`() {
        OnboardingPool.claim(ob1, LoadMode.FORCED)
        launch()
        assertTrue(loader.callsFor("language1").isEmpty())
        assertEquals("language1", engine.owner)
        assertTrue(engine.gateSkipCount >= 1)
    }

    @Test fun `the splash handoff is consumed on a fresh LFO`() {
        ReflectionHelpers.setField(OnboardingSdk.preload(), "language1HandoffPending", true)
        launch()
        assertFalse(OnboardingSdk.preload().takeLanguage1Preload())
    }

    @Test fun `a config refusal of language1 hides the block and sets no owner`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(languageNative = null))
        launch()
        assertTrue(loader.callsFor("language1").isEmpty())
        assertNull(engine.owner)
        assertEquals(View.GONE, adBlock.visibility)
        assertEquals(View.GONE, container.visibility)
    }

    @Test fun `a refused language1 collapses the frame only until a tap makes language2 the owner`() {
        PoolUiSdk.configure(POOL_UI_ADS.copy(languageNative = null))
        launch()
        assertEquals(View.GONE, adBlock.visibility)

        tap(0)
        assertEquals("language2", engine.owner)
        assertEquals("DashDrama's h(2, true) opens the frame", View.VISIBLE, adBlock.visibility)
        val ad2 = fill("language2")
        assertSame(ad2.admobNativeAd, shown())
        assertEquals(View.VISIBLE, adBlock.visibility)
    }

    @Test fun `a fresh pooled LFO starts with no selected language`() {
        OnboardingSdk.session.selectedLanguage = "es"
        launch()
        assertNull(adapter.selectedCode)
        assertEquals(View.GONE, confirm.visibility)
        reveal()
        assertEquals("DashDrama reveals Done only after a tap", View.GONE, confirm.visibility)
    }

    @Test fun `a fresh pooled LFO requires a real tap before a repeat raises the dialog`() {
        OnboardingSdk.session.selectedLanguage = "es"
        launch()
        assertNull(adapter.selectedCode)
        tap(1)
        assertNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
        assertEquals("language2", engine.owner)

        tap(1)
        assertTrue("A tap on the row the user picked is a re-tap", ShadowDialog.getLatestDialog().isShowing)
    }

    @Test fun `a saved language the list does not offer selects nothing`() {
        OnboardingSdk.session.selectedLanguage = "de"
        launch()
        assertNull(adapter.selectedCode)
    }

    @Test fun `every tap claims language2 FORCED and the owner follows its success`() {
        launch()
        // language1 is still in flight, so only a FORCED load can go out.
        tap(0)
        assertEquals(1, loader.callsFor("language2").size)
        assertEquals("language2", engine.owner)
        assertEquals("en-US", adapter.selectedCode)

        OnboardingPool.setOwner(ob1)
        tap(1)
        assertEquals("A repeat finds language2 in flight: no second request", 1, loader.callsFor("language2").size)
        assertEquals("language2", engine.owner)

        val selected = PoolUiSdk.events<AnalyticsEvent.LanguageSelected>()
        assertEquals(listOf(1 to "en-US", 2 to "es"), selected.map { it.screenIndex to it.code })
        assertEquals(listOf(1 to "en-US"), PoolUiSdk.events<AnalyticsEvent.LanguageCompleted>().map { it.screenIndex to it.code })
        assertEquals(listOf(1, 2), PoolUiSdk.events<AnalyticsEvent.LanguageViewed>().map { it.screenIndex })
    }

    @Test fun `a refused language2 claim keeps the owner and the language1 slot`() {
        launch()
        val ad = fill("language1")
        setPremium(true)
        tap(0)
        assertTrue(loader.callsFor("language2").isEmpty())
        assertEquals("language1", engine.owner)
        setPremium(false)
        tap(1)
        assertEquals(listOf(1, 1), PoolUiSdk.events<AnalyticsEvent.LanguageSelected>().map { it.screenIndex })
        assertEquals(1, loader.callsFor("language2").size)
        assertSame(ad.admobNativeAd, shown())
    }

    @Test fun `the confirm control appears only the reveal delay after the first tap`() {
        launch()
        // A bound ad, not a skeleton: a starting shimmer moves Robolectric's clock on its own.
        fill("language1")
        main.idleFor(10_000, MILLISECONDS)
        assertEquals(View.GONE, confirm.visibility)

        // A click itself may tick the clock; the reveal is judged around it, not to the millisecond.
        val firstTapAt = SystemClock.uptimeMillis()
        tap(0)
        val firstTapEnd = SystemClock.uptimeMillis()
        main.idleFor(2_000, MILLISECONDS)
        tap(1)
        idleUntil(firstTapAt + 2_990)
        assertEquals(View.GONE, confirm.visibility)
        idleUntil(firstTapEnd + 3_000)
        assertEquals("Counted from the first tap only", View.VISIBLE, confirm.visibility)
    }

    @Test fun `with done_hidden_gone off the hidden confirm keeps its space`() {
        launch(poolSnapshot().copy(doneHiddenGone = false))
        assertEquals(View.INVISIBLE, confirm.visibility)
        tap(0)
        reveal()
        assertEquals(View.VISIBLE, confirm.visibility)
    }

    @Test fun `back is a no-op`() {
        launch()
        tap(0)
        activity.onBackPressedDispatcher.onBackPressed()
        main.idle()
        assertFalse(activity.isFinishing)
        assertTrue(stageFragment is PoolLanguageFragment)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ob_language_save).visibility)
    }

    @Test fun `onResume refreshes the current key without owning it, then claims it POLITE`() {
        launch()
        fill("language1")
        assertEquals(1, loader.callsFor("language1").size)
        OnboardingPool.setOwner(ob1)

        val seen = recordPool { backgroundAndReturn() }
        val refreshing = seen.first { it.loader("language1")?.refreshing == true }
        assertEquals("The refresh leaves the owner alone", "step_ob1", refreshing.owner)
        assertEquals("language1", engine.owner)
        assertEquals(2, loader.callsFor("language1").size)

        tap(0)
        fill("language2")
        OnboardingPool.setOwner(ob1)
        backgroundAndReturn()
        assertEquals("After the first tap the current key is language2", 2, loader.callsFor("language2").size)
        assertEquals("language2", engine.owner)
    }

    @Test fun `an adopted unshown language1 is not refreshed on the first resume`() {
        val request = requireNotNull(PoolPlacementMapper.requestFor(AdPlacement.Language1, poolSnapshot()))
        val ad = ApNativeAd(R.layout.ob_layout_native_lfo, mock(NativeAd::class.java))
        engine.adoptReady(request, ad)
        launch()
        assertTrue(loader.callsFor("language1").isEmpty())
        assertEquals("language1", engine.owner)
        assertSame(ad.admobNativeAd, shown())

        backgroundAndReturn()
        assertEquals("Later resumes refresh as usual", 1, loader.callsFor("language1").size)
    }

    @Test fun `Done shows the interstitial, then persists and switches stage, warming no content page 0`() {
        launch()
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        done()
        val presentation = PoolUiSdk.provider.presentations.single()
        assertTrue(lfo.blocksAppResume)
        assertNull(OnboardingSdk.selectedLanguageOrNull())

        presentation.onNextAction()
        assertNull("AFTER_AD: nothing moves while the ad is up", OnboardingSdk.selectedLanguageOrNull())
        assertTrue(loader.callsFor("step_ob1").isEmpty())

        presentation.onAdClosed()
        assertEquals("es", OnboardingSdk.selectedLanguageOrNull())
        assertTrue("Only a full page is warmed a screen ahead", loader.callsFor("step_ob1").isEmpty())
        assertEquals("language2", engine.owner)
        assertTrue("The switch is committed, not yet run", stageFragment is PoolLanguageFragment)

        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
        assertTrue(stageFragment is PoolPagerFragment)
        assertEquals("Page 0 asks for its own ad once selected", 1, loader.callsFor("step_ob1").size)
        assertEquals(listOf(2 to "es"), PoolUiSdk.events<AnalyticsEvent.LanguageCompleted>()
            .filter { it.screenIndex == 2 }.map { it.screenIndex to it.code })
        assertEquals(listOf("es"), PoolUiSdk.events<AnalyticsEvent.LanguageFlowCompleted>().map { it.code })
        assertEquals(0, PoolUiSdk.suppressDepth())
        val store = requireNotNull(OnboardingSdk.stateStoreOrNull())
        assertTrue(runBlocking { withTimeout(5_000) { store.state.first { it.isLfoCompleted } }.isLfoCompleted })
    }

    @Test fun `D-BIND - a fill landing while the LFO is only started binds once it resumes`() {
        launch()
        requireNotNull(controller).pause()
        main.idle()
        val ad = fill("language1")
        assertTrue("Nothing bound under the cover", container.getChildAt(0) is ShimmerFrameLayout)

        requireNotNull(controller).resume()
        main.idle()
        assertSame(ad.admobNativeAd, shown())
    }

    @Test fun `screen_slots_bind_started binds the LFO's fill while it is only started, as DashDrama`() {
        launch(poolSnapshot(SYNC_PARITY.copy(screenSlotsBindStarted = true)))
        requireNotNull(controller).pause()
        main.idle()
        val ad = fill("language1")
        assertSame(ad.admobNativeAd, shown())
    }

    @Test fun `a Done callback while stopped switches the stage once started again`() {
        launch()
        tap(0)
        reveal()
        done()
        val presentation = PoolUiSdk.provider.presentations.single()
        presentation.onNextAction()
        requireNotNull(controller).pause().saveInstanceState(Bundle()).stop()
        main.idle()

        presentation.onAdClosed()
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.LANGUAGE, activity.stage)
        assertTrue(stageFragment is PoolLanguageFragment)

        requireNotNull(controller).restart().start().resume()
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
        assertTrue(stageFragment is PoolPagerFragment)
    }

    @Test fun `a latched exit requests nothing when the LFO resumes behind its interstitial`() {
        launch()
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        done()
        PoolUiSdk.provider.presentations.single().onNextAction()

        // A silent vendor: the host back in front ends the ad. Fragments resume in onPostResume,
        // before the activity's ON_RESUME that ends it, so the LFO resumes with its exit latched.
        requireNotNull(controller).pause().stop().restart().start().resume()
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
        assertEquals("No refresh of language2", 1, loader.callsFor("language2").size)
        assertEquals(1, loader.callsFor("language1").size)
        assertEquals("Nothing closed the gate on the next stage's preload", 1, loader.callsFor("step_ob1").size)
    }

    @Test fun `a host recreated under the exit interstitial carries the exit on once the ad closes`() {
        val presentation = doneOnSpanish()
        presentation.onNextAction()
        recreateHost()
        assertEquals(ObPoolFlowActivity.Stage.LANGUAGE, activity.stage)
        assertTrue("The restored LFO stays latched", lfo.blocksAppResume)
        assertTrue(activity.resumeBlockedByScreen)
        assertEquals(View.VISIBLE, confirm.visibility)

        done()
        tap(0)
        assertEquals("Done is spent: no second interstitial", 1, PoolUiSdk.provider.presentations.size)
        assertEquals("Nothing requested under the ad", 1, loader.callsFor("language2").size)
        assertEquals(1, loader.callsFor("language1").size)
        assertNull(OnboardingSdk.selectedLanguageOrNull())

        presentation.onAdClosed()
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
        assertTrue(stageFragment is PoolPagerFragment)
        assertEquals("es", OnboardingSdk.selectedLanguageOrNull())
        assertEquals(1, loader.callsFor("step_ob1").size)
        assertEquals(listOf(1 to "es", 2 to "es"), languageCompleted())
        assertEquals(listOf("es"), PoolUiSdk.events<AnalyticsEvent.LanguageFlowCompleted>().map { it.code })
        assertEquals(0, PoolUiSdk.suppressDepth())
    }

    @Test fun `an exit ad that ends while no host exists runs on the next one, the flow end included`() {
        PoolUiSdk.configure(steps = emptyList())
        val presentation = doneOnSpanish()
        recreateHost(between = { presentation.onAdClosed() })
        main.idle()
        assertEquals("es", OnboardingSdk.selectedLanguageOrNull())
        assertTrue("completeFlow ran on the new host", OnboardingSdk.session.finished.get())
        assertTrue(activity.isFinishing)
        assertEquals(listOf("es"), PoolUiSdk.events<AnalyticsEvent.LanguageFlowCompleted>().map { it.code })
    }

    @Test fun `an exit left by an abandoned host does not run in a new hub`() {
        doneOnSpanish()
        requireNotNull(controller).pause().stop().destroy()
        main.idle()
        val stale = PoolUiSdk.provider.presentations.single()

        launch()
        assertFalse(lfo.blocksAppResume)
        stale.onAdClosed()
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.LANGUAGE, activity.stage)
        assertNull(OnboardingSdk.selectedLanguageOrNull())
    }

    @Test fun `a recreation after reset() keeps the pool off`() {
        launch()
        runBlocking { OnboardingSdk.reset() }
        main.idle()
        assertFalse(OnboardingPool.routeResume)

        recreateHost()
        assertFalse("A configuration change is not a new run", OnboardingPool.routeResume)
        assertFalse(OnboardingPool.isActive)
    }

    @Test fun `under respect_focus_window the LFO's requests wait for its window`() {
        val flow = poolSnapshot(SYNC_PARITY.copy(respectFocusWindow = true))
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java).also {
            it.get().setTheme(R.style.ob_Theme_OnboardKit)
            it.create().start().postCreate(null).resume()
        }
        main.idle()
        assertTrue(stageFragment is PoolLanguageFragment)
        assertFalse(activity.hasWindowFocus())
        assertTrue("Nothing goes out before the window has focus", loader.callsFor("language1").isEmpty())
        assertEquals("The LFO owns the slot meanwhile", "language1", engine.owner)
        assertTrue("A wait is no skip", PoolUiSdk.events<AnalyticsEvent.AdSkipped>().isEmpty())

        requireNotNull(controller).visible().windowFocusChanged(true)
        main.idle()
        assertEquals("One request once it has", 1, loader.callsFor("language1").size)
        assertEquals(listOf("language1"), PoolUiSdk.events<AnalyticsEvent.AdRequested>().map { it.placementName })

        list.itemAnimator = null
        tap(0)
        assertEquals("Focused, a tap requests at once", 1, loader.callsFor("language2").size)

        requireNotNull(controller).windowFocusChanged(false)
        fill("language2")
        backgroundAndReturn()
        assertEquals("The return's refresh waits for focus too", 1, loader.callsFor("language2").size)
        requireNotNull(controller).windowFocusChanged(true)
        main.idle()
        assertEquals(2, loader.callsFor("language2").size)
    }

    @Test fun `UNDER_AD moves on as soon as the ad is on screen`() {
        launch(poolSnapshot().copy(exitNextScreenTiming = NextScreenTiming.UNDER_AD))
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        done()
        val presentation = PoolUiSdk.provider.presentations.single()
        assertNull(OnboardingSdk.selectedLanguageOrNull())

        presentation.onNextAction()
        assertEquals("es", OnboardingSdk.selectedLanguageOrNull())
        assertTrue(loader.callsFor("step_ob1").isEmpty())
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
        assertEquals(1, loader.callsFor("step_ob1").size)

        presentation.onAdClosed()
        main.idle()
        assertEquals(1, loader.callsFor("step_ob1").size)
        assertFalse(activity.isFinishing)
    }

    @Test fun `IN_PLACE applies the pick to the next stage without recreating`() {
        PoolUiSdk.provider.interstitialReady = false
        launch()
        tap(1)
        reveal()
        done()
        main.idle()
        val next = requireNotNull(stageFragment)
        assertTrue(next is PoolPagerFragment)
        assertEquals("es", next.requireContext().resources.configuration.locales[0].language)
        assertEquals("es", Locale.getDefault().language)
        assertFalse(activity.isFinishing)
    }

    @Test fun `with no page left the LFO hands over to Privacy, which claims its own first slot`() {
        PoolUiSdk.configure(steps = emptyList(), privacyGoals = PrivacyGoalsScreenConfig(
            enabled = true, goal = GoalsScreenConfig(options = listOf(GoalOption("learn", "Learn"))),
        ))
        PoolUiSdk.provider.interstitialReady = false
        launch()
        fill("language1")
        tap(0)
        fill("language2")
        reveal()
        confirm.performClick()
        assertTrue("Only a full page is warmed a screen ahead", loader.callsFor("step_partner_privacy").isEmpty())
        assertEquals("language2", engine.owner)
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PRIVACY, activity.stage)
        assertEquals(1, loader.callsFor("step_partner_privacy").size)
    }

    @Test fun `with nothing left Done ends the flow`() {
        PoolUiSdk.configure(steps = emptyList())
        PoolUiSdk.provider.interstitialReady = false
        launch()
        tap(0)
        reveal()
        done()
        main.idle()
        assertTrue(OnboardingSdk.session.finished.get())
        assertTrue(activity.isFinishing)
    }

    @Test fun `the confirm dialog owns the pool while it shows and hands the owner back on close`() {
        launch()
        fill("language1")
        tap(0)
        val ad2 = fill("language2")
        val lfoView = container.getChildAt(0)
        assertSame(ad2.admobNativeAd, shown())

        tap(0)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertEquals("language_confirm", engine.owner)
        assertEquals(1, loader.callsFor("language_confirm").size)
        assertTrue(lfo.blocksAppResume)
        val dialogContainer = dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container)
        assertSame("Its own load runs; meanwhile it borrows", ad2.admobNativeAd, dialogContainer.shownAd())
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.ob_confirm_ad_block).visibility)
        assertTrue(PoolUiSdk.events<AnalyticsEvent.LanguageConfirmShown>().single().hasAd)

        val ad3 = fill("language_confirm")
        assertSame(ad3.admobNativeAd, dialogContainer.shownAd())
        assertSame("The LFO slot is frozen behind the dialog", lfoView, container.getChildAt(0))
        assertSame(ad2.admobNativeAd, shown())

        dialog.findViewById<View>(R.id.ob_confirm_cancel).performClick()
        main.idle()
        assertFalse(dialog.isShowing)
        assertEquals("language2", engine.owner)
        assertNotSame("Unfrozen, it draws again", lfoView, container.getChildAt(0))
        assertSame(ad2.admobNativeAd, shown())
        assertFalse(lfo.blocksAppResume)
        assertEquals(listOf("cancel"), PoolUiSdk.events<AnalyticsEvent.LanguageConfirmDismissed>().map { it.reason })
        assertEquals(0, PoolUiSdk.suppressDepth())
    }

    @Test fun `D-BIND - the confirm popup binds a fill landing under a translucent window once the LFO resumes`() {
        launch()
        fill("language1")
        tap(0)
        val ad2 = fill("language2")
        tap(0)
        val popupSlot = ShadowDialog.getLatestDialog().findViewById<FrameLayout>(R.id.ob_confirm_native_container)
        val borrowed = popupSlot.getChildAt(0)
        assertSame(ad2.admobNativeAd, popupSlot.shownAd())

        requireNotNull(controller).pause()
        main.idle()
        val ad3 = fill("language_confirm")
        assertSame("The popup stays RESUMED over a paused LFO, yet binds nothing", borrowed, popupSlot.getChildAt(0))

        requireNotNull(controller).resume()
        main.idle()
        assertSame(ad3.admobNativeAd, popupSlot.shownAd())
    }

    @Test fun `screen_slots_bind_started binds the confirm popup's fill while the LFO is started, not while stopped`() {
        launch(poolSnapshot(SYNC_PARITY.copy(screenSlotsBindStarted = true)))
        fill("language1")
        tap(0)
        val ad2 = fill("language2")
        tap(0)
        val popupSlot = ShadowDialog.getLatestDialog().findViewById<FrameLayout>(R.id.ob_confirm_native_container)

        requireNotNull(controller).pause().stop()
        main.idle()
        val ad3 = fill("language_confirm")
        assertSame("Nothing bound in the background", ad2.admobNativeAd, popupSlot.shownAd())

        requireNotNull(controller).restart().start()
        main.idle()
        assertSame(ad3.admobNativeAd, popupSlot.shownAd())
    }

    @Test fun `closing the dialog never draws its ad in the LFO slot while the selection lags`() {
        engine = installLaggingSelectorEngine(loader)
        launch()
        fill("language1")
        tap(0)
        val ad2 = fill("language2")
        tap(0)
        val dialog = ShadowDialog.getLatestDialog()
        val ad3 = fill("language_confirm")
        assertSame(ad3.admobNativeAd, dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container).shownAd())

        val drawn = mutableListOf<NativeAd?>()
        container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child is NativeAdView) drawn += Shadow.extract<PoolGlueViewShadow>(child).registered
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        dialog.findViewById<View>(R.id.ob_confirm_cancel).performClick()
        main.idle()
        assertEquals("language2", engine.owner)
        assertEquals("One fresh view, for the LFO's own ad", listOf(ad2.admobNativeAd), drawn)
        assertSame(ad2.admobNativeAd, shown())
    }

    @Test fun `accepting the dialog runs Done with the owner handed back first`() {
        PoolUiSdk.provider.interstitialReady = false
        launch()
        tap(1)
        tap(1)
        val dialog = ShadowDialog.getLatestDialog()
        assertEquals("language_confirm", engine.owner)
        dialog.findViewById<View>(R.id.ob_confirm_accept).performClick()
        assertFalse(dialog.isShowing)
        assertEquals("language2", engine.owner)
        assertEquals("es", OnboardingSdk.selectedLanguageOrNull())
        assertEquals(listOf("es"), PoolUiSdk.events<AnalyticsEvent.LanguageConfirmAccepted>().map { it.code })
        assertEquals(listOf("es"), PoolUiSdk.events<AnalyticsEvent.LanguageFlowCompleted>().map { it.code })
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
    }

    @Test fun `a dialog opened before any fill reports no ad and shows the skeleton`() {
        launch()
        tap(0)
        tap(0)
        val dialog = ShadowDialog.getLatestDialog()
        assertFalse(PoolUiSdk.events<AnalyticsEvent.LanguageConfirmShown>().single().hasAd)
        assertTrue(dialog.findViewById<FrameLayout>(R.id.ob_confirm_native_container).getChildAt(0) is ShimmerFrameLayout)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.ob_confirm_ad_block).visibility)

        dialog.cancel()
        main.idle()
        assertEquals(listOf("dismiss"), PoolUiSdk.events<AnalyticsEvent.LanguageConfirmDismissed>().map { it.reason })
        assertEquals("language2", engine.owner)
    }

    @Test fun `the dialog follows the classic tap count`() {
        launch()
        tap(0)
        tap(1)
        tap(2)
        assertNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
        tap(0)
        assertTrue("The fourth tap raises it", ShadowDialog.getLatestDialog().isShowing)
    }

    @Test fun `a resume under the dialog keeps the dialog the owner`() {
        launch()
        fill("language1")
        tap(0)
        fill("language2")
        tap(0)
        fill("language_confirm")
        backgroundAndReturn()
        assertEquals("language_confirm", engine.owner)
        assertEquals("The dialog's key is refreshed, not the LFO's", 2, loader.callsFor("language_confirm").size)
        assertEquals(1, loader.callsFor("language2").size)
    }

    @Test fun `leaving the stage clears only the LFO's own owner`() {
        PoolUiSdk.provider.interstitialReady = false
        launch()
        tap(0)
        reveal()
        done()
        main.idle()
        assertNotEquals("The LFO's owner does not outlive its view", "language2", engine.owner)
    }

    @Test fun `a recreated LFO keeps its pick, LFO2 and the revealed confirm without adopting again`() {
        launch()
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        ReflectionHelpers.setField(OnboardingSdk.preload(), "language1HandoffPending", true)

        requireNotNull(controller).recreate()
        main.idle()
        list.itemAnimator = null
        assertEquals("es", adapter.selectedCode)
        assertEquals(View.VISIBLE, confirm.visibility)
        assertEquals("language2", engine.owner)
        assertTrue("Only a fresh LFO takes the splash handoff", OnboardingSdk.preload().takeLanguage1Preload())
        assertEquals(1, PoolUiSdk.events<AnalyticsEvent.LanguageCompleted>().count { it.screenIndex == 1 })

        tap(0)
        assertEquals(2, PoolUiSdk.events<AnalyticsEvent.LanguageSelected>().last().screenIndex)
    }

    @Test fun `a dialog still open when the view goes leaves neither its owner nor the LFO's`() {
        launch()
        fill("language1")
        tap(0)
        tap(0)
        assertEquals("language_confirm", engine.owner)

        requireNotNull(controller).pause().stop().destroy()
        main.idle()
        assertNull(engine.owner)
        assertEquals("A torn-down view is not a dismissal by the user", 0,
            PoolUiSdk.events<AnalyticsEvent.LanguageConfirmDismissed>().size)
    }

    @Test fun `OB1 back restores the pick on language2 with Done visible at once`() {
        PoolUiSdk.provider.interstitialReady = false
        launch()
        fill("language1")
        tap(1)
        fill("language2")
        reveal()
        done()
        main.idle()
        ReflectionHelpers.setField(OnboardingSdk.preload(), "language1HandoffPending", true)
        val viewsBefore = PoolUiSdk.events<AnalyticsEvent.LanguageViewed>().size

        activity.returnToLanguage("es")
        main.idle()
        assertEquals(ObPoolFlowActivity.Stage.LANGUAGE, activity.stage)
        assertEquals("es", adapter.selectedCode)
        assertEquals(View.VISIBLE, confirm.visibility)
        assertEquals("language2", engine.owner)
        assertTrue("The splash handoff is not touched", OnboardingSdk.preload().takeLanguage1Preload())
        assertEquals("Its first resume refreshes as usual", 2, loader.callsFor("language2").size)
        assertEquals(listOf(2), PoolUiSdk.events<AnalyticsEvent.LanguageViewed>().drop(viewsBefore).map { it.screenIndex })

        tap(0)
        assertEquals(2, PoolUiSdk.events<AnalyticsEvent.LanguageSelected>().last().screenIndex)
        assertEquals("No second LFO1 completion", 1, PoolUiSdk.events<AnalyticsEvent.LanguageCompleted>()
            .count { it.screenIndex == 1 })
    }

    private val loadingCard get() = activity.findViewById<View>(R.id.ob_language_loading)

    /** Launching already lets the resumed screen's clock run, so tests read what is left. */
    private fun loadingLeft(): Long = ReflectionHelpers.getField(lfo, "loadingLeftMs")

    private fun loadingFlow(ms: Long = 4_000L) = poolSnapshot().copy(lfoLoadingDialogMs = ms)

    @Test fun `LFO1 opens under a loading card that takes no tap until its countdown ends`() {
        launch(loadingFlow())
        val left = loadingLeft()
        assertTrue(left in 1..4_000)
        assertEquals(View.VISIBLE, loadingCard.visibility)
        assertEquals("The ad is requested under the card", 1, loader.callsFor("language1").size)

        tap(0)
        assertNull("No pick while loading", adapter.selectedCode)
        assertTrue(loader.callsFor("language2").isEmpty())

        main.idleFor(left - 100, MILLISECONDS)
        assertEquals(View.VISIBLE, loadingCard.visibility)
        main.idleFor(400, MILLISECONDS)
        assertEquals(View.GONE, loadingCard.visibility)

        tap(0)
        assertEquals("en-US", adapter.selectedCode)
    }

    @Test fun `the loading countdown stands still while the app is away`() {
        launch(loadingFlow(8_000L))
        val left = loadingLeft()
        requireNotNull(controller).pause().stop()
        main.idleFor(10_000, MILLISECONDS)
        assertEquals(left, loadingLeft())
        requireNotNull(controller).restart().start().resume()
        main.idle()
        val back = loadingLeft()
        assertTrue("Counted again only once resumed: $back of $left", back in 1..left)
        main.idleFor(back - 100, MILLISECONDS)
        assertEquals(View.VISIBLE, loadingCard.visibility)
        main.idleFor(400, MILLISECONDS)
        assertEquals(View.GONE, loadingCard.visibility)
    }

    @Test fun `a recreated LFO1 keeps only what is left of the countdown`() {
        launch(loadingFlow(8_000L))
        main.idleFor(500, MILLISECONDS)
        val before = loadingLeft()
        recreateHost()
        assertEquals(View.VISIBLE, loadingCard.visibility)
        val left = loadingLeft()
        assertTrue("Resumed from $before, not restarted: $left", left in 1..before)
        main.idleFor(left + 300, MILLISECONDS)
        assertEquals(View.GONE, loadingCard.visibility)
    }

    @Test fun `no loading card at the bundled zero`() {
        launch()
        assertEquals(View.GONE, loadingCard.visibility)
    }
}
