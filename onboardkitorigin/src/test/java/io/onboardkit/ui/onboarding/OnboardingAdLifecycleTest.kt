package io.onboardkit.ui.onboarding

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BehaviorConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.AnalyticsPlugin
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.ui.pager.LazyStepFragment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Real Activity/FragmentStateAdapter transactions; only the ad vendor is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    instrumentedPackages = ["io.onboardkit.ui.onboarding"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingAdLifecycleTest {
    private var controller: ActivityController<ObOnboardingHostActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val pager get() = activity.findViewById<ViewPager2>(R.id.ob_step_pager)
    private val main get() = shadowOf(Looper.getMainLooper())

    private companion object {
        val listeners = mutableMapOf<AdPlacement, AdEventListener>()
        val completions = mutableListOf<AnalyticsEvent.StepCompleted>()
        var failOnBind = false
        var interstitialLoads = 0
        var interstitial: ObInterstitialCallback? = null
    }

    @Before fun setup() {
        listeners.clear()
        completions.clear()
        failOnBind = false
        interstitialLoads = 0
        interstitial = null
        val provider = Mockito.mock(OnboardingAdProvider::class.java) { call ->
            when (call.method.name) {
                "nativeClickAction" -> io.onboardkit.remote.OnboardingSettings.nativeClickAction(call.getArgument(0))
                "bindNative" -> {
                    call.getArgument<AdEventListener?>(4)?.let {
                        listeners[call.getArgument(1)] = it
                        if (failOnBind) it.onFailedToLoad()
                    }
                    true
                }
                "loadAndShowInterstitial" -> {
                    interstitialLoads++
                    interstitial = call.getArgument(3)
                    null
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        OnboardingSdk.install(ApplicationProvider.getApplicationContext()) {
            adProvider = provider
            trackkitAutoTracking(false)
            analyticsPlugin(AnalyticsPlugin { event ->
                if (event is AnalyticsEvent.StepCompleted) completions += event
            })
        }
        ConsentCenter.setHostConsent(true, false)
        OnboardingSdk.setCanRequestAds(true)
        runBlocking { OnboardingSdk.reset() }
    }

    @After fun cleanup() {
        interstitial?.onAdSkipped(AdSkipReason.NOT_READY)
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.clearHostConsent()
        com.ads.module.config.AdRemoteConfig.reset()
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun launch(
        first: StepDefinition = ContentStepDefinition(StepId.OB1, title = "One"),
        clickReturn: Boolean = true,
        second: StepDefinition = ContentStepDefinition(StepId.OB2, title = "Two"),
        lastOnly: Boolean = false,
        lockSwipe: Boolean = true,
        swipeCompletesLastStep: Boolean = true,
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            step(first)
            if (!lastOnly) {
                step(second)
                step(ContentStepDefinition(StepId.OB4, title = "Three"))
            }
            behavior = BehaviorConfig(adClickReturnCompletesStep = clickReturn,
                lockPagerSwipe = lockSwipe, swipeCompletesLastStep = swipeCompletesLastStep)
            ads = AdsConfig(contentStepNative = NativeAdUnit("test-content"),
                fullScreenStepNative = NativeAdUnit("test-fullscreen"),
                afterOnboardingInterstitial = InterstitialAdUnit("test-exit").takeIf { lastOnly })
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        main.idle()
        layout()
    }

    private fun layout() {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        main.idle()
    }

    private fun listener(fullscreen: Boolean = false) = requireNotNull(listeners[
        if (fullscreen) AdPlacement.StepFullScreen(StepId.OB1) else AdPlacement.StepNative(StepId.OB1)
    ])

    private fun pause() { requireNotNull(controller).pause().stop() }
    private fun resume() { requireNotNull(controller).restart().start().resume() }
    private fun settle() { main.idleFor(2_000, MILLISECONDS) }
    private fun assertOneCompletion(reason: String) {
        assertEquals(1, pager.currentItem)
        assertEquals(listOf(StepId.OB1), completions.map { it.stepId })
        assertEquals(listOf(reason), completions.map { it.exitReason })
    }

    @Test fun `reload and none override legacy auto advance on content click return`() {
        launch(clickReturn = true)
        for (action in listOf("reload", "none")) {
            io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"behavior":{"click":{"action":"$action"}}}}}}""")
            listener().onClicked()
            listener().onAdOpened()
            pause()
            resume()
            settle()
            assertEquals(action, 0, pager.currentItem)
            assertTrue(completions.isEmpty())
        }
    }

    @Test fun `explicit auto next overrides disabled legacy navigation and enabled reload`() {
        launch(clickReturn = false)
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"steps":{"ob1":{"behavior":{"click":{"action":"auto_next"},"reload":{"on_ad_click":true}}}}}}""")
        listener().onClicked()
        pause()
        resume()
        settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    private fun flingForward() {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(0L, MotionEvent.ACTION_DOWN, 900f),
            Triple(20L, MotionEvent.ACTION_MOVE, 650f),
            Triple(40L, MotionEvent.ACTION_MOVE, 400f),
            Triple(60L, MotionEvent.ACTION_UP, 100f)).forEach { (time, action, x) ->
            MotionEvent.obtain(start, start + time, action, x, 500f, 0).also {
                activity.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        // Distinct gestures must not share timestamps or become a GestureDetector double tap.
        main.idleFor(400, MILLISECONDS)
    }

    @Test fun `full1 binds fullscreen then OB3 content binds its own native with splash native off`() {
        com.ads.module.config.AdRemoteConfig.update(com.ads.module.config.AdRemoteConfig(mapOf(
            "native_fs" to com.ads.module.config.AdUnitConfig("unused_splash_native", false),
            "native_full1" to com.ads.module.config.AdUnitConfig("ob_fullscreen", true),
            "native_ob1" to com.ads.module.config.AdUnitConfig("content1", true),
            "native_ob2" to com.ads.module.config.AdUnitConfig("content2", true),
            "native_ob3" to com.ads.module.config.AdUnitConfig("content3", true, enableUaCheck = false),
        )))
        OnboardingSdk.configure(onboardKitConfig { defaultSteps() }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        layout()
        assertEquals(5, pager.adapter!!.itemCount)
        pager.setCurrentItem(1, false)
        layout()
        assertTrue(listeners.containsKey(AdPlacement.StepFullScreen(StepId.FULL1)))
        pager.setCurrentItem(3, false)
        layout()
        assertTrue(listeners.containsKey(AdPlacement.StepNative(StepId.OB3)))
        assertEquals(listOf("content3"), OnboardingSdk.requireConfig().ads.nativeUnitFor(AdPlacement.StepNative(StepId.OB3))!!.loadOrder)
        assertEquals(View.VISIBLE, activity.supportFragmentManager.fragments
            .filterIsInstance<ContentStepFragment>().first { it.isResumed }.requireView()
            .findViewById<View>(R.id.ob_ad_block).visibility)
    }

    @Test fun `swipe enabled keeps OB1 locked and unlocks OB2`() {
        launch(lockSwipe = false)
        assertFalse(pager.isUserInputEnabled)
        flingForward()
        assertEquals(0, pager.currentItem)
        pager.setCurrentItem(1, false)
        layout()
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `late remote order does not remove or reorder pages in a running pager`() {
        launch(lockSwipe = false)
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch("""{"onboarding":{"order":["ob4"]}}""")
        activity.rebuildPendingPages()
        assertEquals(3, pager.adapter!!.itemCount)
        assertEquals(StepId.OB1, activity.stepDefinition(StepId.OB1)?.id)
        assertFalse(pager.isUserInputEnabled)
        pager.setCurrentItem(1, false)
        layout()
        assertEquals(StepId.OB2, activity.stepDefinition(StepId.OB2)?.id)
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `all middle content except OB1 allows swipe regardless of position`() {
        launch(first = ContentStepDefinition(StepId.OB3), second = ContentStepDefinition(StepId.OB1), lockSwipe = false)
        assertTrue(pager.isUserInputEnabled)
        pager.setCurrentItem(1, false)
        layout()
        assertFalse(pager.isUserInputEnabled)
        pager.setCurrentItem(2, false)
        layout()
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `fullscreen swipe requires successful show on each visit`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false), lockSwipe = false)
        assertFalse(pager.isUserInputEnabled)
        listener(true).onLoaded()
        assertFalse("Load and bind must leave fullscreen swipe locked", pager.isUserInputEnabled)
        flingForward()
        assertEquals(0, pager.currentItem)
        listener(true).onImpression()
        assertTrue(pager.isUserInputEnabled)
        val oldAd = listener(true)
        pager.setCurrentItem(1, false)
        layout()
        pager.setCurrentItem(0, false)
        layout()
        assertFalse(pager.isUserInputEnabled)
        oldAd.onImpression()
        assertFalse(pager.isUserInputEnabled)
        listener(true).onImpression()
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `last fullscreen cannot fling past loading but can exit after show`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false),
            lastOnly = true, lockSwipe = false)
        flingForward()
        assertEquals(0, interstitialLoads)
        assertTrue(completions.isEmpty())
        listener(true).onImpression()
        assertTrue(pager.isUserInputEnabled)
        assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
        flingForward()
        assertEquals(1, interstitialLoads)
        assertEquals(listOf(StepExit.SWIPE), completions.map { it.exitReason })
    }

    @Test fun `last content fling uses the CTA exit interstitial once`() {
        launch(ContentStepDefinition(StepId.OB4), lastOnly = true, lockSwipe = false)
        flingForward()
        flingForward()
        assertEquals(1, interstitialLoads)
        assertEquals(listOf(StepExit.SWIPE), completions.map { it.exitReason })
    }

    @Test fun `disabled swipe cannot complete last content through window detector`() {
        launch(ContentStepDefinition(StepId.OB4), lastOnly = true)
        flingForward()
        assertEquals(0, interstitialLoads)
        assertTrue(completions.isEmpty())
    }

    @Test fun `fullscreen success cannot override the global swipe lock`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        listener(true).onImpression()
        assertFalse(pager.isUserInputEnabled)
    }

    @Test fun `fullscreen failure locks swipe before queued automatic completion`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false), lockSwipe = false)
        listener(true).onImpression()
        assertTrue(pager.isUserInputEnabled)
        listener(true).onFailedToLoad()
        assertFalse(pager.isUserInputEnabled)
        settle()
        assertOneCompletion(StepExit.AD_FAILED)
    }

    @Test fun `fullscreen X still advances while loading keeps swipe locked`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false), lockSwipe = false)
        main.idleFor(5_000, MILLISECONDS)
        assertFalse(pager.isUserInputEnabled)
        activity.findViewById<View>(R.id.ob_skip_close).performClick()
        settle()
        assertOneCompletion(StepExit.SKIP)
    }

    @Test fun `last page completion flag can disable the exit fling`() {
        launch(ContentStepDefinition(StepId.OB4), lastOnly = true,
            lockSwipe = false, swipeCompletesLastStep = false)
        flingForward()
        assertEquals(0, interstitialLoads)
        assertTrue(completions.isEmpty())
    }

    @Test fun `click only vendor returns and advances once`() {
        launch()
        listener().onClicked()
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `open only vendor returns and advances once`() {
        launch()
        listener().onAdOpened()
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `duplicate click and open callbacks do not double advance`() {
        launch()
        listener().onClicked()
        listener().onAdOpened()
        listener().onClicked()
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `home or another fullscreen ad does not complete untouched content`() {
        launch()
        repeat(2) { pause(); resume(); settle() }
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `failed ad departure is disarmed by the next page touch`() {
        launch()
        listener().onClicked()
        activity.supportFragmentManager.fragments.filterIsInstance<LazyStepFragment>()
            .first { it.isResumed }.onWindowTouched()
        pause(); resume(); settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `callback arriving after pause cannot arm a later unrelated return`() {
        launch()
        pause()
        listener().onAdOpened()
        resume(); settle()
        pause(); resume(); settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `disabled click return leaves content in place`() {
        launch(clickReturn = false)
        listener().onClicked()
        pause(); resume(); settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `CTA wins against a queued click return without skipping the next page`() {
        launch()
        listener().onClicked()
        pause(); resume()
        activity.next(StepExit.CTA)
        settle()
        assertOneCompletion(StepExit.CTA)
    }

    @Test fun `queued click return cannot complete a later visit to the same page`() {
        launch()
        listener().onClicked()
        pause(); resume()
        pager.setCurrentItem(1, false)
        pager.setCurrentItem(0, false)
        settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `queued click return is discarded when its activity is destroyed`() {
        launch()
        listener().onClicked()
        pause(); resume()
        requireNotNull(controller).pause().stop().destroy()
        controller = null
        settle()
        assertTrue(completions.isEmpty())
    }

    @Test fun `fullscreen foreground timeout advances once`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000), lockSwipe = false)
        assertFalse(pager.isUserInputEnabled)
        main.idleFor(2_500, MILLISECONDS)
        assertEquals(0, pager.currentItem)
        main.idleFor(500, MILLISECONDS)
        settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen timeout still counts background time and return cannot advance again`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000))
        main.idleFor(500, MILLISECONDS)
        listener(true).onClicked()
        pause()
        main.idleFor(2_500, MILLISECONDS)
        assertOneCompletion(StepExit.AUTO_NEXT)
        resume(); settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen expired deadline catches up safely during resume`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000))
        pause()
        // Advance the clock without running the timer: Android may suspend the process/CPU.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        resume(); settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen click return wins once when its deadline also expired`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000))
        listener(true).onClicked()
        listener(true).onAdOpened()
        pause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `fullscreen click return before timeout cancels the old timer`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000))
        listener(true).onAdOpened()
        pause(); resume()
        main.idleFor(5_000, MILLISECONDS)
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `fullscreen manual mode still allows click return`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        listener(true).onClicked()
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `disabled click return does not disable fullscreen deadline catchup`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000), clickReturn = false)
        listener(true).onClicked()
        pause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        resume(); settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen synchronous no fill completes safely even with auto next disabled`() {
        failOnBind = true
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        settle()
        assertOneCompletion(StepExit.AD_FAILED)
    }

    @Test fun `queued fullscreen completion cannot complete a new visit to its source page`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        activity.completeAdStep(StepId.OB1, StepExit.AD_FAILED)
        pager.setCurrentItem(1, false)
        pager.setCurrentItem(0, false)
        settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `no fill while settling onto fullscreen waits then skips exactly that page`() {
        failOnBind = true
        launch(second = AdFullScreenStepDefinition(StepId.OB2, autoNextEnabled = false))
        activity.next(StepExit.CTA)
        layout()
        assertEquals(ViewPager2.SCROLL_STATE_SETTLING, pager.scrollState)
        assertEquals(listOf(StepId.OB1), completions.map { it.stepId })
        // Robolectric does not draw the smooth-scroll frames; finish at the same target.
        pager.setCurrentItem(1, false)
        layout()
        settle()
        assertEquals("state=${pager.scrollState}, listeners=${listeners.keys}, " +
            "completions=${completions.map { it.stepId }}", 2, pager.currentItem)
        assertEquals(listOf(StepId.OB1, StepId.OB2), completions.map { it.stepId })
        assertEquals(listOf(StepExit.CTA, StepExit.AD_FAILED), completions.map { it.exitReason })
    }

    @Test fun `zero duration fullscreen entering during a scroll completes without reentrant transactions`() {
        launch(second = AdFullScreenStepDefinition(StepId.OB2, autoNextDelayMs = 0))
        activity.next(StepExit.CTA)
        layout()
        assertEquals(ViewPager2.SCROLL_STATE_SETTLING, pager.scrollState)
        assertEquals(listOf(StepId.OB1), completions.map { it.stepId })
        pager.setCurrentItem(1, false)
        layout()
        settle()
        assertEquals("state=${pager.scrollState}, listeners=${listeners.keys}, " +
            "completions=${completions.map { it.stepId }}", 2, pager.currentItem)
        assertEquals(listOf(StepId.OB1, StepId.OB2), completions.map { it.stepId })
        assertEquals(listOf(StepExit.CTA, StepExit.AUTO_NEXT), completions.map { it.exitReason })
    }

    @Test fun `last fullscreen completes in background but exit interstitial waits for resume`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000), lastOnly = true)
        listener(true).onClicked()
        pause()
        main.idleFor(4_000, MILLISECONDS)
        assertEquals(listOf(StepExit.AUTO_NEXT), completions.map { it.exitReason })
        assertEquals(0, interstitialLoads)
        assertFalse(activity.isFinishing)
        resume(); settle()
        assertEquals(1, interstitialLoads)
        requireNotNull(interstitial).onNextAction()
        assertFalse(activity.isFinishing)
        requireNotNull(interstitial).onAdClosed()
        requireNotNull(interstitial).onAdClosed()
        settle()
        assertTrue(activity.isFinishing)
        assertEquals(1, completions.size)
    }

    @Test fun `last content click return and interstitial lifecycle fallback finish only once`() {
        launch(lastOnly = true)
        listener().onClicked()
        pause(); resume(); settle()
        assertEquals(listOf(StepExit.AD_CLICK_RETURN), completions.map { it.exitReason })
        assertEquals(1, interstitialLoads)
        requireNotNull(interstitial).onNextAction()
        pause(); resume(); settle()
        assertTrue(activity.isFinishing)
        requireNotNull(interstitial).onAdClosed()
        settle()
        assertEquals(1, completions.size)
        assertEquals(1, interstitialLoads)
    }

    @Test fun `old content ad callback cannot arm a new visit`() {
        launch()
        val oldAd = listener()
        pager.setCurrentItem(1, false)
        layout()
        pager.setCurrentItem(0, false)
        layout()
        oldAd.onClicked()
        pause(); resume(); settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }

    @Test fun `old fullscreen no fill cannot complete a new visit`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        val oldAd = listener(true)
        pager.setCurrentItem(1, false)
        layout()
        pager.setCurrentItem(0, false)
        layout()
        oldAd.onFailedToLoad()
        settle()
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
    }
}
