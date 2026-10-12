package io.onboardkit.ui.onboarding

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.FakeAdProvider
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.StepDefinition
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.remote.RemoteFlags
import io.onboardkit.ui.language.LanguageAdapter
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Real pager host and LFO; only the ad vendor is replaced. */
@RunWith(RobolectricTestRunner::class)
// Own sandbox: install() keeps the first provider, and this one records native requests.
// The display matches layout(): a natural layout pass at another width would end a smooth scroll a page off.
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi",
    instrumentedPackages = ["io.onboardkit.ui.onboarding", "io.onboardkit.ui.language"])
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingBackTest {
    /** [beforeLanguage]: the host had neither started the LFO nor begun finishing at the call. */
    private data class Kept(val placement: AdPlacement, val beforeLanguage: Boolean)

    private companion object {
        val preloads = mutableListOf<NativeAdRequest>()
        val kept = mutableListOf<Kept>()
        var host: ObOnboardingHostActivity? = null
        val lfo1 = NativeAdUnit(listOf("lfo1-high", "lfo1-all"))
        val lfo2 = NativeAdUnit(listOf("lfo2-high", "lfo2-all"))
        val content = NativeAdUnit("test-content")
        val fullscreen = NativeAdUnit("test-fullscreen")
    }

    private val ob1 get() = ContentStepDefinition(StepId.OB1, title = "One")
    private val ob2 get() = ContentStepDefinition(StepId.OB2, title = "Two")
    private val ob3 get() = ContentStepDefinition(StepId.OB3, title = "Three")
    private val full1 get() = AdFullScreenStepDefinition(StepId.FULL1, autoNextEnabled = false)

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var controller: ActivityController<ObOnboardingHostActivity>? = null
    private var language: ActivityController<ObLanguageActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val pager get() = activity.findViewById<ViewPager2>(R.id.ob_step_pager)
    private val back get() = activity.findViewById<View>(R.id.ob_step_back)

    @Before fun setup() {
        preloads.clear()
        kept.clear()
        host = null
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSdk.install(app) {
            adProvider = object : FakeAdProvider() {
                override fun preloadNative(activity: Activity, request: NativeAdRequest) {
                    preloads += request
                }

                override fun releaseNativeKeepingUnseen(placement: AdPlacement) {
                    val current = host
                    kept += Kept(placement, current != null && !current.isFinishing &&
                        shadowOf(current).peekNextStartedActivity() == null)
                }

                // A fullscreen page with its ad bound stays put: no fallback timer moves the pager.
                override fun bindNative(
                    activity: ComponentActivity,
                    request: NativeAdRequest,
                    container: FrameLayout,
                    listener: AdEventListener,
                ): Boolean = request.placement is AdPlacement.StepFullScreen
            }
            trackkitAutoTracking(false)
        }
        ConsentCenter.setHostConsent(true, false)
        runBlocking { OnboardingSdk.reset() }
        OnboardingSdk.remoteOrNull()?.applySnapshot(RemoteFlags())
    }

    @After fun cleanup() {
        language?.pause()?.stop()?.destroy()
        controller?.pause()?.stop()?.destroy()
        host = null
        main.idle()
        ConsentCenter.clearHostConsent()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    private fun launch(
        settings: String? = null,
        steps: List<StepDefinition> = listOf(ob1, ob2, ob3),
        exitAd: Boolean = false,
    ) {
        OnboardingSettings.document.acceptSuccessfulFetch(settings)
        OnboardingSdk.configure(onboardKitConfig {
            steps.forEach(::step)
            language = LanguageConfig(languages = listOf("en-US", "es").map { requireNotNull(ObLanguages.find(it)) })
            ads = AdsConfig(languageNative = lfo1, languageDupNative = lfo2, contentStepNative = content,
                fullScreenStepNative = fullscreen,
                afterOnboardingInterstitial = InterstitialAdUnit("test-exit").takeIf { exitAd })
        }.getOrThrow()).getOrThrow()
        controller = Robolectric.buildActivity(ObOnboardingHostActivity::class.java)
        activity.setTheme(R.style.ob_Theme_OnboardKit)
        host = activity
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

    private fun settle() { main.idleFor(2_000, MILLISECONDS); layout() }

    private fun nextPage() { activity.next(null); settle() }

    private fun systemBack() { activity.onBackPressedDispatcher.onBackPressed(); settle() }

    private fun gestureBack() {
        val dispatcher = activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1000f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 1000f, 0.5f, BackEventCompat.EDGE_LEFT))
        dispatcher.onBackPressed()
        settle()
    }

    private fun assertButtonShown(page: Int) {
        assertEquals(page, pager.currentItem)
        assertEquals("Page $page (${activity.stepIdAt(page)?.value}) shows the button", View.VISIBLE, back.visibility)
        assertEquals(1f, back.alpha)
    }

    private fun assertButtonHidden(page: Int) {
        assertEquals(page, pager.currentItem)
        assertEquals("Page $page (${activity.stepIdAt(page)?.value}) has no button", View.GONE, back.visibility)
    }

    /** The button's alpha at every frame of the slide out of [from], read after the host's own callback. */
    private fun recordSlideFrom(from: Int): MutableList<Float> {
        val alphas = mutableListOf<Float>()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, offset: Float, offsetPx: Int) {
                if (position == from && offset > 0f) alphas += if (back.visibility == View.VISIBLE) back.alpha else 0f
            }
        })
        return alphas
    }

    private fun startedLanguage(): Intent {
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) { "Nothing was started" }
        assertEquals(ObLanguageActivity::class.java.name, started.component?.className)
        val firstOpen = requireNotNull(ObLanguageActivity.intentFor(app, LanguageScreenMode.FIRST_OPEN).extras)
        firstOpen.keySet().forEach { key ->
            @Suppress("DEPRECATION")
            assertEquals(key, firstOpen.get(key), started.extras?.get(key))
        }
        return started
    }

    private fun assertNoLanguageStarted() {
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach {
            assertFalse("LFO must not reopen: $it", it.component?.className == ObLanguageActivity::class.java.name)
        }
    }

    private fun ob1Requests() = preloads.count { it.placement == AdPlacement.StepNative(StepId.OB1) }

    @Test fun `OB1 as the first page shows the back button by default`() {
        launch()
        assertButtonShown(0)
        assertEquals(activity.getString(R.string.ob_back), back.contentDescription)
    }

    @Test fun `the back button on OB1 reopens a first-open LFO and finishes the host`() {
        launch()
        back.performClick()
        startedLanguage()
        assertTrue(activity.isFinishing)
        back.performClick()
        assertNull("A second tap reopens nothing", shadowOf(activity).peekNextStartedActivity())
    }

    @Test fun `system back on OB1 returns to the LFO`() {
        launch()
        activity.onBackPressedDispatcher.onBackPressed()
        startedLanguage()
        assertTrue(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull(shadowOf(activity).peekNextStartedActivity())
    }

    @Test fun `a back gesture on OB1 returns to the LFO once it commits`() {
        launch()
        val dispatcher = activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1000f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 1000f, 0.5f, BackEventCompat.EDGE_LEFT))
        assertNull("A gesture in progress has not committed", shadowOf(activity).peekNextStartedActivity())
        assertFalse(activity.isFinishing)
        dispatcher.dispatchOnBackCancelled()
        assertNull(shadowOf(activity).peekNextStartedActivity())
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1000f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.onBackPressed()
        startedLanguage()
        assertTrue(activity.isFinishing)
    }

    @Test fun `every page after OB1 hides the button, content or fullscreen`() {
        launch(steps = listOf(ob1, ob2, full1, ob3))
        assertButtonShown(0)
        (1..3).forEach { page ->
            nextPage()
            assertButtonHidden(page)
        }
    }

    @Test fun `the hidden button on a later page does nothing`() {
        launch()
        nextPage()
        assertButtonHidden(1)
        back.performClick()
        settle()
        assertEquals(1, pager.currentItem)
        assertFalse(activity.isFinishing)
        assertNoLanguageStarted()
    }

    @Test fun `a first page that is not OB1 hides the button and back there leaves without the LFO`() {
        launch(settings = """{"onboarding":{"order":["ob2","ob1","ob3"]}}""")
        assertEquals(StepId.OB2, activity.stepIdAt(0))
        assertButtonHidden(0)
        nextPage()
        assertEquals(StepId.OB1, activity.stepIdAt(1))
        assertButtonHidden(1)
        systemBack()
        assertButtonHidden(0)
        assertNoLanguageStarted()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertNoLanguageStarted()
    }

    @Test fun `system back and the back gesture on a later page go one page back`() {
        launch()
        nextPage()
        nextPage()
        assertButtonHidden(2)
        systemBack()
        assertButtonHidden(1)
        gestureBack()
        assertButtonShown(0)
        assertFalse(activity.isFinishing)
        assertNoLanguageStarted()
        activity.onBackPressedDispatcher.onBackPressed()
        startedLanguage()
    }

    @Test fun `back on a fullscreen page goes one page back to OB1`() {
        launch(steps = listOf(ob1, full1, ob2))
        nextPage()
        assertButtonHidden(1)
        systemBack()
        assertButtonShown(0)
        assertNoLanguageStarted()
    }

    @Test fun `with back_navigates_back off, back on a later page leaves the flow`() {
        launch(settings = """{"onboarding":{"navigation":{"back_navigates_back":false}}}""")
        nextPage()
        assertButtonHidden(1)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertNoLanguageStarted()
    }

    @Test fun `with back_navigates_back off, back on a fullscreen page leaves the flow`() {
        launch(settings = """{"onboarding":{"navigation":{"back_navigates_back":false}}}""",
            steps = listOf(ob1, full1, ob2))
        nextPage()
        assertButtonHidden(1)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertNoLanguageStarted()
    }

    @Test fun `with back_navigates_back off, OB1 still returns to the LFO`() {
        launch(settings = """{"onboarding":{"navigation":{"back_navigates_back":false}}}""")
        assertButtonShown(0)
        activity.onBackPressedDispatcher.onBackPressed()
        startedLanguage()
        assertTrue(activity.isFinishing)
    }

    @Test fun `OB1 with its remote flag off hides the button and back leaves without the LFO`() {
        launch(settings = """{"onboarding":{"steps":{"ob1":{"back":{"enabled":false}}}}}""")
        assertButtonHidden(0)
        back.performClick()
        assertNull(shadowOf(activity).peekNextStartedActivity())
        assertFalse(activity.isFinishing)
        nextPage()
        assertButtonHidden(1)
        systemBack()
        assertButtonHidden(0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertNoLanguageStarted()
    }

    @Test fun `the button fades with the slide when Next leaves OB1`() {
        launch()
        val alphas = recordSlideFrom(0)
        activity.next(null)
        settle()
        assertTrue("OB1 slid out over several frames: $alphas", alphas.size > 1)
        assertTrue("The button stays while OB1 slides out: $alphas", alphas.all { it > 0f })
        assertTrue("and fades as it goes: $alphas", alphas.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("It does fade: $alphas", alphas.last() < 1f)
        assertButtonHidden(1)
    }

    @Test fun `a host recreated on a later page has no button and back goes one page back to it`() {
        launch()
        nextPage()
        controller = requireNotNull(controller).recreate()
        main.idle()
        layout()
        assertButtonHidden(1)
        systemBack()
        assertButtonShown(0)
        assertNoLanguageStarted()
    }

    @Test fun `once the last page resolves the flow exit, back no longer reopens the LFO`() {
        launch(steps = listOf(ob1), exitAd = true)
        assertButtonShown(0)
        activity.next(null)
        assertEquals(View.GONE, back.visibility)
        back.performClick()
        activity.onBackPressedDispatcher.onBackPressed()
        assertNoLanguageStarted()
    }

    @Test fun `returning forgets step requests so OB natives can be requested again`() {
        launch()
        assertEquals("OB1 asked for its native on entry", 1, ob1Requests())
        val request = NativeAdRequest(AdPlacement.StepNative(StepId.OB1), content,
            NativeTemplates.layoutForPlacement(AdPlacement.StepNative(StepId.OB1)))
        assertFalse(OnboardingSdk.preload().requestNativeOnce(activity, request))
        assertEquals(1, ob1Requests())
        back.performClick()
        assertTrue(OnboardingSdk.preload().requestNativeOnce(activity, request))
        assertEquals(2, ob1Requests())
    }

    @Test fun `the reopened LFO requests both LFO natives over their waterfall and its pick warms OB1 again`() {
        launch()
        back.performClick()
        val intent = startedLanguage()
        controller?.pause()?.stop()?.destroy()
        controller = null
        preloads.clear()

        language = Robolectric.buildActivity(ObLanguageActivity::class.java, intent).setup()
        main.idle()
        val screen = requireNotNull(language).get()
        val list = screen.findViewById<RecyclerView>(R.id.ob_language_list)
        val adapter = list.adapter as LanguageAdapter
        assertNull("A fresh pick, not the one made before", adapter.selectedCode)
        assertEquals(lfo1, preloads.single { it.placement == AdPlacement.Language1 }.unit)
        assertEquals(lfo2, preloads.single { it.placement == AdPlacement.Language2 }.unit)
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.ob_ad_block).visibility)

        adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, 0) }.itemView.performClick()
        main.idle()
        assertEquals(1, preloads.count { it.placement == AdPlacement.StepNative(StepId.OB1) })
    }

    @Test fun `the reopened LFO hints the device language, not the one picked before`() {
        val device = java.util.Locale.getDefault()
        try {
            launch()
            OnboardingSdk.persistLanguage("es")
            back.performClick()
            val intent = startedLanguage()
            controller?.pause()?.stop()?.destroy()
            controller = null
            language = Robolectric.buildActivity(ObLanguageActivity::class.java, intent).setup()
            main.idleFor(OnboardingSettings.number("lfo.tap_hint.delay_ms") + 100, MILLISECONDS)
            val screen = requireNotNull(language).get()
            assertEquals("es", java.util.Locale.getDefault().language)
            val adapter = screen.findViewById<RecyclerView>(R.id.ob_language_list).adapter as LanguageAdapter
            assertEquals("en-US", adapter.hintCode)
        } finally {
            java.util.Locale.setDefault(device)
        }
    }

    // ── Unseen ads of the other pages on the way back to the LFO ──

    private fun assertKeptBeforeLanguage(vararg expected: AdPlacement) {
        assertEquals(expected.toList(), kept.map { it.placement })
        assertTrue("Every page is handed back before the LFO starts: $kept", kept.all { it.beforeLanguage })
    }

    @Test fun `the button on OB1 hands back every other page's ad before the LFO, never its own`() {
        launch()
        back.performClick()
        assertKeptBeforeLanguage(AdPlacement.StepNative(StepId.OB2), AdPlacement.StepNative(StepId.OB3))
        startedLanguage()
        back.performClick()
        assertEquals("A second tap hands back nothing again", 2, kept.size)
    }

    @Test fun `system back on OB1 hands back the other pages' ads as the button does`() {
        launch()
        activity.onBackPressedDispatcher.onBackPressed()
        assertKeptBeforeLanguage(AdPlacement.StepNative(StepId.OB2), AdPlacement.StepNative(StepId.OB3))
        startedLanguage()
    }

    @Test fun `a fullscreen page is handed back under its fullscreen placement`() {
        launch(steps = listOf(ob1, full1, ob2))
        back.performClick()
        assertKeptBeforeLanguage(AdPlacement.StepFullScreen(StepId.FULL1), AdPlacement.StepNative(StepId.OB2))
        assertFalse(kept.any { it.placement == AdPlacement.StepNative(StepId.FULL1) })
        startedLanguage()
    }

    @Test fun `going one page back hands back no ad`() {
        launch()
        nextPage()
        nextPage()
        systemBack()
        assertButtonHidden(1)
        gestureBack()
        assertButtonShown(0)
        assertEquals(emptyList<Kept>(), kept)
        assertNoLanguageStarted()
    }

    @Test fun `back that leaves the flow instead of returning to the LFO hands back no ad`() {
        launch(settings = """{"onboarding":{"steps":{"ob1":{"back":{"enabled":false}}}}}""")
        back.performClick()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertEquals(emptyList<Kept>(), kept)
        assertNoLanguageStarted()
    }

    @Test fun `back on a first page that is not OB1 hands back no ad`() {
        launch(settings = """{"onboarding":{"order":["ob2","ob1","ob3"]}}""")
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertEquals(emptyList<Kept>(), kept)
        assertNoLanguageStarted()
    }

    @Test fun `back on a fullscreen page with back_navigates_back off hands back no ad`() {
        launch(settings = """{"onboarding":{"navigation":{"back_navigates_back":false}}}""",
            steps = listOf(ob1, full1, ob2))
        nextPage()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertEquals(emptyList<Kept>(), kept)
        assertNoLanguageStarted()
    }
}
