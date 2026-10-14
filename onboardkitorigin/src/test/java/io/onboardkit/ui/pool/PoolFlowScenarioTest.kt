package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.AdGate
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolParity
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.PoolSnapshot
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.ObNativeResume
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.ads.pool.setPremium
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.GoalOption
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.GoalAnswer
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.ui.language.LanguageAdapter
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.pool.ObPoolFlowActivity.Stage
import io.trackkit.AdFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Whole SHARED_POOL runs through the real host, LFO to completion, with only the pool's ad loader
 * and the interstitial vendor faked: the DashDrama outcomes OC1-OC5, a purchase mid-flow, the
 * in-place locale and the analytics funnel against the classic flow for the same taps.
 */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider. The pager package is instrumented for its dwell clock.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.pager", "io.onboardkit.ui.widget"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolFlowScenarioTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private lateinit var vendor: ScenarioVendor
    private lateinit var locale: Locale
    private val controllers = mutableListOf<ActivityController<*>>()
    private var run: ActivityController<ObPoolFlowActivity>? = null
    private val host get() = requireNotNull(run).get()
    private val stage get() = host.supportFragmentManager.findFragmentByTag("ob_pool_stage")
    private val pager get() = host.findViewById<ViewPager2>(R.id.ob_pool_pager)

    private val ob1 = ContentStepDefinition(StepId.OB1, title = "One")
    private val ob2 = ContentStepDefinition(StepId.OB2, title = "Two")
    private val ob3 = ContentStepDefinition(StepId.OB3, title = "Three")
    private val full1 = AdFullScreenStepDefinition(StepId.FULL1)
    private val stepOb1 = AdPlacement.StepNative(StepId.OB1)
    private val stepOb2 = AdPlacement.StepNative(StepId.OB2)
    private val fullPage = AdPlacement.StepFullScreen(StepId.FULL1)
    private val privacy = AdPlacement.StepNative(StepId.PARTNER_PRIVACY)
    private val privacyAlt = AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT)
    private val goal = AdPlacement.StepNative(StepId.PARTNER_GOAL)
    private val goalAlt = AdPlacement.StepNative(StepId.PARTNER_GOAL_ALT)
    private val language1 = AdPlacement.Language1.key
    private val language2 = AdPlacement.Language2.key

    private val goals = PrivacyGoalsScreenConfig(
        enabled = true,
        goal = GoalsScreenConfig(options = listOf(GoalOption("read", "Read"), GoalOption("edit", "Edit")), minSelection = 1),
    )

    @Before fun setUp() {
        locale = Locale.getDefault()
        // A running shimmer would move the clock on every frame; paused frames wait for it instead.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
        // Animation time never rewinds: a slide left running by another test would freeze this one's.
        AnimationUtils.currentAnimationTimeMillis()
        loader = ApFakeLoader()
        vendor = ScenarioSdk.install(app)
        engine = installTestEngine(loader)
    }

    @After fun tearDown() {
        controllers.asReversed().forEach(::close)
        controllers.clear()
        ScenarioSdk.tearDown()
        ShadowChoreographer.reset()
        Locale.setDefault(locale)
    }

    // ── Driving a run ──

    private fun close(controller: ActivityController<*>) {
        val owner = controller.get() as? LifecycleOwner
        if (owner == null) {
            runCatching { controller.pause().stop().destroy() }
            main.idle()
            return
        }
        val state = owner.lifecycle.currentState
        runCatching {
            if (state.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            if (state != Lifecycle.State.DESTROYED) controller.destroy()
        }
        main.idle()
    }

    /** Sync messages wait behind a traversal barrier until its frame runs; a few frames let both through. */
    private fun pump() {
        main.idle()
        repeat(4) { main.idleFor(FRAME_MS, MILLISECONDS) }
    }

    private fun waitMs(ms: Long) {
        main.idleFor(ms, MILLISECONDS)
        pump()
    }

    /** The user taps again once the slide has ended. */
    private fun settle(pager: ViewPager2) {
        var frames = 0
        do main.idleFor(FRAME_MS, MILLISECONDS)
        while (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE && ++frames < 1_000)
        pump()
    }

    /** What `startResolved` does for a pooled run: latch the knobs, activate the pool, open the host. */
    private fun start(flow: NativeFlowSnapshot = poolSnapshot()) {
        OnboardingSdk.session.begin(null)
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
        run = Robolectric.buildActivity(ObPoolFlowActivity::class.java).also { controllers += it }
        host.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(run).setup().visible()
        pump()
        // Row change animations would move the paused clock on every tap.
        host.findViewById<RecyclerView>(R.id.ob_language_list).itemAnimator = null
    }

    private fun tapLanguage(index: Int) {
        host.tapLanguage(index)
        pump()
    }

    private fun fill(key: String): ApNativeAd = loader.fill(key).also { pump() }

    private fun lfoSlot(): FrameLayout = host.findViewById(R.id.ob_native_container)

    /** The LFO's Done after its reveal delay, then the leftover splash interstitial up and closed. */
    private fun doneThroughInterstitial() {
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_language_confirm).performClick()
        pump()
        closeInterstitial()
    }

    private fun closeInterstitial() {
        val presentation = vendor.presentations.last()
        presentation.onNextAction()
        presentation.onAdClosed()
        pump()
    }

    private fun page(position: Int): Fragment = pageOf((stage as PoolPagerFragment).childFragmentManager, position)

    private fun pageOf(fragments: FragmentManager, position: Int): Fragment =
        fragments.fragments.first { it.arguments?.getInt("ob_arg_position", -1) == position }

    private fun pageSlot(position: Int): FrameLayout = page(position).requireView().findViewById(R.id.ob_native_container)

    /** The current content page's CTA; the last one leaves the pager. */
    private fun cta() {
        page(pager.currentItem).requireView().findViewById<View>(R.id.ob_primary_cta).performClick()
        settle(pager)
    }

    /** X on the current full page, at its skip delay. */
    private fun skipFullPage() {
        waitMs(2_000)
        page(pager.currentItem).requireView().findViewById<View>(R.id.ob_skip_button).performClick()
        settle(pager)
    }

    private fun selectSlot(): FrameLayout = host.findViewById(R.id.ob_privacy_goals_ad)

    private fun privacyThroughContinue() {
        host.findViewById<CheckBox>(R.id.ob_privacy_consent_checkbox).performClick()
        pump()
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_privacy_goals_continue).performClick()
        pump()
    }

    private fun tapGoal(position: Int) {
        val list = host.findViewById<RecyclerView>(R.id.ob_goal_options)
        requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performClick()
        pump()
    }

    private fun goalNextThroughInterstitial() {
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_privacy_goals_continue).performClick()
        pump()
        closeInterstitial()
    }

    /** Every pool state published while [block] runs. */
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

    /** Production's async gate and selector, derived in later main-looper messages. */
    private fun installAsyncEngine(): NativePoolEngine<ApNativeAd> {
        SharedNativePool.resetForTest()
        return NativePoolEngine(
            loader = loader,
            policy = PoolRequestPolicy.ALLOW_ALL,
            parity = PoolParity(),
            clock = SystemPoolClock,
            scheduler = MainLooperPoolScheduler(),
            expiry = PoolExpiry { 0L },
            derivedDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
        ).also(SharedNativePool::installForTest)
    }

    // ── OC1-OC5 ──

    @Test fun `OC1 a late LFO2 fill shows on page 0`() {
        ScenarioSdk.configure(listOf(ob1, ob2))
        start()
        loader.exhaust(language1)
        tapLanguage(1)
        assertEquals(listOf("lfo2-high"), loader.callsFor(language2))
        doneThroughInterstitial()

        assertEquals(Stage.PAGER, host.stage)
        assertEquals(0, pager.currentItem)
        assertEquals("Page 0's own claim met LFO2's chain", LoadResult.SkippedByGate, OnboardingPool.lastResult(stepOb1))
        assertTrue(loader.callsFor(stepOb1.key).isEmpty())
        assertEquals(stepOb1.key, engine.owner)
        assertTrue("Nothing loaded yet: the skeleton", pageSlot(0).getChildAt(0) is ShimmerFrameLayout)

        val lfo2 = fill(language2)
        assertSame(lfo2.admobNativeAd, pageSlot(0).shownAd())
        assertEquals("Page 0 still owns the slot it borrows into", stepOb1.key, engine.owner)
    }

    @Test fun `OC2 with LFO2 loading at Done, page 0 is gate-skipped and shows LFO1 then LFO2 (sync parity)`() {
        oc2(poolSnapshot())
    }

    @Test fun `OC2 with LFO2 loading at Done, page 0 is gate-skipped and shows LFO1 then LFO2 (default async parity)`() {
        engine = installAsyncEngine()
        oc2(poolSnapshot(PoolParity()))
    }

    private fun oc2(flow: NativeFlowSnapshot) {
        ScenarioSdk.configure(listOf(ob1, ob2))
        start(flow)
        val lfo1 = fill(language1)
        assertSame(lfo1.admobNativeAd, lfoSlot().shownAd())
        loader.impress(lfo1)
        pump()
        tapLanguage(1)
        assertTrue(requireNotNull(engine.debugSnapshot().loader(language2)).inFlight)
        assertSame("Rule 3 on the LFO while LFO2 loads", lfo1.admobNativeAd, lfoSlot().shownAd())
        doneThroughInterstitial()

        assertEquals(Stage.PAGER, host.stage)
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(stepOb1))
        assertTrue("Neither Done's preload nor page 0's claim went out", loader.callsFor(stepOb1.key).isEmpty())
        assertTrue(OnboardingPool.dump(), OnboardingPool.dump().contains("step_ob1=2"))
        assertEquals(stepOb1.key, engine.owner)
        assertSame("Rule 3: the showing LFO1", lfo1.admobNativeAd, pageSlot(0).shownAd())

        val lfo2 = fill(language2)
        assertSame("Rule 2: the newest loaded", lfo2.admobNativeAd, pageSlot(0).shownAd())
        assertEquals(stepOb1.key, engine.owner)
    }

    @Test fun `OC3 a late FULL1 fill shows on Goal by rule 2`() {
        ScenarioSdk.configure(listOf(ob1, full1, ob2), privacyGoals = goals)
        start()
        fill(language1)
        tapLanguage(1)
        fill(language2)
        doneThroughInterstitial()
        assertEquals("Done's preload went out with the gate open", listOf("content"), loader.callsFor(stepOb1.key))
        val ob1Ad = fill(stepOb1.key)
        assertSame(ob1Ad.admobNativeAd, pageSlot(0).shownAd())

        cta()
        assertEquals(1, pager.currentItem)
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(fullPage))
        skipFullPage()
        assertEquals(2, pager.currentItem)
        assertEquals("FULL1 still loading", LoadResult.SkippedByGate, OnboardingPool.lastResult(stepOb2))

        cta()
        assertEquals(Stage.PRIVACY, host.stage)
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(privacy))
        privacyThroughContinue()
        assertEquals(Stage.GOAL, host.stage)
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(goal))
        assertEquals(goal.key, engine.owner)
        assertTrue(requireNotNull(engine.debugSnapshot().loader(fullPage.key)).inFlight)
        assertTrue("Privacy's ALT still loads too", requireNotNull(engine.debugSnapshot().loader(privacyAlt.key)).inFlight)

        val late = fill(fullPage.key)
        assertSame("Goal owns nothing loaded: the newest loaded is FULL1's", late.admobNativeAd, selectSlot().shownAd())
        assertEquals(goal.key, engine.owner)
        assertEquals(View.VISIBLE, selectSlot().visibility)
    }

    @Test fun `OC4 nothing is cancelled across stage changes or completeFlow`() {
        ScenarioSdk.configure(listOf(ob1, full1, ob2), privacyGoals = goals)
        start()
        val pending = listOf(language1, language2, privacyAlt.key, goalAlt.key)
        val seen = recordPool {
            tapLanguage(1)
            doneThroughInterstitial()
            assertEquals(Stage.PAGER, host.stage)
            cta()
            skipFullPage()
            cta()
            assertEquals(Stage.PRIVACY, host.stage)
            privacyThroughContinue()
            assertEquals(Stage.GOAL, host.stage)
            tapGoal(0)
            goalNextThroughInterstitial()
            assertTrue(host.isFinishing)
            close(requireNotNull(run))
        }
        assertEquals(1, ScenarioSdk.outcomes.size)
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        assertEquals("completeFlow released the provider, not the pool", 1, vendor.releaseAllCalls)

        // Once a chain started, no published state ever showed it stopped before its fill.
        pending.forEach { key ->
            val states = seen.mapNotNull { it.loader(key) }
            assertTrue("$key started", states.isNotEmpty())
            assertTrue("$key was never cancelled", states.all { it.inFlight })
        }
        assertEquals("No stage cleared the gate's closers", pending.toSet(),
            engine.debugSnapshot().loaders.filter { it.inFlight }.map { it.key }.toSet())

        val ads = pending.associateWith { fill(it) }
        pending.forEach { key ->
            val view = requireNotNull(engine.debugSnapshot().loader(key))
            assertSame("$key's late fill is kept", ads.getValue(key), view.ad)
            assertTrue(view.state is PoolState.Loaded)
            assertFalse(view.inFlight)
            assertEquals("One chain, one id: nothing was re-requested", 1, loader.callsFor(key).size)
        }
        assertNull(engine.owner)
        assertTrue(OnboardingPool.routeResume)
    }

    @Test fun `OC5 after completeFlow the pooled resume screen shows the newest leftover`() {
        ScenarioSdk.configure(listOf(ob1))
        start()
        val lfo1 = fill(language1)
        loader.impress(lfo1)
        tapLanguage(1)
        doneThroughInterstitial()
        cta()
        assertTrue(host.isFinishing)
        close(requireNotNull(run))
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        assertTrue("keep_after_flow: routing stays on", OnboardingPool.routeResume)
        assertTrue(OnboardingPool.isActive)
        assertNull("Every stage cleared its own owner", engine.owner)

        val leftover = fill(language2)
        val app = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()
        ObNativeResume.show(app)
        val intent = requireNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(ObPoolResumeActivity::class.java.name, intent.component?.className)
        val resume = Robolectric.buildActivity(ObPoolResumeActivity::class.java, intent).also { controllers += it }
        resume.get().setTheme(R.style.ob_Theme_OnboardKit)
        resume.setup()
        pump()
        val slot = resume.get().findViewById<FrameLayout>(R.id.ob_resume_ad)
        assertSame("Newest loaded beats the showing LFO1", leftover.admobNativeAd, slot.shownAd())
        assertEquals(View.VISIBLE, resume.get().findViewById<View>(R.id.ob_resume_ad_block).visibility)
        assertNull("The resume screen never owns", engine.owner)
        assertEquals("Its own POLITE load found the gate open", listOf("resume"), loader.callsFor(AdPlacement.ResumeNative.key))
        assertTrue(OnboardingPool.routeResume)
    }

    // ── Purchase ──

    @Test fun `a purchase mid-flow hides every slot and requests nothing more, and the flow's end drops the pool`() {
        ScenarioSdk.configure(listOf(ob1, ob2, ob3))
        start()
        tapLanguage(1)
        val lfo2 = fill(language2)
        doneThroughInterstitial()
        assertSame("LFO1 still loading: page 0 borrows LFO2", lfo2.admobNativeAd, pageSlot(0).shownAd())
        val attempts = loader.attempts.size

        setPremium(true)
        // The purchase screen covered the flow; the user is back.
        requireNotNull(run).pause().stop()
        pump()
        requireNotNull(run).restart().start().resume()
        pump()
        assertNull(pageSlot(0).shownAd())
        assertEquals(View.GONE, pageSlot(0).visibility)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.PREMIUM.key), OnboardingPool.lastResult(stepOb1))
        assertEquals("Neither the resume refresh nor the retried claim went out", attempts, loader.attempts.size)

        loader.last(language1).sink.onFailed()
        pump()
        assertEquals("A running chain stops at its next id", attempts, loader.attempts.size)
        assertFalse(requireNotNull(engine.debugSnapshot().loader(language1)).inFlight)

        cta()
        assertEquals(1, pager.currentItem)
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.PREMIUM.key), OnboardingPool.lastResult(stepOb2))
        assertNull(pageSlot(1).shownAd())
        assertEquals(View.GONE, pageSlot(1).visibility)
        cta()
        assertNull(pageSlot(2).shownAd())
        assertTrue("Ads still held mid-flow without the premium observer",
            engine.debugSnapshot().loaders.any { it.ad != null })
        cta()
        assertTrue(host.isFinishing)
        assertEquals(attempts, loader.attempts.size)
        assertTrue("A bought run ends with an empty pool", engine.debugSnapshot().loaders.isEmpty())
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
    }

    @Test fun `with the premium observer a purchase mid-flow empties the pool at once`() {
        ScenarioSdk.configure(listOf(ob1, ob2))
        start()
        fill(language1)
        tapLanguage(1)
        val lfo2 = fill(language2)
        doneThroughInterstitial()
        assertEquals("Done's preload is still loading page 0's own ad", listOf("content"), loader.callsFor(stepOb1.key))
        assertSame(lfo2.admobNativeAd, pageSlot(0).shownAd())
        val attempts = loader.attempts.size

        val premium = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob())
        AdGate.installPremiumObserver(scope, premium)
        try {
            setPremium(true)
            premium.value = true
            pump()
        } finally {
            scope.cancel()
        }
        assertTrue("releaseBufferedAds released the pool", engine.debugSnapshot().loaders.isEmpty())
        assertNull(engine.owner)
        assertNull(pageSlot(0).shownAd())
        assertEquals(View.GONE, pageSlot(0).visibility)

        loader.last(stepOb1.key).fill()
        pump()
        assertTrue("The released chain's late fill is dropped", engine.debugSnapshot().loaders.isEmpty())
        assertNull(pageSlot(0).shownAd())

        cta()
        assertNull(pageSlot(1).shownAd())
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.PREMIUM.key), OnboardingPool.lastResult(stepOb2))
        cta()
        assertTrue(host.isFinishing)
        assertEquals("Nothing was requested after the purchase", attempts, loader.attempts.size)
        assertTrue(engine.debugSnapshot().loaders.isEmpty())
    }

    // ── Locale ──

    @Test fun `IN_PLACE applies the pick to the pager and Goal strings without recreating`() {
        // Framework strings: the SDK ships no translations of its own, Android ships es for these.
        ScenarioSdk.configure(
            listOf(ContentStepDefinition(StepId.OB1, titleRes = android.R.string.cancel)),
            privacyGoals = PrivacyGoalsScreenConfig(
                enabled = true,
                goal = GoalsScreenConfig(options = listOf(GoalOption("ok", titleRes = android.R.string.ok)), minSelection = 1),
            ),
        )
        vendor.interstitialReady = false
        start()
        val created = host
        assertEquals("Cancel", host.getString(android.R.string.cancel))
        tapLanguage(1)
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_language_confirm).performClick()
        pump()

        assertEquals(Stage.PAGER, host.stage)
        assertSame("Not recreated", created, host)
        assertEquals("es", Locale.getDefault().language)
        assertEquals("Cancelar", page(0).requireView().findViewById<TextView>(R.id.ob_step_title).text.toString())

        cta()
        assertEquals(Stage.PRIVACY, host.stage)
        privacyThroughContinue()
        assertEquals(Stage.GOAL, host.stage)
        val option = requireNotNull(host.findViewById<RecyclerView>(R.id.ob_goal_options).findViewHolderForAdapterPosition(0))
        assertEquals("Aceptar", firstText(option.itemView))
        tapGoal(0)
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_privacy_goals_continue).performClick()
        pump()
        assertSame(created, host)
        assertEquals(listOf(GoalAnswer("ok", "Aceptar")), OnboardingSdk.session.goals.toList())
        assertEquals("es", (ScenarioSdk.outcomes.single() as OnboardingOutcome.Completed).selectedLanguage)
    }

    private fun firstText(view: View): String? = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstText(view.getChildAt(it)) }
        else -> null
    }

    // ── Analytics against the classic flow ──

    /**
     * One tap script in both types: pick es, then fr, LFO2 fills, Done after the reveal, the leftover
     * splash interstitial closes, CTA on both pages. Native requests and binds are not compared:
     * the pool and the provider count them differently by design.
     *
     * Expected differences, and only these:
     * - D-ANALYTICS-ORDER: the pool reports LanguageCompleted(1) and LanguageViewed(2) when the first
     *   tap's Language2 claim succeeds, so the second tap already counts on screen 2; classic reports
     *   them when LFO2 binds, after both taps. The pool also saves the pick only after the LFO's
     *   interstitial, classic before it.
     * - No pager-exit interstitial: classic asks for `inter_after_ob3` at the last page (refused by
     *   `onboarding.exit_interstitial.enabled`, off by default); the pool never asks.
     */
    @Test fun `the analytics funnel matches the classic flow for the same taps but for the documented deviations`() {
        val steps = listOf(ob1, ob2)
        ScenarioSdk.configure(steps)
        OnboardingSdk.session.begin(null)
        val classicLfo = Robolectric.buildActivity(ObLanguageActivity::class.java,
            ObLanguageActivity.intentFor(app, LanguageScreenMode.FIRST_OPEN)).also { controllers += it }
        classicLfo.get().setTheme(R.style.ob_Theme_OnboardKit)
        classicLfo.setup().visible()
        pump()
        val list = classicLfo.get().findViewById<RecyclerView>(R.id.ob_language_list)
        list.itemAnimator = null
        tapRow(list, 1)
        tapRow(list, 2)
        vendor.fillNative(AdPlacement.Language2)
        pump()
        waitMs(3_000)
        classicLfo.get().findViewById<View>(R.id.ob_language_confirm).performClick()
        pump()
        assertEquals("Classic saves the pick before its interstitial", "fr", OnboardingSdk.selectedLanguageOrNull())
        closeInterstitial()
        val classicIntent = requireNotNull(shadowOf(classicLfo.get()).nextStartedActivity)
        val classicHost = Robolectric.buildActivity(ObOnboardingHostActivity::class.java, classicIntent)
            .also { controllers += it }
        classicHost.get().setTheme(R.style.ob_Theme_OnboardKit)
        classicHost.setup().visible()
        pump()
        val classicPager = classicHost.get().findViewById<ViewPager2>(R.id.ob_step_pager)
        repeat(steps.size) {
            pageOf(classicHost.get().supportFragmentManager, classicPager.currentItem).requireView()
                .findViewById<View>(R.id.ob_primary_cta).performClick()
            settle(classicPager)
        }
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        val classic = funnel()
        val classicPresented = vendor.presented.toList()
        controllers.asReversed().forEach(::close)
        controllers.clear()

        runBlocking { OnboardingSdk.reset() }
        main.idle()
        Locale.setDefault(locale)
        ScenarioSdk.analytics.clear()
        ScenarioSdk.outcomes.clear()
        vendor.presented.clear()
        start()
        tapLanguage(1)
        tapLanguage(2)
        fill(language2)
        waitMs(3_000)
        host.findViewById<View>(R.id.ob_language_confirm).performClick()
        pump()
        assertNull("D-ANALYTICS-ORDER: the pool saves it after", OnboardingSdk.selectedLanguageOrNull())
        closeInterstitial()
        assertEquals("fr", OnboardingSdk.selectedLanguageOrNull())
        repeat(steps.size) { cta() }
        assertTrue(ScenarioSdk.outcomes.single() is OnboardingOutcome.Completed)
        val pooled = funnel()

        assertEquals(listOf(
            "lfo_view 1", "lfo_select 1 es", "lfo_select 1 fr", "lfo_complete 1 es", "lfo_view 2",
            "lfo_complete 2 fr", "lfo_flow_complete fr",
            "step_view ob1@0", "step_complete ob1@0 cta", "step_view ob2@1", "step_complete ob2@1 cta",
            "inter_skip inter_after_ob3 ${AdSkipReason.ADS_OFF_IN_CONFIG.key}",
            "flow_complete 2",
        ), classic)
        assertEquals(listOf(
            "lfo_view 1", "lfo_select 1 es", "lfo_complete 1 es", "lfo_view 2", "lfo_select 2 fr",
            "lfo_complete 2 fr", "lfo_flow_complete fr",
            "step_view ob1@0", "step_complete ob1@0 cta", "step_view ob2@1", "step_complete ob2@1 cta",
            "flow_complete 2",
        ), pooled)

        // The two deviations account for every difference: drop the exit ask, move the LFO2 pair
        // from the fill to the first tap, and the second tap lands on screen 2.
        val classicWithDeviations = classic.toMutableList().apply {
            remove("inter_skip inter_after_ob3 ${AdSkipReason.ADS_OFF_IN_CONFIG.key}")
            removeAll(listOf("lfo_complete 1 es", "lfo_view 2"))
            addAll(indexOf("lfo_select 1 es") + 1, listOf("lfo_complete 1 es", "lfo_view 2"))
            set(indexOf("lfo_select 1 fr"), "lfo_select 2 fr")
        }
        assertEquals(classicWithDeviations, pooled)
        assertEquals("Only the LFO exit reuses the splash interstitial in both",
            listOf(AdPlacement.SplashInterstitial), classicPresented)
        assertEquals(listOf(AdPlacement.SplashInterstitial), vendor.presented)
        assertTrue("Neither flow loads a pager-exit interstitial", vendor.loadAndShows.isEmpty())
    }

    private fun tapRow(list: RecyclerView, index: Int) {
        val adapter = list.adapter as LanguageAdapter
        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, index) }.itemView.performClick()
        pump()
    }

    /** The funnel and every interstitial decision, in order; native ad events are left out. */
    private fun funnel(): List<String> = ScenarioSdk.analytics.mapNotNull { e ->
        when (e) {
            is AnalyticsEvent.LanguageViewed -> "lfo_view ${e.screenIndex}"
            is AnalyticsEvent.LanguageSelected -> "lfo_select ${e.screenIndex} ${e.code}"
            is AnalyticsEvent.LanguageCompleted -> "lfo_complete ${e.screenIndex} ${e.code}"
            is AnalyticsEvent.LanguageFlowCompleted -> "lfo_flow_complete ${e.code}"
            is AnalyticsEvent.LanguageConfirmShown -> "lfo_confirm ${e.code}"
            is AnalyticsEvent.StepViewed -> "step_view ${e.stepId.value}@${e.index}"
            is AnalyticsEvent.StepCompleted -> "step_complete ${e.stepId.value}@${e.index} ${e.exitReason}"
            is AnalyticsEvent.PaywallViewed -> "paywall ${e.placementName}"
            is AnalyticsEvent.FlowCompleted -> "flow_complete ${e.stepsShown}"
            is AnalyticsEvent.AdRequested -> if (e.format == AdFormat.INTERSTITIAL) "inter_request ${e.placementName}" else null
            is AnalyticsEvent.AdSkipped ->
                if (e.format == AdFormat.INTERSTITIAL) "inter_skip ${e.placementName} ${e.reason}" else null
            else -> null
        }
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
