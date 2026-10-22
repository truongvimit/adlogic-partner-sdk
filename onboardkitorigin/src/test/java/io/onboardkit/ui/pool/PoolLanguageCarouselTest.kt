package io.onboardkit.ui.pool

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.NativePoolEngine
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.ApFakeLoader
import io.onboardkit.ads.pool.LFO1
import io.onboardkit.ads.pool.LFO2
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolGlueViewShadow
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit.MILLISECONDS

/** The pooled LFO's two-page carousel in its real host; only the ad loader is a fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ui.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolLanguageCarouselTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private var controller: ActivityController<ObPoolFlowActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val block get() = activity.findViewById<FrameLayout>(R.id.ob_ad_block)
    private val carousel get() = block.getChildAt(0) as? RecyclerView
    private val autoScrollMs get() = OnboardingSettings.carouselAutoScrollMs()

    @Before fun setUp() {
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
    }

    private fun switches(lfo1: Boolean, lfo2: Boolean = lfo1) {
        AdRemoteConfig.update(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig(LFO1.tiers, true, carousel = lfo1),
            "native_lang_alt" to AdUnitConfig(LFO2.tiers, true, carousel = lfo2),
        )))
    }

    private fun launch(lfo1: Boolean = true, lfo2: Boolean = lfo1) {
        switches(lfo1, lfo2)
        controller = launchPoolFlow(app).also { it.visible().windowFocusChanged(true) }
        main.idle()
    }

    private fun fill(key: String): ApNativeAd = loader.fill(key).also { main.idle() }

    private fun calls(key: String) = loader.callsFor(key).size

    private fun page(index: Int): ViewGroup =
        requireNotNull(carousel?.layoutManager?.findViewByPosition(index) as? ViewGroup) { "page $index not laid out" }

    private fun shownOn(index: Int) = (page(index).getChildAt(0) as FrameLayout).shownAd()

    private fun loadingOn(index: Int) = page(index).getChildAt(1).visibility == View.VISIBLE

    private fun shownPage(): Int {
        val lm = carousel!!.layoutManager as LinearLayoutManager
        return if (lm.findFirstVisibleItemPosition() == 0 && lm.findLastVisibleItemPosition() == 1) {
            // Both pages peek: the one wider on screen is the one shown.
            val first = lm.findViewByPosition(0)!!
            if (first.right > carousel!!.width / 2) 0 else 1
        } else lm.findFirstVisibleItemPosition()
    }

    private fun swipe(towardsSecond: Boolean) {
        val rv = requireNotNull(carousel)
        val y = rv.height / 2f
        val start = SystemClock.uptimeMillis()
        val (from, to) = if (towardsSecond) 0.9f to 0.1f else 0.1f to 0.9f
        fun event(action: Int, x: Float, at: Long) =
            MotionEvent.obtain(start, start + at, action, x, y, 0).also { rv.dispatchTouchEvent(it); it.recycle() }
        event(MotionEvent.ACTION_DOWN, rv.width * from, 0)
        for (step in 1..10) event(MotionEvent.ACTION_MOVE, rv.width * (from + (to - from) * step / 10f), step * 16L)
        event(MotionEvent.ACTION_UP, rv.width * to, 176)
        main.idleFor(1_000, MILLISECONDS)
    }

    /** LFO1 on page 0 and page 1's own ad, both filled. */
    private fun bothFilled(): Pair<ApNativeAd, ApNativeAd> {
        launch()
        val first = fill("language1")
        val second = fill("language1_carousel")
        return first to second
    }

    @Test fun `without the switch the pooled LFO keeps its single native`() {
        launch(lfo1 = false)
        fill("language1")
        assertNull(carousel)
        assertEquals(0, calls("language1_carousel"))
    }

    @Test fun `page 1 waits for LFO1's load, then loads its own key POLITE on LFO1's units`() {
        launch()
        assertNotNull(carousel)
        assertEquals("LFO1 goes first; page 1 never jumps the gate", 0, calls("language1_carousel"))
        val first = fill("language1")
        assertEquals(listOf("lfo1-high"), loader.callsFor("language1_carousel"))
        val second = fill("language1_carousel")
        assertSame(first.admobNativeAd, shownOn(0))
        assertSame(second.admobNativeAd, shownOn(1))
    }

    @Test fun `page 1 borrows the pool's newest unshown ad instead of requesting`() {
        launch()
        OnboardingPool.preload(AdPlacement.StepNative(StepId.OB1), LoadMode.FORCED)
        val lfo1 = fill("language1")
        val borrowed = fill("step_ob1")
        assertSame(lfo1.admobNativeAd, shownOn(0))
        assertSame(borrowed.admobNativeAd, shownOn(1))
        assertEquals(0, calls("language1_carousel"))
    }

    @Test fun `auto-scroll alternates the pages every interval once both show an ad`() {
        bothFilled()
        assertEquals(0, shownPage())
        main.idleFor(autoScrollMs + 500, MILLISECONDS)
        assertEquals(1, shownPage())
        main.idleFor(autoScrollMs, MILLISECONDS)
        assertEquals(0, shownPage())
    }

    @Test fun `auto-scroll stays on page 0 while page 1 has no ad`() {
        launch()
        fill("language1")
        loader.exhaust("language1_carousel")
        main.idle()
        main.idleFor(autoScrollMs * 3, MILLISECONDS)
        assertEquals(0, shownPage())
    }

    @Test fun `auto-scroll onto a borrowing page loads its own key POLITE`() {
        launch()
        OnboardingPool.preload(AdPlacement.StepNative(StepId.OB1), LoadMode.FORCED)
        fill("language1")
        fill("step_ob1")
        assertEquals(0, calls("language1_carousel"))
        main.idleFor(autoScrollMs + 500, MILLISECONDS)
        assertEquals(1, shownPage())
        assertEquals(1, calls("language1_carousel"))
    }

    @Test fun `a swipe refreshes the page it lands on under the loading view, the old ad until the new one`() {
        val (_, old) = bothFilled()
        swipe(towardsSecond = true)
        assertEquals(1, shownPage())
        assertEquals(2, calls("language1_carousel"))
        assertTrue(loadingOn(1))
        assertSame(old.admobNativeAd, shownOn(1))
        val fresh = fill("language1_carousel")
        assertSame(fresh.admobNativeAd, shownOn(1))
        assertEquals(false, loadingOn(1))
    }

    @Test fun `the loading skeleton covers exactly the page's card, not its margins`() {
        bothFilled()
        swipe(towardsSecond = true)
        val card = page(1).getChildAt(0)
        val loading = page(1).getChildAt(1)
        assertEquals(View.VISIBLE, loading.visibility)
        assertEquals(card.left, loading.left)
        assertEquals(card.width, loading.width)
        assertEquals(card.top, loading.top)
        assertEquals(card.height, loading.height)
        assertNull("No backdrop of its own around the skeleton", loading.background)
    }

    @Test fun `with the carousel the rows run under the ad block, padded by its height`() {
        bothFilled()
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        val root = list.parent as View
        assertEquals(root.height, list.bottom)
        assertEquals(block.height, list.paddingBottom)
        assertTrue(block.height > 0)
    }

    @Test fun `without the carousel the rows still end above the ad block`() {
        launch(lfo1 = false)
        fill("language1")
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        assertTrue(list.bottom <= block.top)
        assertEquals(0, list.paddingBottom)
    }

    @Test fun `a failed swipe reload keeps the page's old ad`() {
        val (_, old) = bothFilled()
        swipe(towardsSecond = true)
        loader.exhaust("language1_carousel")
        main.idle()
        assertSame(old.admobNativeAd, shownOn(1))
        assertEquals(false, loadingOn(1))
    }

    @Test fun `however the user swipes, only one ad loads at a time`() {
        val (first, _) = bothFilled()
        val before = loader.attempts.size
        swipe(towardsSecond = true)
        swipe(towardsSecond = false)
        swipe(towardsSecond = true)
        swipe(towardsSecond = false)
        assertEquals("Only page 1's reload went out", before + 1, loader.attempts.size)
        assertEquals(0, shownPage())
        assertSame("Page 0 keeps its ad while page 1 loads", first.admobNativeAd, shownOn(0))
        val fresh = fill("language1_carousel")
        assertSame("Page 1's fill lands on page 1", fresh.admobNativeAd, shownOn(1))
        assertSame(first.admobNativeAd, shownOn(0))
    }

    @Test fun `back from the background page 1 renews its own key like the LFO key would`() {
        bothFilled()
        swipe(towardsSecond = true)
        fill("language1_carousel")
        val language1Calls = calls("language1")
        val carouselCalls = calls("language1_carousel")
        requireNotNull(controller).pause().stop().restart().start().resume()
        main.idle()
        assertEquals(carouselCalls + 1, calls("language1_carousel"))
        assertEquals(language1Calls, calls("language1"))
    }

    @Test fun `the first pick moves page 1 to LFO2's key and it keeps its ad until that key fills`() {
        val (_, second) = bothFilled()
        activity.tapLanguage(1)
        assertSame(second.admobNativeAd, shownOn(1))
        fill("language2")
        main.idleFor(autoScrollMs + 500, MILLISECONDS)
        assertEquals(1, shownPage())
        assertEquals("Auto-scroll asks LFO2's carousel key POLITE", 1, calls("language2_carousel"))
    }

    @Test fun `LFO2 with the switch off takes the carousel down`() {
        launch(lfo1 = true, lfo2 = false)
        fill("language1")
        val loadingCard = activity.findViewById<View>(R.id.ob_language_loading)
        loadingCard.visibility = View.VISIBLE
        activity.tapLanguage(1)
        assertNull(carousel)
        assertEquals(View.VISIBLE, block.visibility)
        val list = activity.findViewById<RecyclerView>(R.id.ob_language_list)
        main.idle()
        assertTrue("The rows end above the single native again", list.bottom <= block.top)
        assertEquals(0, list.paddingBottom)
        assertEquals("Taking the carousel down leaves every other view as it is", View.VISIBLE, loadingCard.visibility)
    }

    @Test fun `page 1 reports as the LFO placement, its requests, binds and paid events name language1`() {
        bothFilled()
        val requested = PoolUiSdk.events<io.onboardkit.core.analytics.AnalyticsEvent.AdRequested>().map { it.placementName }
        assertEquals(listOf("language1", "language1"), requested)
        val bound = PoolUiSdk.events<io.onboardkit.core.analytics.AnalyticsEvent.AdImpression>().map { it.placementName }
        assertEquals(listOf("language1", "language1"), bound)
        val request = requireNotNull(io.onboardkit.ads.pool.PoolPlacementMapper.requestFor(
            AdPlacement.Language1Carousel, io.onboardkit.ads.pool.poolSnapshot()))
        assertEquals("Its own buffer in the pool", "language1_carousel", request.key)
        assertEquals("Paid events by id name the LFO", "language1", request.trackingKey)
        assertEquals(LFO1.tiers, request.ids)
    }

    @Test fun `page 1 reads the LFO's ad_config key even when the units are written in code`() {
        launch()
        val ads = io.onboardkit.OnboardingSdk.requireConfig().ads
        assertEquals("native_lang", ads.standardKeyFor(AdPlacement.Language1Carousel))
        assertEquals("native_lang_alt", ads.standardKeyFor(AdPlacement.Language2Carousel))
    }
}
