package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.POOL_ADS
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.PoolTestSdk
import io.onboardkit.ads.pool.SYNC_PARITY
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.ads.pool.lastRegisteredView
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.widget.ObPrimaryButton
import io.onboardkit.ui.widget.ObStepIndicator
import kotlinx.coroutines.CoroutineDispatcher
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.coroutines.CoroutineContext

/** The real pool host on its pager stage; only the ad vendor behind the pool is faked. */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider. The pager package is instrumented for its dwell clock.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [PoolGlueViewShadow::class], instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.pager"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolPagerTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private var controller: ActivityController<ObPoolFlowActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val stage get() = activity.supportFragmentManager.findFragmentByTag("ob_pool_stage")
    private val pagerStage get() = stage as PoolPagerFragment
    private val pager get() = activity.findViewById<ViewPager2>(R.id.ob_pool_pager)
    private val back get() = activity.findViewById<View>(R.id.ob_pool_step_back)
    private val now get() = SystemClock.uptimeMillis()

    private val ob1 = ContentStepDefinition(StepId.OB1, title = "One")
    private val ob2 = ContentStepDefinition(StepId.OB2, title = "Two")
    private val ob3 = ContentStepDefinition(StepId.OB3, title = "Three")
    private val full1 = AdFullScreenStepDefinition(StepId.FULL1)
    private val stepOb1 = AdPlacement.StepNative(StepId.OB1)
    private val stepOb2 = AdPlacement.StepNative(StepId.OB2)
    private val stepOb3 = AdPlacement.StepNative(StepId.OB3)
    private val fullPage = AdPlacement.StepFullScreen(StepId.FULL1)

    /** No LFO units: whatever the LANGUAGE stage claims on its way through is a config refusal. */
    private val pagerAds = POOL_ADS.copy(languageNative = null, languageDupNative = null)

    @Before fun setUp() {
        // A running shimmer would move the clock on every frame; paused frames wait for it instead.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
        // Animation time never rewinds: a test that ended mid-slide would freeze every slide here
        // until the reset clock caught up with it.
        AnimationUtils.currentAnimationTimeMillis()
        PoolTestSdk.install(app)
        loader = ApFakeLoader()
        engine = installTestEngine(loader)
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        ReflectionHelpers.setField(OnboardingSdk, "paywallGate", null)
        Locale.setDefault(Locale.US)
        PoolTestSdk.tearDown()
        ShadowChoreographer.reset()
    }

    private fun configure(steps: List<StepDefinition>, ads: AdsConfig) {
        OnboardingSdk.configure(onboardKitConfig {
            steps.forEach(::step)
            this.ads = ads
        }.getOrThrow()).getOrThrow()
    }

    /** Starts a pooled run and opens its pager stage; [beforeOpen] runs once the pool is live. */
    private fun launch(
        steps: List<StepDefinition> = listOf(ob1, full1, ob2),
        ads: AdsConfig = pagerAds,
        snapshot: NativeFlowSnapshot = poolSnapshot(),
        settings: String? = null,
        beforeOpen: () -> Unit = {},
    ) {
        OnboardingSettings.document.acceptSuccessfulFetch(settings)
        configure(steps, ads)
        OnboardingSdk.session.begin(null)
        OnboardingSdk.session.nativeFlow = snapshot
        OnboardingPool.activate(app, snapshot)
        main.idle()
        beforeOpen()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        pump()
        activity.goTo(ObPoolFlowActivity.Stage.PAGER)
        pump()
    }

    /** Sync messages wait behind a traversal barrier until its frame runs; a few frames let both through. */
    private fun pump() {
        main.idle()
        repeat(4) { main.idleFor(FRAME_MS, MILLISECONDS) }
    }

    /** The user taps again once the slide has ended. */
    private fun settle(pager: ViewPager2) {
        var frames = 0
        do main.idleFor(FRAME_MS, MILLISECONDS)
        while (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE && ++frames < 1_000)
        pump()
    }

    private fun idleUntil(uptimeMs: Long) {
        val wait = uptimeMs - now
        if (wait > 0) main.idleFor(wait, MILLISECONDS)
    }

    private fun nextPage() {
        activity.next(StepExit.CTA)
        settle(pager)
    }

    private var paywalls = 0

    private fun countPaywalls() {
        ReflectionHelpers.setField(OnboardingSdk, "paywallGate", object : PaywallGate {
            override suspend fun shouldShow(placement: PaywallPlacement) = true
            override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
                paywalls++
                return PaywallOutcome.Dismissed
            }
        })
    }

    private fun backgroundAndReturn() {
        requireNotNull(controller).pause().stop()
        pump()
        requireNotNull(controller).start().resume()
        pump()
    }

    private fun destroy() {
        requireNotNull(controller).pause().stop().destroy()
        pump()
        controller = null
    }

    private fun page(position: Int): Fragment = pagerStage.childFragmentManager.fragments
        .first { it.arguments?.getInt("ob_arg_position", -1) == position }

    private fun slot(position: Int): FrameLayout = page(position).requireView().findViewById(R.id.ob_native_container)

    private fun shownAd(position: Int) = (slot(position).getChildAt(0) as? NativeAdView)
        ?.let { Shadow.extract<PoolGlueViewShadow>(it).registered }

    private fun skip(position: Int): View = page(position).requireView().findViewById(R.id.ob_skip_button)

    /** When the pager selects [position], on the clock. */
    private fun selectionTime(position: Int): () -> Long {
        var at = -1L
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(selected: Int) {
                if (selected == position && at < 0) at = now
            }
        })
        return { at }
    }

    private fun completed() = PoolTestSdk.events<AnalyticsEvent.StepCompleted>()

    private fun completedAs(stepId: StepId) = completed().single { it.stepId == stepId }.exitReason

    @Test fun `a selection claims its page and preloads only a full next page, without making it the owner`() {
        launch(steps = listOf(ob1, full1, ob2)) {
            OnboardingPool.claim(stepOb1, LoadMode.FORCED)
            loader.fill(stepOb1.key)
        }
        assertEquals(0, pager.currentItem)
        assertEquals("The full page is warmed one screen ahead", LoadResult.Requested, OnboardingPool.lastResult(fullPage))
        assertEquals(stepOb1.key, engine.owner)
        loader.fill(fullPage.key)
        pump()

        nextPage()
        assertEquals(1, pager.currentItem)
        assertTrue("Its preload filled it: no second request", OnboardingPool.lastResult(fullPage) is LoadResult.NoOpState)
        assertEquals(fullPage.key, engine.owner)
        assertNull("A content next page is never preloaded", OnboardingPool.lastResult(stepOb2))
        assertTrue(loader.callsFor(stepOb2.key).isEmpty())

        nextPage()
        assertEquals(2, pager.currentItem)
        assertEquals("A content page asks for its own ad when it is selected", LoadResult.Requested,
            OnboardingPool.lastResult(stepOb2))
        assertEquals(stepOb2.key, engine.owner)
    }

    @Test fun `a full page whose preload is still loading shows the newest ad and requests nothing more`() {
        var newest: ApNativeAd? = null
        launch(steps = listOf(ob1, full1, ob2)) {
            OnboardingPool.claim(stepOb1, LoadMode.FORCED)
            newest = loader.fill(stepOb1.key)
        }
        pump()
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(fullPage))
        val callsBefore = loader.callsFor(fullPage.key).size

        nextPage()
        assertEquals(1, pager.currentItem)
        assertEquals("Its own preload is in flight: the claim is a gate skip", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(fullPage))
        assertEquals(callsBefore, loader.callsFor(fullPage.key).size)
        assertEquals(fullPage.key, engine.owner)
        pump()
        assertSame("Until its own fill lands the full page shows the newest ad", newest?.admobNativeAd, shownAd(1))

        val own = loader.fill(fullPage.key)
        pump()
        assertSame(own.admobNativeAd, shownAd(1))
    }

    @Test fun `an offscreen content page holds its ad's height with a still skeleton, then binds once it resumes`() {
        launch(steps = listOf(ob1, ob2)) {
            OnboardingPool.claim(stepOb1, LoadMode.FORCED)
        }
        val ad = loader.fill(stepOb1.key)
        pump()
        assertFalse(page(1).isResumed)
        val holder = slot(1)
        assertEquals("Laid out at the ad's height before its slide", View.VISIBLE, holder.visibility)
        val still = holder.getChildAt(0) as ShimmerFrameLayout
        assertFalse("A page nobody sees does not animate", still.isShimmerStarted)
        assertEquals(View.VISIBLE, page(1).requireView().findViewById<View>(R.id.ob_ad_block).visibility)

        nextPage()
        assertTrue(page(1).isResumed)
        assertSame("Rule 2 binds the newest ad over the placeholder", ad.admobNativeAd, shownAd(1))
    }

    @Test fun `a page without a slot keeps the previous owner and draws nothing`() {
        val ads = pagerAds.copy(contentStepNative = null,
            stepNatives = mapOf(StepId.OB1 to NativeAdUnit("ob1"), StepId.OB3 to NativeAdUnit("ob3")))
        launch(steps = listOf(ob1, ob2, ob3), ads = ads)
        val ad = loader.fill(stepOb1.key)
        pump()
        assertSame(ad.admobNativeAd, shownAd(0))
        assertNull("Nothing to preload for a slot-less next page", OnboardingPool.lastResult(stepOb2))

        nextPage()
        assertEquals(1, pager.currentItem)
        assertNull(OnboardingPool.lastResult(stepOb2))
        assertEquals(stepOb1.key, engine.owner)
        assertEquals("OB2 borrows nothing", 0, slot(1).childCount)

        backgroundAndReturn()
        assertEquals(stepOb1.key, engine.owner)
        assertNull("The retried claim is still OB1's", OnboardingPool.lastResult(stepOb2))
        assertEquals(0, slot(1).childCount)
    }

    @Test fun `a page planned with a slot keeps it when the config later drops its unit`() {
        launch(steps = listOf(ob1, ob2, ob3))
        val ad = loader.fill(stepOb1.key)
        pump()
        assertSame(ad.admobNativeAd, shownAd(0))
        configure(listOf(ob1, ob2, ob3), pagerAds.copy(contentStepNative = null,
            stepNatives = mapOf(StepId.OB1 to NativeAdUnit("ob1"), StepId.OB3 to NativeAdUnit("ob3"))))

        nextPage()
        assertTrue("Claimed as planned, and refused", OnboardingPool.lastResult(stepOb2) is LoadResult.SkippedByConfig)
        assertEquals(stepOb1.key, engine.owner)
        assertNull("OB2 hides instead of borrowing OB1's ad", shownAd(1))
    }

    @Test fun `a page planned without a slot claims nothing when the config later gives it a unit`() {
        val ads = pagerAds.copy(contentStepNative = null,
            stepNatives = mapOf(StepId.OB1 to NativeAdUnit("ob1"), StepId.OB3 to NativeAdUnit("ob3")))
        launch(steps = listOf(ob1, ob2, ob3), ads = ads)
        loader.fill(stepOb1.key)
        pump()
        configure(listOf(ob1, ob2, ob3), pagerAds)

        nextPage()
        assertNull(OnboardingPool.lastResult(stepOb2))
        assertEquals(stepOb1.key, engine.owner)
        assertEquals(0, slot(1).childCount)
    }

    @Test fun `swipe never unlocks, even once a page's ad has shown`() {
        launch(steps = listOf(ob1, ob2, ob3))
        val ad = loader.fill(stepOb1.key)
        pump()
        loader.impress(ad)
        pump()
        assertSame(ad.admobNativeAd, shownAd(0))
        assertFalse(pager.isUserInputEnabled)
        nextPage()
        assertFalse(pager.isUserInputEnabled)
    }

    @Test fun `every page stays alive unless a smaller offscreen limit is configured`() {
        launch(steps = listOf(ob1, full1, ob2, ob3))
        assertEquals(4, pager.offscreenPageLimit)
        assertEquals(4, pagerStage.childFragmentManager.fragments.size)
        destroy()

        launch(steps = listOf(ob1, full1, ob2, ob3), snapshot = poolSnapshot().copy(pagerOffscreenPages = 1))
        assertEquals(1, pager.offscreenPageLimit)
    }

    @Test fun `back in front the stage takes its owner back, refreshes it and retries its page politely`() {
        launch(steps = listOf(ob1, ob2, ob3))
        loader.fill(stepOb1.key)
        pump()
        assertEquals(stepOb1.key, engine.owner)
        OnboardingPool.setOwner(AdPlacement.ResumeNative)

        backgroundAndReturn()
        assertEquals(stepOb1.key, engine.owner)
        assertEquals("refresh(step_ob1) ran", listOf("content", "content"), loader.callsFor(stepOb1.key))
        assertEquals("POLITE after the refresh closed the gate", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(stepOb1))
    }

    @Test fun `leaving the stage clears the owner only while it is the stage's own`() {
        launch(steps = listOf(ob1, ob2))
        assertEquals(stepOb1.key, engine.owner)
        destroy()
        assertNull(engine.owner)

        launch(steps = listOf(ob1, ob2))
        assertEquals(stepOb1.key, engine.owner)
        OnboardingPool.setOwner(AdPlacement.ResumeNative)
        destroy()
        assertEquals(AdPlacement.ResumeNative.key, engine.owner)
    }

    @Test fun `content pages read the step count and the last CTA through the resolver`() {
        launch(steps = listOf(ob1, ob2, ob3))
        assertEquals(3, activity.poolTotalSteps())
        (0..2).forEach { position ->
            val view = page(position).requireView()
            val indicator = view.findViewById<ObStepIndicator>(R.id.ob_step_indicator)
            assertEquals(3, indicator.count)
            assertEquals(position, indicator.selectedIndex)
            val expected = if (position == 2) ObPrimaryButton.State.LAST else ObPrimaryButton.State.NEXT
            assertEquals(expected, view.findViewById<ObPrimaryButton>(R.id.ob_primary_cta).state)
        }
    }

    @Test fun `a full page shows X at the skip delay after it first resumes and X skips it`() {
        launch()
        var resumedAt = -1L
        page(1).lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && resumedAt < 0) resumedAt = now
        })
        nextPage()
        assertEquals(1, pager.currentItem)
        assertTrue(resumedAt > 0)
        assertTrue("Shimmer while OB1's chain runs", slot(1).getChildAt(0) is ShimmerFrameLayout)
        idleUntil(resumedAt + 1_999)
        assertEquals(View.GONE, skip(1).visibility)
        idleUntil(resumedAt + 2_000)
        assertEquals(View.VISIBLE, skip(1).visibility)

        skip(1).performClick()
        pump()
        assertEquals(2, pager.currentItem)
        assertEquals(StepExit.SKIP, completedAs(StepId.FULL1))
    }

    @Test fun `auto-next counts from selection and fires on time in front`() {
        launch()
        val selectedAt = selectionTime(1)
        nextPage()
        idleUntil(selectedAt() + 9_999)
        assertEquals(1, pager.currentItem)
        idleUntil(selectedAt() + 10_000)
        pump()
        assertEquals(2, pager.currentItem)
        assertEquals(StepExit.AUTO_NEXT, completedAs(StepId.FULL1))
    }

    @Test fun `auto-next counted in the background moves the page only once the user is back`() {
        launch()
        val selectedAt = selectionTime(1)
        nextPage()
        idleUntil(selectedAt() + 4_000)
        requireNotNull(controller).pause().stop()
        idleUntil(selectedAt() + 10_000)
        pump()
        main.idleFor(5_000, MILLISECONDS)
        assertEquals("Waits in the background", 1, pager.currentItem)
        assertTrue(completed().none { it.stepId == StepId.FULL1 })

        requireNotNull(controller).start()
        pump()
        assertEquals("Moves at STARTED, as DD's collector", 2, pager.currentItem)
        requireNotNull(controller).resume()
        settle(pager)
        assertEquals(2, pager.currentItem)
        assertEquals(StepExit.AUTO_NEXT, completedAs(StepId.FULL1))
        assertEquals(1, completed().count { it.stepId == StepId.FULL1 })
    }

    @Test fun `a last full page's auto-next ends the flow only once the task is back in front`() {
        countPaywalls()
        launch(steps = listOf(ob1, full1))
        val selectedAt = selectionTime(1)
        nextPage()
        requireNotNull(controller).pause().stop()
        idleUntil(selectedAt() + 10_000)
        pump()
        assertTrue(completed().none { it.stepId == StepId.FULL1 })
        assertEquals(0, paywalls)
        assertTrue(PoolTestSdk.events<AnalyticsEvent.FlowCompleted>().isEmpty())

        requireNotNull(controller).start()
        pump()
        assertEquals(StepExit.AUTO_NEXT, completedAs(StepId.FULL1))
        assertEquals("No paywall before RESUMED", 0, paywalls)
        assertTrue(PoolTestSdk.events<AnalyticsEvent.FlowCompleted>().isEmpty())
        assertTrue(activity.resumeBlockedByScreen)
        assertFalse(activity.isFinishing)

        requireNotNull(controller).resume()
        pump()
        assertEquals(1, paywalls)
        assertEquals(1, PoolTestSdk.events<AnalyticsEvent.FlowCompleted>().size)
        assertTrue(activity.isFinishing)
    }

    @Test fun `a completion that arrives mid-slide waits for idle and moves exactly one page`() {
        launch()
        activity.next(StepExit.CTA)
        assertEquals(1, pager.currentItem)
        activity.completeAdStep(StepId.FULL1, StepExit.AD_FAILED)
        activity.completeAdStep(StepId.FULL1, StepExit.SKIP)
        repeat(2) { main.idleFor(FRAME_MS, MILLISECONDS) }
        assertTrue("Still sliding onto the full page", pager.scrollState != ViewPager2.SCROLL_STATE_IDLE)
        assertEquals(1, pager.currentItem)
        assertTrue(completed().none { it.stepId == StepId.FULL1 })

        settle(pager)
        assertEquals("The first completion moved on once idle", 2, pager.currentItem)
        settle(pager)
        assertEquals("The second belonged to the visit that ended", 2, pager.currentItem)
        assertEquals(listOf(StepExit.AD_FAILED), completed().filter { it.stepId == StepId.FULL1 }.map { it.exitReason })
        assertTrue(completed().none { it.stepId == StepId.OB2 })
    }

    @Test fun `a full page left with nothing to show skips itself as ad_failed`() {
        launch()
        loader.exhaust(stepOb1.key)
        pump()
        nextPage()
        assertEquals("Warmed FORCED from page 0, its own claim finds it loading", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(fullPage))
        assertEquals(1, loader.callsFor(fullPage.key).size)
        assertEquals(1, pager.currentItem)
        assertTrue(slot(1).getChildAt(0) is ShimmerFrameLayout)

        loader.exhaust(fullPage.key)
        pump()
        assertEquals(2, pager.currentItem)
        assertEquals(StepExit.AD_FAILED, completedAs(StepId.FULL1))
    }

    @Test fun `an offscreen no-fill does not skip a full page whose own claim is loading`() {
        engine.close()
        // Dispatchers.Default in production: the new selection reaches the slots after the page's selection.
        val late = object : CoroutineDispatcher() {
            private val handler = Handler(Looper.getMainLooper())
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                handler.postDelayed(block, 1)
            }
        }
        val parity = SYNC_PARITY.copy(asyncSelector = true, pagerSlotsBindResumed = false)
        engine = NativePoolEngine(loader, PoolRequestPolicy.ALLOW_ALL, parity, SystemPoolClock,
            MainLooperPoolScheduler(), PoolExpiry { 0L }, late).also(SharedNativePool::installForTest)
        launch(snapshot = poolSnapshot(parity))
        loader.exhaust(stepOb1.key)
        // The FORCED warm-up of page 1 fails too, so nothing is left loading offscreen.
        loader.exhaust(fullPage.key)
        pump()
        assertEquals("The offscreen full page saw nothing to show", View.GONE, slot(1).visibility)

        nextPage()
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(fullPage))
        assertEquals(1, pager.currentItem)
        assertTrue(completed().none { it.stepId == StepId.FULL1 })
        assertTrue(slot(1).getChildAt(0) is ShimmerFrameLayout)

        val ad = loader.fill(fullPage.key)
        pump()
        assertSame(ad.admobNativeAd, shownAd(1))
        assertEquals(1, pager.currentItem)
    }

    @Test fun `a full page refused by its own config skips itself as ad_failed`() {
        launch()
        // The pages are frozen; the unit goes away before the user reaches the full page.
        configure(listOf(ob1, full1, ob2), pagerAds.copy(fullScreenStepNative = null))
        activity.next(StepExit.CTA)
        assertTrue(OnboardingPool.lastResult(fullPage) is LoadResult.SkippedByConfig)
        assertEquals("The refusal took no ownership", stepOb1.key, engine.owner)
        settle(pager)
        assertEquals(2, pager.currentItem)
        assertEquals(StepExit.AD_FAILED, completedAs(StepId.FULL1))
    }

    @Test fun `app resume is vetoed on a full page and allowed on a resting content page`() {
        launch()
        assertFalse(activity.resumeBlockedByScreen)
        nextPage()
        assertTrue(activity.resumeBlockedByScreen)
        main.idleFor(2_000, MILLISECONDS)
        skip(1).performClick()
        pump()
        assertEquals(2, pager.currentItem)
        assertTrue("Blocked while the pager slides", activity.resumeBlockedByScreen)
        settle(pager)
        assertFalse(activity.resumeBlockedByScreen)
    }

    @Test fun `dwell on a full page counts from its selection`() {
        launch()
        val selectedAt = selectionTime(1)
        nextPage()
        idleUntil(selectedAt() + 2_500)
        skip(1).performClick()
        pump()
        val dwell = completed().single { it.stepId == StepId.FULL1 }.dwellMs
        assertTrue("dwell=$dwell", dwell in 2_400L..2_600L)
    }

    @Test fun `step analytics match the classic pager for the same taps`() {
        val steps = listOf(ob1, ob2, ob3)
        configure(steps, pagerAds)
        OnboardingSdk.session.begin(null)
        val classic = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        classic.get().setTheme(R.style.ob_Theme_OnboardKit)
        classic.setup().visible()
        pump()
        repeat(3) {
            classic.get().next(StepExit.CTA)
            settle(classic.get().findViewById(R.id.ob_step_pager))
        }
        val classicViewed = stepsViewed()
        val classicCompleted = stepsCompleted()
        runCatching { classic.pause().stop().destroy() }
        PoolTestSdk.analytics.clear()

        launch(steps = steps, snapshot = poolSnapshot())
        repeat(3) {
            activity.next(StepExit.CTA)
            settle(pager)
        }
        assertEquals(listOf("ob1@0:TEMPLATE_1", "ob2@1:TEMPLATE_1", "ob3@2:TEMPLATE_1"), classicViewed)
        assertEquals(classicViewed, stepsViewed())
        assertEquals(listOf("ob1@0:cta", "ob2@1:cta", "ob3@2:cta"), classicCompleted)
        assertEquals(classicCompleted, stepsCompleted())
        assertEquals(listOf(StepId.OB1, StepId.OB2, StepId.OB3), OnboardingSdk.session.stepsShown.toList())
    }

    private fun stepsViewed() = PoolTestSdk.events<AnalyticsEvent.StepViewed>().map { "${it.stepId.value}@${it.index}:${it.variant}" }

    private fun stepsCompleted() = completed().map { "${it.stepId.value}@${it.index}:${it.exitReason}" }

    @Test fun `the OB1 back returns to the LANGUAGE stage with the pick`() {
        OnboardingSdk.session.selectedLanguage = "es"
        launch()
        assertEquals(View.VISIBLE, back.visibility)
        assertEquals(1f, back.alpha)

        back.performClick()
        pump()
        assertEquals(ObPoolFlowActivity.Stage.LANGUAGE, activity.stage)
        val language = stage as PoolLanguageFragment
        val args = requireNotNull(language.arguments)
        assertTrue("The LFO comes back with es: $args", args.keySet().any { args.getString(it) == "es" })
        assertFalse(activity.isFinishing)
    }

    @Test fun `the OB1 back shows on page 0 only and follows its remote switch`() {
        launch()
        assertEquals(View.VISIBLE, back.visibility)
        nextPage()
        assertEquals(View.GONE, back.visibility)
        destroy()

        // A jump onto a laid-out page may report no scroll at all.
        launch(snapshot = poolSnapshot().copy(pagerSmoothScroll = false))
        assertEquals(View.VISIBLE, back.visibility)
        activity.next(StepExit.CTA)
        assertEquals(View.GONE, back.visibility)
        destroy()

        launch(settings = """{"onboarding":{"steps":{"ob1":{"back":{"enabled":false}}}}}""")
        assertEquals(View.GONE, back.visibility)
        back.performClick()
        pump()
        assertEquals(ObPoolFlowActivity.Stage.PAGER, activity.stage)
    }

    @Test fun `with pager_slots_bind_resumed off the page on screen keeps its ad's registration`() {
        launch(steps = listOf(ob1, ob2, ob3), snapshot = poolSnapshot(SYNC_PARITY.copy(pagerSlotsBindResumed = false)))
        val first = loader.fill(stepOb1.key)
        pump()
        assertSame("Offscreen pages bind too", first.admobNativeAd, shownAd(2))
        assertSame("The page on screen holds the registration", slot(0).getChildAt(0),
            lastRegisteredView[first.admobNativeAd])

        nextPage()
        assertEquals(1, pager.currentItem)
        assertSame(first.admobNativeAd, shownAd(1))
        assertSame("The page now on screen took it", slot(1).getChildAt(0), lastRegisteredView[first.admobNativeAd])

        val second = loader.fill(stepOb2.key)
        pump()
        assertSame(second.admobNativeAd, shownAd(1))
        assertSame(second.admobNativeAd, shownAd(2))
        assertSame(slot(1).getChildAt(0), lastRegisteredView[second.admobNativeAd])
    }

    @Test fun `after reset() mid-run the pages still render the pool and a full page waits for its ad`() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        configure(listOf(ob1, full1, ob2), pagerAds)
        OnboardingSdk.session.begin(null)
        val flow = poolSnapshot()
        OnboardingSdk.session.nativeFlow = flow
        OnboardingPool.activate(app, flow)
        main.idle()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        pump()
        runBlocking { OnboardingSdk.reset() }
        pump()
        assertFalse(OnboardingPool.isActive)

        activity.goTo(ObPoolFlowActivity.Stage.PAGER)
        pump()
        val ad = loader.fill(stepOb1.key)
        pump()
        assertSame("Page 0 still renders the pool's pick", ad.admobNativeAd, shownAd(0))

        nextPage()
        assertEquals(1, pager.currentItem)
        assertEquals("Warmed FORCED from page 0, its own claim finds it loading", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(fullPage))
        assertTrue("The full page borrows while its own claim loads", completed().none { it.stepId == StepId.FULL1 })
        assertSame(ad.admobNativeAd, shownAd(1))
    }

    @Test fun `OC1 a late LFO2 fill shows on page 0`() {
        launch(steps = listOf(ob1, ob2), ads = POOL_ADS) {
            OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        }
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(stepOb1))
        assertEquals(stepOb1.key, engine.owner)
        assertTrue(slot(0).getChildAt(0) is ShimmerFrameLayout)

        val language2 = loader.fill(AdPlacement.Language2.key)
        pump()
        assertSame(language2.admobNativeAd, shownAd(0))
    }

    @Test fun `OC2 with LFO2 loading at Done page 0 is gate-skipped and shows LFO1 then LFO2`() {
        // The fill itself, not the loader's latest attempt: the real LFO stage requests again
        // (its resume refresh) after this ad filled, and that attempt holds no ad.
        var language1: ApNativeAd? = null
        launch(steps = listOf(ob1, ob2), ads = POOL_ADS) {
            OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
            language1 = loader.fill(AdPlacement.Language1.key).also(loader::impress)
            OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
            assertEquals("Done's page-0 preload", LoadResult.SkippedByGate, OnboardingPool.preload(stepOb1))
        }
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(stepOb1))
        assertEquals(stepOb1.key, engine.owner)
        assertSame("Rule 3: the showing LFO1", language1?.admobNativeAd, shownAd(0))

        val language2 = loader.fill(AdPlacement.Language2.key)
        pump()
        assertSame("Rule 2: the newest loaded", language2.admobNativeAd, shownAd(0))
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
