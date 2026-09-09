package io.onboardkit.ui.onboarding

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
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
        controller?.pause()?.stop()?.destroy()
        main.idle()
        ConsentCenter.clearHostConsent()
    }

    private fun launch(
        first: StepDefinition = ContentStepDefinition(StepId.OB1, title = "One"),
        clickReturn: Boolean = true,
        second: StepDefinition = ContentStepDefinition(StepId.OB2, title = "Two"),
        lastOnly: Boolean = false,
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            step(first)
            if (!lastOnly) {
                step(second)
                step(ContentStepDefinition(StepId.OB4, title = "Three"))
            }
            behavior = BehaviorConfig(adClickReturnCompletesStep = clickReturn)
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
        launch(AdFullScreenStepDefinition(StepId.OB1))
        main.idleFor(2_500, MILLISECONDS)
        assertEquals(0, pager.currentItem)
        main.idleFor(500, MILLISECONDS)
        settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen timeout still counts background time and return cannot advance again`() {
        launch(AdFullScreenStepDefinition(StepId.OB1))
        main.idleFor(500, MILLISECONDS)
        listener(true).onClicked()
        pause()
        main.idleFor(2_500, MILLISECONDS)
        assertOneCompletion(StepExit.AUTO_NEXT)
        resume(); settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen expired deadline catches up safely during resume`() {
        launch(AdFullScreenStepDefinition(StepId.OB1))
        pause()
        // Advance the clock without running the timer: Android may suspend the process/CPU.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        resume(); settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `fullscreen click return wins once when its deadline also expired`() {
        launch(AdFullScreenStepDefinition(StepId.OB1))
        listener(true).onClicked()
        listener(true).onAdOpened()
        pause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

    @Test fun `fullscreen click return before timeout cancels the old timer`() {
        launch(AdFullScreenStepDefinition(StepId.OB1))
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
        launch(AdFullScreenStepDefinition(StepId.OB1), clickReturn = false)
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
        launch(AdFullScreenStepDefinition(StepId.OB1), lastOnly = true)
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
