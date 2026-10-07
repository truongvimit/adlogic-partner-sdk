package io.onboardkit.ui.onboarding

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.FakeAdProvider
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
        val binds = mutableMapOf<AdPlacement, Int>()
        val releases = mutableListOf<AdPlacement>()
        val completions = mutableListOf<AnalyticsEvent.StepCompleted>()
        var failOnBind = false
        var pendingOnBind = false
        var interstitialLoads = 0
        var interstitial: ObInterstitialCallback? = null
    }

    @Before fun setup() {
        listeners.clear()
        binds.clear()
        releases.clear()
        completions.clear()
        failOnBind = false
        pendingOnBind = false
        interstitialLoads = 0
        interstitial = null
        val provider = object : FakeAdProvider() {
            override fun bindNative(
                activity: ComponentActivity,
                request: io.onboardkit.ads.NativeAdRequest,
                container: android.widget.FrameLayout,
                listener: AdEventListener,
            ): Boolean {
                listeners[request.placement] = listener
                binds.merge(request.placement, 1, Int::plus)
                if (failOnBind) listener.onFailedToLoad()
                return !failOnBind && !pendingOnBind
            }

            override fun releaseNative(placement: AdPlacement) {
                releases += placement
            }

            override fun loadAndShowInterstitial(
                activity: androidx.appcompat.app.AppCompatActivity,
                placement: AdPlacement,
                unit: InterstitialAdUnit,
                callback: ObInterstitialCallback,
                timeoutMs: Long,
            ) {
                interstitialLoads++
                interstitial = callback
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
        clickAction: NativeClickAction? = null,
        second: StepDefinition = ContentStepDefinition(StepId.OB2, title = "Two"),
        lastOnly: Boolean = false,
        lockSwipe: Boolean = true,
        swipeCompletesLastStep: Boolean = true,
        adsOverride: AdsConfig? = null,
    ) {
        OnboardingSdk.configure(onboardKitConfig {
            step(first)
            if (!lastOnly) {
                step(second)
                step(ContentStepDefinition(StepId.OB4, title = "Three"))
            }
            behavior = BehaviorConfig(lockPagerSwipe = lockSwipe, swipeCompletesLastStep = swipeCompletesLastStep)
            ads = adsOverride ?: AdsConfig(contentStepNative = NativeAdUnit("test-content"),
                fullScreenStepNative = NativeAdUnit("test-fullscreen"),
                afterOnboardingInterstitial = InterstitialAdUnit("test-exit").takeIf { lastOnly })
        }.getOrThrow()).getOrThrow()
        clickAction?.let(::pagerClickAction)
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        main.idle()
        layout()
    }

    private fun pagerClickAction(action: NativeClickAction) {
        val pages = listOf(AdPlacement.StepNative(StepId.OB1), AdPlacement.StepFullScreen(StepId.OB1))
        com.ads.module.config.AdRemoteConfig.update(com.ads.module.config.AdRemoteConfig(mapOf(
            "native_ob1" to com.ads.module.config.AdUnitConfig(listOf("test-content"), true, clickAction = action),
            AdPlacement.StepFullScreen(StepId.OB1).key to com.ads.module.config.AdUnitConfig(listOf("test-fullscreen"), true, clickAction = action),
        )))
        pages.forEach { assertEquals(it.key, action, io.onboardkit.remote.OnboardingSettings.nativeClickAction(it)) }
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

    private fun impress(step: StepId) = requireNotNull(listeners[AdPlacement.StepNative(step)]).onImpression()

    private fun pause() { requireNotNull(controller).pause().stop() }
    private fun resume() { requireNotNull(controller).restart().start().resume() }
    private fun settle() { main.idleFor(2_000, MILLISECONDS) }
    private fun assertOneCompletion(reason: String) {
        assertEquals(1, pager.currentItem)
        assertEquals(listOf(StepId.OB1), completions.map { it.stepId })
        assertEquals(listOf(reason), completions.map { it.exitReason })
    }

    private fun contentPage() = activity.supportFragmentManager.fragments
        .filterIsInstance<ContentStepFragment>().first { it.isResumed }.requireView()

    @Test fun `disabled ads start without an ad slot or a provider bind`() {
        launch(adsOverride = AdsConfig(contentStepNative = null))
        assertNoAdFromStart()
    }

    @Test fun `missing native unit starts without an ad slot or a provider bind`() {
        launch(adsOverride = AdsConfig(contentStepNative = null))
        assertNoAdFromStart()
    }

    @Test fun `disabled remote native placement starts without an ad slot or a provider bind`() {
        com.ads.module.config.AdRemoteConfig.update(com.ads.module.config.AdRemoteConfig(mapOf(
            "native_ob1" to com.ads.module.config.AdUnitConfig(listOf("test"), false),
        )))
        launch(adsOverride = AdsConfig.fromAdConfig())
        assertNoAdFromStart()
    }

    private fun assertNoAdFromStart() {
        val page = contentPage()
        assertEquals(View.GONE, page.findViewById<View>(R.id.ob_ad_block).visibility)
        assertEquals(page.height / 2f, page.findViewById<View>(R.id.ob_step_image).height.toFloat(), 1f)
        assertTrue(listeners.isEmpty())
        assertEquals(0, page.findViewById<android.widget.FrameLayout>(R.id.ob_native_container).childCount)
    }

    @Test fun `synchronous native failure does not insert shimmer after reporting unavailable`() {
        failOnBind = true
        launch()
        val page = contentPage()
        assertEquals(View.GONE, page.findViewById<View>(R.id.ob_ad_block).visibility)
        assertEquals(0, page.findViewById<android.widget.FrameLayout>(R.id.ob_native_container).childCount)
    }

    @Test fun `late native fill restores the same views after loading failed`() {
        pendingOnBind = true
        launch()
        val page = contentPage()
        val image = page.findViewById<View>(R.id.ob_step_image)
        val card = page.findViewById<View>(R.id.ob_step_card)
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.ob_ad_block).visibility)
        listener().onFailedToLoad()
        settle()
        layout()
        assertEquals(View.GONE, page.findViewById<View>(R.id.ob_ad_block).visibility)
        listener().onLoaded()
        settle()
        layout()
        org.junit.Assert.assertSame(image, contentPage().findViewById(R.id.ob_step_image))
        org.junit.Assert.assertSame(card, contentPage().findViewById(R.id.ob_step_card))
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.ob_ad_block).visibility)
        assertEquals("The image runs down to the ad", page.findViewById<View>(R.id.ob_ad_block).top, image.bottom)
        listener().onFailedToLoad()
        settle()
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.ob_ad_block).visibility)
    }

    @Test fun `no ad content moves into the lower panel and restores its ad layout on a late fill`() {
        pendingOnBind = true
        launch()
        fun page() = contentPage()
        fun geometry() = page().let { view ->
            val image = view.findViewById<View>(R.id.ob_step_image)
            val card = view.findViewById<View>(R.id.ob_step_card)
            listOf(image.top, card.height, image.bottom - card.bottom)
        }
        val original = geometry()
        val originalBackground = page().findViewById<View>(R.id.ob_step_card).background
        val originalElevation = page().findViewById<View>(R.id.ob_step_card).elevation
        listener().onFailedToLoad()
        settle()
        layout()
        val noAdPage = page()
        val image = noAdPage.findViewById<View>(R.id.ob_step_image)
        val card = noAdPage.findViewById<View>(R.id.ob_step_card)
        assertEquals(noAdPage.height / 2f, image.height.toFloat(), 1f)
        assertEquals(noAdPage.width, image.width)
        assertEquals(noAdPage.height * .75f - 25 * noAdPage.resources.displayMetrics.density,
            (card.top + card.bottom) / 2f, 1f)
        org.junit.Assert.assertSame(originalBackground, card.background)
        assertEquals(originalElevation, card.elevation, 0f)
        assertTrue(noAdPage.findViewById<View>(R.id.ob_primary_cta).isShown)

        listener().onLoaded()
        settle()
        layout()
        assertEquals(original, geometry())
        assertEquals("The image runs down to the ad", page().findViewById<View>(R.id.ob_ad_block).top,
            page().findViewById<View>(R.id.ob_step_image).bottom)
        org.junit.Assert.assertNotNull(page().findViewById<View>(R.id.ob_step_card).background)
        assertEquals(originalElevation, page().findViewById<View>(R.id.ob_step_card).elevation, 0f)
    }

    @Test fun `reload and none on the pager key leave content in place on click return`() {
        launch()
        for (action in listOf(NativeClickAction.RELOAD, NativeClickAction.NONE)) {
            pagerClickAction(action)
            listener().onClicked()
            listener().onAdOpened()
            pause()
            resume()
            settle()
            assertEquals(action.remoteValue, 0, pager.currentItem)
            assertTrue(completions.isEmpty())
        }
    }

    @Test fun `an ad_config change to auto next reaches the next click on the pager key`() {
        launch(clickAction = NativeClickAction.NONE)
        pagerClickAction(NativeClickAction.AUTO_NEXT)
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
            "native_fs" to com.ads.module.config.AdUnitConfig(listOf("unused_splash_native"), false),
            "native_full1" to com.ads.module.config.AdUnitConfig(listOf("ob_fullscreen"), true),
            "native_ob1" to com.ads.module.config.AdUnitConfig(listOf("content1"), true),
            "native_ob2" to com.ads.module.config.AdUnitConfig(listOf("content2"), true),
            "native_ob3" to com.ads.module.config.AdUnitConfig(listOf("content3"), true, enableUaCheck = false),
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

    @Test fun `swipe enabled keeps OB1 locked even after its ad shows`() {
        launch(lockSwipe = false)
        impress(StepId.OB1)
        assertFalse(pager.isUserInputEnabled)
        flingForward()
        assertEquals(0, pager.currentItem)
        pager.setCurrentItem(1, false)
        layout()
        impress(StepId.OB2)
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `content swipe waits for the first show and a kept ad unlocks it on return`() {
        launch(lockSwipe = false)
        pager.setCurrentItem(1, false)
        layout()
        assertFalse("Load and bind must leave content swipe locked", pager.isUserInputEnabled)
        impress(StepId.OB2)
        assertTrue(pager.isUserInputEnabled)
        pager.setCurrentItem(2, false)
        layout()
        assertFalse("Each visit starts locked", pager.isUserInputEnabled)
        pager.setCurrentItem(1, false)
        layout()
        assertTrue(pager.isUserInputEnabled)
        assertEquals(1, binds[AdPlacement.StepNative(StepId.OB2)])
    }

    @Test fun `content without an ad to show allows swipe at once`() {
        launch(lockSwipe = false, adsOverride = AdsConfig(contentStepNative = null))
        pager.setCurrentItem(1, false)
        layout()
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `content no fill allows swipe`() {
        pendingOnBind = true
        launch(lockSwipe = false)
        pager.setCurrentItem(1, false)
        layout()
        assertFalse(pager.isUserInputEnabled)
        requireNotNull(listeners[AdPlacement.StepNative(StepId.OB2)]).onFailedToLoad()
        settle()
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `content impression cannot override the global swipe lock`() {
        launch()
        pager.setCurrentItem(1, false)
        layout()
        impress(StepId.OB2)
        assertFalse(pager.isUserInputEnabled)
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
        impress(StepId.OB2)
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `remote order changing after language preload cannot remove planned pages before pager entry`() {
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(contentStepNative = NativeAdUnit("content"), fullScreenStepNative = NativeAdUnit("full"))
        }.getOrThrow()).getOrThrow()
        val language = Robolectric.buildActivity(android.app.Activity::class.java).get()
        OnboardingSdk.preload().onLanguageSelected(language)
        io.onboardkit.remote.OnboardingSettings.document.acceptSuccessfulFetch(
            """{"onboarding":{"order":["ob4"]}}""")
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        requireNotNull(controller).setup().visible()
        layout()
        val expected = listOf(StepId.OB1, StepId.FULL1, StepId.OB2, StepId.FULL2, StepId.OB3, StepId.OB4)
        assertEquals(6, pager.adapter!!.itemCount)
        expected.forEachIndexed { index, id ->
            assertEquals(id, activity.stepIdAt(index))
            assertEquals(id, activity.stepDefinition(id)?.id)
        }
        pager.setCurrentItem(2, false)
        layout()
        assertTrue(listeners.containsKey(AdPlacement.StepNative(StepId.OB2)))
    }

    @Test fun `all shown content except OB1 allows swipe regardless of position`() {
        launch(first = ContentStepDefinition(StepId.OB3), second = ContentStepDefinition(StepId.OB1), lockSwipe = false)
        impress(StepId.OB3)
        assertTrue(pager.isUserInputEnabled)
        pager.setCurrentItem(1, false)
        layout()
        impress(StepId.OB1)
        assertFalse(pager.isUserInputEnabled)
        pager.setCurrentItem(2, false)
        layout()
        impress(StepId.OB4)
        assertTrue(pager.isUserInputEnabled)
    }

    @Test fun `fullscreen swipe waits for the first show and a kept ad unlocks it on return`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false), lockSwipe = false)
        assertFalse(pager.isUserInputEnabled)
        listener(true).onLoaded()
        assertFalse("Load and bind must leave fullscreen swipe locked", pager.isUserInputEnabled)
        flingForward()
        assertEquals(0, pager.currentItem)
        listener(true).onImpression()
        assertTrue(pager.isUserInputEnabled)
        pager.setCurrentItem(2, false)
        layout()
        pager.setCurrentItem(0, false)
        layout()
        assertTrue(pager.isUserInputEnabled)
        assertEquals(1, binds[AdPlacement.StepFullScreen(StepId.OB1)])
        assertTrue(releases.isEmpty())
    }

    @Test fun `returning to a kept fullscreen ad restarts its auto next from zero`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000), lockSwipe = false)
        listener(true).onImpression()
        main.idleFor(2_500, MILLISECONDS)
        pager.setCurrentItem(1, false)
        layout()
        main.idleFor(1_000, MILLISECONDS)
        pager.setCurrentItem(0, false)
        layout()
        main.idleFor(2_900, MILLISECONDS)
        assertEquals(0, pager.currentItem)
        assertTrue(completions.isEmpty())
        main.idleFor(100, MILLISECONDS)
        settle()
        assertOneCompletion(StepExit.AUTO_NEXT)
    }

    @Test fun `returning to a fullscreen whose ad failed stays on it with skip`() {
        failOnBind = true
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        settle()
        assertOneCompletion(StepExit.AD_FAILED)
        pager.setCurrentItem(0, false)
        layout()
        settle()
        assertEquals(0, pager.currentItem)
        assertEquals(1, completions.size)
        val page = activity.supportFragmentManager.fragments
            .filterIsInstance<AdStepFragment>().single().requireView()
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.ob_fullscreen_fallback).visibility)
        val skip = page.findViewById<View>(R.id.ob_skip_button)
        assertEquals(View.VISIBLE, skip.visibility)
        skip.performClick()
        settle()
        assertEquals(1, pager.currentItem)
        assertEquals(listOf(StepExit.AD_FAILED, StepExit.AD_FAILED), completions.map { it.exitReason })
    }

    @Test fun `kept natives are released only when the pager is torn down`() {
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextEnabled = false))
        pager.setCurrentItem(1, false)
        layout()
        pager.setCurrentItem(2, false)
        layout()
        assertTrue(releases.isEmpty())
        requireNotNull(controller).pause().stop().destroy()
        main.idle()
        controller = null
        assertTrue(releases.toString(), releases.containsAll(listOf(
            AdPlacement.StepFullScreen(StepId.OB1), AdPlacement.StepNative(StepId.OB2),
            AdPlacement.StepNative(StepId.OB4),
        )))
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

    @Test fun `last content cannot fling past loading but can exit after show`() {
        launch(ContentStepDefinition(StepId.OB4), lastOnly = true, lockSwipe = false)
        flingForward()
        assertEquals(0, interstitialLoads)
        assertTrue(completions.isEmpty())
    }

    @Test fun `last content fling uses the CTA exit interstitial once`() {
        launch(ContentStepDefinition(StepId.OB4), lastOnly = true, lockSwipe = false)
        impress(StepId.OB4)
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
        impress(StepId.OB4)
        assertTrue(pager.isUserInputEnabled)
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
        launch(clickAction = NativeClickAction.NONE)
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
        launch(AdFullScreenStepDefinition(StepId.OB1, autoNextDelayMs = 3000), clickAction = NativeClickAction.NONE)
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

    @Test fun `content ad kept across a revisit stays visible and still arms click return`() {
        launch()
        val ad = listener()
        pager.setCurrentItem(2, false)
        layout()
        pager.setCurrentItem(0, false)
        layout()
        assertEquals(1, binds[AdPlacement.StepNative(StepId.OB1)])
        assertTrue(releases.isEmpty())
        assertEquals(View.VISIBLE, contentPage().findViewById<View>(R.id.ob_ad_block).visibility)
        ad.onClicked()
        pause(); resume(); settle()
        assertOneCompletion(StepExit.AD_CLICK_RETURN)
    }

}
