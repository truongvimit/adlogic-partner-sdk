package io.onboardkit.ui.pool

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.POOL_ADS
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.ads.pool.PoolTestSdk
import io.onboardkit.ads.pool.SYNC_PARITY
import io.onboardkit.ads.pool.installTestEngine
import io.onboardkit.ads.pool.poolSnapshot
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.GoalOption
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.SelectionMode
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.GoalAnswer
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.core.StepId
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.paywall.PaywallGate
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.pool.ObPoolFlowActivity.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Privacy and Goal on the real pool host (decision 1); only the ad vendor and the paywall are faked. */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [PoolGlueViewShadow::class], instrumentedPackages = ["io.onboardkit.ui.pool", "io.onboardkit.ui.widget"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolGoalsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private var controller: ActivityController<ObPoolFlowActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val stage get() = activity.supportFragmentManager.findFragmentByTag("ob_pool_stage")

    private val privacy = AdPlacement.StepNative(StepId.PARTNER_PRIVACY)
    private val privacyAlt = AdPlacement.StepNative(StepId.PARTNER_PRIVACY_ALT)
    private val goal = AdPlacement.StepNative(StepId.PARTNER_GOAL)
    private val goalAlt = AdPlacement.StepNative(StepId.PARTNER_GOAL_ALT)
    private val options = listOf(GoalOption("read", "Read"), GoalOption("edit", "Edit"), GoalOption("sign", "Sign"))

    /** A splash interstitial is buffered; the test closes it. */
    private val exits = mutableListOf<ObInterstitialCallback>()
    private val exitProvider = object : FakeAdProvider() {
        override fun isInterstitialReady(placement: AdPlacement) = placement == AdPlacement.SplashInterstitial

        override fun showInterstitial(activity: Activity, placement: AdPlacement, callback: ObInterstitialCallback) {
            exits += callback
        }
    }
    private var paywalls = 0
    private val outcomes = mutableListOf<OnboardingOutcome>()

    @Before fun setUp() {
        // A running shimmer would move the clock on every frame; paused frames wait for it instead.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
        PoolTestSdk.install(app)
        // install() keeps the provider it was given; this run needs one with a buffered interstitial.
        ReflectionHelpers.setField(OnboardingSdk, "adProvider", exitProvider)
        ReflectionHelpers.setField(OnboardingSdk, "paywallGate", object : PaywallGate {
            override suspend fun shouldShow(placement: PaywallPlacement) = true
            override suspend fun present(activity: Activity, placement: PaywallPlacement): PaywallOutcome {
                paywalls++
                return PaywallOutcome.Dismissed
            }
        })
        OnboardingSdk.setListener { _, outcome -> outcomes += outcome }
        loader = ApFakeLoader()
        engine = installTestEngine(loader)
    }

    @After fun tearDown() {
        // ObInterstitial holds one presentation per process until it ends.
        exits.forEach { it.onAdClosed() }
        pump()
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        ReflectionHelpers.setField(OnboardingSdk, "paywallGate", null)
        PoolTestSdk.tearDown()
        ShadowChoreographer.reset()
    }

    private val selectAds = POOL_ADS.copy(languageNative = null, languageDupNative = null,
        splashInterstitial = InterstitialAdUnit("splash-inter"))

    private fun configure(minSelection: Int = 2, mode: SelectionMode = SelectionMode.MULTIPLE, ads: AdsConfig = selectAds) {
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            this.ads = ads
            privacyGoalsScreen = PrivacyGoalsScreenConfig(
                enabled = true,
                goal = GoalsScreenConfig(options = options, selectionMode = mode, minSelection = minSelection),
            )
        }.getOrThrow()).getOrThrow()
    }

    private fun open(
        target: Stage,
        snapshot: NativeFlowSnapshot = poolSnapshot(),
        minSelection: Int = 2,
        mode: SelectionMode = SelectionMode.MULTIPLE,
        ads: AdsConfig = selectAds,
        beforeOpen: () -> Unit = {},
    ) {
        configure(minSelection, mode, ads)
        OnboardingSdk.session.begin(null)
        OnboardingSdk.session.nativeFlow = snapshot
        OnboardingPool.activate(app, snapshot)
        main.idle()
        beforeOpen()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        pump()
        activity.goTo(target)
        pump()
    }

    /** Sync messages wait behind a traversal barrier until its frame runs; a few frames let both through. */
    private fun pump() {
        main.idle()
        repeat(4) { main.idleFor(FRAME_MS, MILLISECONDS) }
    }

    private fun idleUntil(uptimeMs: Long) {
        val wait = uptimeMs - SystemClock.uptimeMillis()
        if (wait > 0) main.idleFor(wait, MILLISECONDS)
    }

    private val consent get() = activity.findViewById<CheckBox>(R.id.ob_privacy_consent_checkbox)
    private val forward get() = activity.findViewById<View>(R.id.ob_privacy_goals_continue)
    private val adSlot get() = activity.findViewById<FrameLayout>(R.id.ob_privacy_goals_ad)

    private fun shownAd() = (adSlot.getChildAt(0) as? NativeAdView)?.let { Shadow.extract<PoolGlueViewShadow>(it).registered }

    private fun tap(position: Int) {
        val list = activity.findViewById<RecyclerView>(R.id.ob_goal_options)
        requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.performClick()
        pump()
    }

    /** A configuration change in the same process: the saved state comes back to a new instance. */
    private fun recreate() {
        val state = Bundle()
        requireNotNull(controller).pause().saveInstanceState(state).stop().destroy()
        pump()
        controller = Robolectric.buildActivity(ObPoolFlowActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup(state)
        pump()
    }

    private fun backgroundAndReturn() {
        requireNotNull(controller).pause().stop()
        pump()
        requireNotNull(controller).start().resume()
        pump()
    }

    @Test fun `Privacy claims its first ad politely and the second forcibly on every consent change`() {
        open(Stage.PRIVACY)
        assertEquals(listOf("content"), loader.callsFor(privacy.key))
        assertEquals("The resume retry found its own chain closing the gate", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(privacy))
        assertEquals(privacy.key, engine.owner)
        assertTrue(adSlot.getChildAt(0) is ShimmerFrameLayout)
        assertFalse(forward.isEnabled)

        consent.performClick()
        assertTrue(consent.isChecked)
        assertEquals("FORCED past the gate privacy's chain closed", LoadResult.Requested, OnboardingPool.lastResult(privacyAlt))
        assertEquals(privacyAlt.key, engine.owner)
        assertTrue(forward.isEnabled)

        consent.performClick()
        assertFalse(forward.isEnabled)
        assertTrue("In flight: no second request", OnboardingPool.lastResult(privacyAlt) is LoadResult.NoOpState)
        assertEquals(listOf("content"), loader.callsFor(privacyAlt.key))

        loader.exhaust(privacyAlt.key)
        consent.performClick()
        assertEquals("LoadError: requested again", listOf("content", "content"), loader.callsFor(privacyAlt.key))
        val alt = loader.fill(privacyAlt.key)
        pump()
        assertSame(alt.admobNativeAd, shownAd())
        consent.performClick()
        assertTrue("Held: no request", OnboardingPool.lastResult(privacyAlt) is LoadResult.NoOpState)
        assertEquals(2, loader.callsFor(privacyAlt.key).size)
    }

    @Test fun `D-BIND - a select screen binds a fill landing under a translucent window once it resumes`() {
        open(Stage.PRIVACY)
        requireNotNull(controller).pause()
        pump()
        val ad = loader.fill(privacy.key)
        pump()
        assertTrue("Nothing bound under the cover", adSlot.getChildAt(0) is ShimmerFrameLayout)
        requireNotNull(controller).resume()
        pump()
        assertSame(ad.admobNativeAd, shownAd())
    }

    @Test fun `screen_slots_bind_started binds a select screen's fill while it is only started`() {
        open(Stage.GOAL, snapshot = poolSnapshot(SYNC_PARITY.copy(screenSlotsBindStarted = true)))
        requireNotNull(controller).pause()
        pump()
        val goalAd = loader.fill(goal.key)
        pump()
        assertSame("screen_slots_bind_started: DashDrama's STARTED collector", goalAd.admobNativeAd, shownAd())
    }

    @Test fun `the forward button stays hidden until a fixed time after the first selection`() {
        open(Stage.PRIVACY)
        assertEquals(View.GONE, forward.visibility)
        val firstAt = SystemClock.uptimeMillis()
        consent.performClick()
        pump()
        consent.performClick()
        consent.performClick()
        idleUntil(firstAt + 2_999)
        assertEquals(View.GONE, forward.visibility)
        idleUntil(firstAt + 3_000)
        assertEquals("Counted from the first selection", View.VISIBLE, forward.visibility)
        requireNotNull(controller).pause().stop().destroy()
        pump()

        open(Stage.GOAL, snapshot = poolSnapshot().copy(doneHiddenGone = false))
        assertEquals(View.INVISIBLE, forward.visibility)
        val tapAt = SystemClock.uptimeMillis()
        tap(0)
        idleUntil(tapAt + 2_999)
        assertEquals(View.INVISIBLE, forward.visibility)
        idleUntil(tapAt + 3_000)
        assertEquals(View.VISIBLE, forward.visibility)
    }

    @Test fun `Goal claims its first ad politely, the second forcibly on every tap, and Next needs minSelection`() {
        open(Stage.GOAL)
        assertEquals(listOf("content"), loader.callsFor(goal.key))
        assertEquals(goal.key, engine.owner)
        assertFalse(forward.isEnabled)

        tap(0)
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(goalAlt))
        assertEquals(goalAlt.key, engine.owner)
        assertFalse("1 of 2", forward.isEnabled)
        tap(1)
        assertTrue(forward.isEnabled)
        assertTrue(OnboardingPool.lastResult(goalAlt) is LoadResult.NoOpState)
        tap(1)
        assertFalse("Deselecting is a selection event too", forward.isEnabled)
        assertEquals(listOf("content"), loader.callsFor(goalAlt.key))

        loader.exhaust(goalAlt.key)
        tap(2)
        assertEquals(listOf("content", "content"), loader.callsFor(goalAlt.key))
        assertTrue(forward.isEnabled)
    }

    @Test fun `a select screen whose first ad is refused still shows the second one it owns`() {
        val altOnly = selectAds.copy(contentStepNative = null,
            stepNatives = mapOf(StepId.PARTNER_GOAL_ALT to NativeAdUnit("goal-alt")))
        open(Stage.GOAL, ads = altOnly)
        assertTrue(OnboardingPool.lastResult(goal) is LoadResult.SkippedByConfig)
        assertEquals(View.GONE, adSlot.visibility)

        tap(0)
        assertEquals(LoadResult.Requested, OnboardingPool.lastResult(goalAlt))
        assertEquals(goalAlt.key, engine.owner)
        assertTrue(adSlot.getChildAt(0) is ShimmerFrameLayout)
        val alt = loader.fill(goalAlt.key)
        pump()
        assertEquals(View.VISIBLE, adSlot.visibility)
        assertSame(alt.admobNativeAd, shownAd())
    }

    @Test fun `a single-choice Goal keeps one pick`() {
        open(Stage.GOAL, minSelection = 1, mode = SelectionMode.SINGLE)
        tap(0)
        tap(1)
        main.idleFor(3_000, MILLISECONDS)
        forward.performClick()
        pump()
        assertEquals(listOf("edit"), OnboardingSdk.session.goals.map { it.id })
        assertTrue((stage as PoolGoalFragment).blocksAppResume)
    }

    @Test fun `back in front a select screen restores its owner, refreshes it and retries its last claim politely`() {
        open(Stage.GOAL)
        loader.fill(goal.key)
        tap(0)
        loader.fill(goalAlt.key)
        pump()
        assertEquals(goalAlt.key, engine.owner)
        OnboardingPool.setOwner(AdPlacement.ResumeNative)

        backgroundAndReturn()
        assertEquals(goalAlt.key, engine.owner)
        assertEquals("refresh(alt) ran", listOf("content", "content"), loader.callsFor(goalAlt.key))
        assertEquals("POLITE after the refresh closed the gate", LoadResult.SkippedByGate,
            OnboardingPool.lastResult(goalAlt))
        assertEquals(listOf("content"), loader.callsFor(goal.key))
    }

    @Test fun `back does nothing on Privacy and Goal`() {
        open(Stage.PRIVACY)
        activity.onBackPressedDispatcher.onBackPressed()
        pump()
        assertEquals(Stage.PRIVACY, activity.stage)
        assertFalse(activity.isFinishing)

        activity.goTo(Stage.GOAL)
        pump()
        activity.onBackPressedDispatcher.onBackPressed()
        pump()
        assertEquals(Stage.GOAL, activity.stage)
        assertFalse(activity.isFinishing)
    }

    @Test fun `Privacy Continue opens Goal, which claims its own first ad`() {
        open(Stage.PRIVACY)
        loader.fill(privacy.key)
        consent.performClick()
        loader.fill(privacyAlt.key)
        main.idleFor(3_000, MILLISECONDS)
        assertEquals(View.VISIBLE, forward.visibility)

        forward.performClick()
        assertNull("Only a full page is warmed a screen ahead", OnboardingPool.lastResult(goal))
        assertEquals(privacyAlt.key, engine.owner)
        pump()
        assertEquals(Stage.GOAL, activity.stage)
        assertTrue(stage is PoolGoalFragment)
        assertEquals(goal.key, engine.owner)
        assertEquals("The Goal's own claim requests it", listOf("content"), loader.callsFor(goal.key))
    }

    @Test fun `Goal Next records the picks and completes once after the leftover splash interstitial, with no paywall`() {
        open(Stage.GOAL)
        tap(0)
        tap(2)
        main.idleFor(3_000, MILLISECONDS)
        forward.performClick()
        forward.performClick()
        pump()
        assertEquals(listOf(GoalAnswer("read", "Read"), GoalAnswer("sign", "Sign")), OnboardingSdk.session.goals.toList())
        assertEquals("skips=${PoolTestSdk.skipped()}", 1, exits.size)
        assertTrue(activity.resumeBlockedByScreen)
        assertTrue("AFTER_AD: nothing ends before the ad closes", outcomes.isEmpty())

        exits.single().onNextAction()
        pump()
        assertTrue(outcomes.isEmpty())
        assertFalse(activity.isFinishing)

        exits.single().onAdClosed()
        pump()
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is OnboardingOutcome.Completed)
        assertTrue(activity.isFinishing)
        assertEquals(0, paywalls)
        exits.single().onAdClosed()
        pump()
        assertEquals(1, outcomes.size)
    }

    @Test fun `a host recreated under the Goal interstitial completes the run once`() {
        val events = mutableListOf<OnboardingEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { OnboardingSdk.events.collect { events += it } }
        try {
            open(Stage.GOAL)
            tap(0)
            tap(2)
            main.idleFor(3_000, MILLISECONDS)
            forward.performClick()
            pump()
            assertEquals(1, exits.size)
            exits.single().onNextAction()

            recreate()
            assertEquals(Stage.GOAL, activity.stage)
            assertFalse("Next stays off on the restored screen", forward.isEnabled)
            assertTrue((stage as PoolGoalFragment).blocksAppResume)
            assertTrue(activity.resumeBlockedByScreen)
            forward.performClick()
            pump()
            assertEquals("No second exit", 1, exits.size)
            assertTrue(outcomes.isEmpty())

            exits.single().onAdClosed()
            pump()
            assertEquals(1, outcomes.size)
            assertTrue(outcomes.single() is OnboardingOutcome.Completed)
            assertTrue("The new host ends the run", activity.isFinishing)
            assertEquals(0, paywalls)
            assertEquals(1, events.count { it is OnboardingEvent.GoalsSelected })
            assertEquals(listOf("read", "sign"), OnboardingSdk.session.goals.map { it.id })
        } finally {
            job.cancel()
        }
    }

    @Test fun `selections survive recreation`() {
        open(Stage.PRIVACY)
        consent.performClick()
        recreate()
        assertEquals(Stage.PRIVACY, activity.stage)
        assertTrue(consent.isChecked)
        assertTrue(forward.isEnabled)
        assertEquals(View.VISIBLE, forward.visibility)

        activity.goTo(Stage.GOAL)
        pump()
        tap(1)
        tap(2)
        recreate()
        assertEquals(Stage.GOAL, activity.stage)
        val list = activity.findViewById<RecyclerView>(R.id.ob_goal_options)
        val selected = (0 until options.size).map { requireNotNull(list.findViewHolderForAdapterPosition(it)).itemView.isSelected }
        assertEquals(listOf(false, true, true), selected)
        assertTrue(forward.isEnabled)
        forward.performClick()
        pump()
        assertEquals(listOf("edit", "sign"), OnboardingSdk.session.goals.map { it.id })
    }

    @Test fun `OC3 a late FULL1 fill shows on Goal by rule 2`() {
        val full1 = AdPlacement.StepFullScreen(StepId.FULL1)
        open(Stage.GOAL) {
            assertEquals(LoadResult.Requested, OnboardingPool.claim(full1, LoadMode.POLITE))
        }
        assertEquals(LoadResult.SkippedByGate, OnboardingPool.lastResult(goal))
        assertEquals(goal.key, engine.owner)
        assertTrue(adSlot.getChildAt(0) is ShimmerFrameLayout)

        val ad = loader.fill(full1.key)
        pump()
        assertSame(ad.admobNativeAd, shownAd())
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
