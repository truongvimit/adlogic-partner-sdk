package io.onboardkit.ads.pool

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.pool.PoolNativeSlot.Kind
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.ui.pool.StepSlotCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [PoolGlueViewShadow::class],
    instrumentedPackages = ["io.onboardkit.ads.pool"])
@LooperMode(LooperMode.Mode.PAUSED)
class PoolNativeSlotTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val themed by lazy { ContextThemeWrapper(app, R.style.ob_Theme_OnboardKit) }
    private lateinit var loader: ApFakeLoader
    private lateinit var engine: NativePoolEngine<ApNativeAd>
    private val slots = mutableListOf<Slot>()
    private val ob1 = AdPlacement.StepNative(StepId.OB1)
    private val ob2 = AdPlacement.StepNative(StepId.OB2)

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Slot(placement: AdPlacement, kind: Kind = Kind.OWNER, minState: Lifecycle.State = Lifecycle.State.STARTED) {
        val owner = TestOwner().also { it.registry.currentState = Lifecycle.State.CREATED }
        val container = FrameLayout(themed)
        val log = mutableListOf<String>()
        val slot = PoolNativeSlot(owner, placement, kind, minState, container, StepSlotCallbacks(
            onLoading = { log += "loading" },
            onBound = { log += "bound" },
            onShown = { log += "shown" },
            onUnavailable = { log += "unavailable:${it.key}" },
            onAdEngaged = { log += "engaged:${it.name}" },
        )).also {
            slots += this
            it.start()
            main.idle()
        }

        val view: View? get() = container.getChildAt(0)

        fun moveTo(state: Lifecycle.State) {
            owner.registry.currentState = state
            main.idle()
        }

        fun reenter(state: Lifecycle.State) {
            moveTo(Lifecycle.State.CREATED)
            moveTo(state)
        }

        fun resumeAgain() {
            moveTo(Lifecycle.State.STARTED)
            moveTo(Lifecycle.State.RESUMED)
        }

        /** The vendor ad the container's current view registered. */
        fun shownAd() = (view as? NativeAdView)?.let { Shadow.extract<PoolGlueViewShadow>(it).registered }
    }

    @Before fun setUp() {
        PoolTestSdk.install(app)
        loader = ApFakeLoader()
        engine = installTestEngine(loader)
        OnboardingPool.activate(app, poolSnapshot())
        main.idle()
        clickReturn().set(null)
    }

    @After fun tearDown() {
        slots.forEach { it.moveTo(Lifecycle.State.DESTROYED) }
        clickReturn().set(null)
        PoolTestSdk.tearDown()
    }

    private fun clickReturn(): AtomicReference<String?> =
        ReflectionHelpers.getField(AppOpenManager.getInstance(), "pendingResumeSkipReason")

    private fun filledLfo1(): ApNativeAd {
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        return loader.fill("language1").also { main.idle() }
    }

    private fun bound(): List<String> = PoolTestSdk.events<AnalyticsEvent.AdImpression>().map { it.placementName }

    @Test fun `the slot renders the selection only from its min state`() {
        val s = Slot(AdPlacement.Language1)
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.POLITE)
        main.idle()
        assertEquals(0, s.container.childCount)
        assertTrue(s.log.isEmpty())

        s.moveTo(Lifecycle.State.STARTED)
        assertTrue(s.view is ShimmerFrameLayout)
        assertEquals(View.VISIBLE, s.container.visibility)
        assertEquals(listOf("loading"), s.log)

        val ad = loader.fill("language1")
        main.idle()
        assertTrue(s.view is NativeAdView)
        assertSame(ad.admobNativeAd, s.shownAd())
        assertEquals(listOf("loading", "bound"), s.log)
        assertEquals(listOf("language1"), bound())
    }

    @Test fun `a slot below its min state ignores new values until it re-enters`() {
        val first = filledLfo1()
        val s = Slot(AdPlacement.Language1, minState = Lifecycle.State.RESUMED)
        s.moveTo(Lifecycle.State.STARTED)
        assertEquals(0, s.container.childCount)
        s.moveTo(Lifecycle.State.RESUMED)
        assertSame(first.admobNativeAd, s.shownAd())

        s.moveTo(Lifecycle.State.STARTED)
        OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        val second = loader.fill("language2")
        main.idle()
        assertSame("Paused pages keep their ad", first.admobNativeAd, s.shownAd())
        s.moveTo(Lifecycle.State.RESUMED)
        assertSame(second.admobNativeAd, s.shownAd())
    }

    @Test fun `every re-entry inflates a fresh view of the same ad`() {
        val ad = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.slot.start()
        s.moveTo(Lifecycle.State.STARTED)
        val firstView = s.view
        assertEquals("start() is idempotent", listOf("bound"), s.log)

        s.reenter(Lifecycle.State.STARTED)
        assertNotSame(firstView, s.view)
        assertSame(ad.admobNativeAd, s.shownAd())
        assertEquals(listOf("bound", "bound"), s.log)
        assertEquals("fo_ad_bound once per slot", listOf("language1"), bound())
    }

    @Test fun `a hidden selection reports no fill`() {
        val s = Slot(AdPlacement.Language1)
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        s.moveTo(Lifecycle.State.STARTED)
        loader.exhaust("language1")
        main.idle()
        assertEquals(View.GONE, s.container.visibility)
        assertEquals(listOf("loading", "unavailable:${AdSkipReason.NO_FILL.key}"), s.log)
    }

    @Test fun `an owner slot hides on its own config refusal while a non-owner keeps borrowing`() {
        // OB2 and the resume screen have no unit: both are config refusals.
        PoolTestSdk.configure(AdsConfig(languageNative = LFO1, stepNatives = mapOf(StepId.OB1 to NativeAdUnit("ob1"))))
        val ad = filledLfo1()
        val page = Slot(ob2)
        val resume = Slot(AdPlacement.ResumeNative, Kind.NON_OWNER)
        page.moveTo(Lifecycle.State.STARTED)
        resume.moveTo(Lifecycle.State.STARTED)
        assertSame(ad.admobNativeAd, page.shownAd())
        assertSame(ad.admobNativeAd, resume.shownAd())

        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key), OnboardingPool.claim(ob2, LoadMode.POLITE))
        assertEquals(LoadResult.SkippedByConfig(AdSkipReason.NO_AD_UNIT.key), OnboardingPool.preload(AdPlacement.ResumeNative))
        main.idle()
        assertEquals(View.GONE, page.container.visibility)
        assertEquals(listOf("bound", "unavailable:${AdSkipReason.NO_AD_UNIT.key}"), page.log)
        assertEquals(View.VISIBLE, resume.container.visibility)
        assertSame(ad.admobNativeAd, resume.shownAd())
        assertEquals(listOf("bound"), resume.log)

        page.reenter(Lifecycle.State.STARTED)
        assertEquals("The refusal outlives a re-entry", View.GONE, page.container.visibility)
    }

    @Test fun `a refusal for the whole flow hides every slot at the next resume`() {
        filledLfo1()
        val owner = Slot(AdPlacement.Language1)
        val borrower = Slot(AdPlacement.ResumeNative, Kind.NON_OWNER)
        owner.moveTo(Lifecycle.State.RESUMED)
        borrower.moveTo(Lifecycle.State.RESUMED)

        setPremium(true)
        owner.resumeAgain()
        borrower.resumeAgain()
        listOf(owner, borrower).forEach {
            assertEquals(View.GONE, it.container.visibility)
            assertEquals(listOf("bound", "unavailable:${AdSkipReason.PREMIUM.key}"), it.log)
        }

        setPremium(false)
        val kept = owner.view
        owner.resumeAgain()
        assertEquals(View.VISIBLE, owner.container.visibility)
        assertSame("The hidden view is shown again", kept, owner.view)
        assertEquals(listOf("bound", "unavailable:${AdSkipReason.PREMIUM.key}", "bound"), owner.log)
        assertEquals("fo_ad_bound stays once", 1, bound().count { it == "language1" })
    }

    @Test fun `the first impression of the bound ad reports it shown once`() {
        val ad = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.moveTo(Lifecycle.State.STARTED)
        OnboardingPool.preload(ob1)
        val other = loader.fill("step_ob1")
        main.idle()
        assertSame("The owner's ad stays", ad.admobNativeAd, s.shownAd())

        loader.impress(other)
        assertEquals(listOf("bound"), s.log)
        loader.impress(ad)
        loader.impress(ad)
        main.idle()
        assertEquals(listOf("bound", "shown"), s.log)
    }

    @Test fun `a click on the bound ad tells app resume and engages with NONE`() {
        // Off by default; on, the next return is marked as coming back from the ad.
        AdBehavior.document.acceptSuccessfulFetch("""{"app_open":{"presentation":{"skip_after_ad_click":true}}}""")
        val ad = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.moveTo(Lifecycle.State.STARTED)
        assertNull(clickReturn().get())

        loader.click(ad)
        assertEquals(listOf("bound", "engaged:NONE"), s.log)
        assertEquals("returning_from_ad_click", clickReturn().get())
    }

    @Test fun `setPlacement moves the slot to the new placement's template`() {
        AdRemoteConfig.initializeFromJson("""{"native_lang":{"ids":[{"id":"l1"}],"isEnable":true,"templateId":1},""" +
            """"native_lang_alt":{"ids":[{"id":"l2"}],"isEnable":true,"templateId":2}}""")
        val ad = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.moveTo(Lifecycle.State.STARTED)
        val lfoView = s.view
        assertNotNull(lfoView?.findViewById(R.id.ob_native_lfo_media_well))

        s.slot.setPlacement(AdPlacement.Language2)
        main.idle()
        assertNotSame(lfoView, s.view)
        assertNotNull(s.view?.findViewById(R.id.ob_splash_native_media_well))
        assertNull(s.view?.findViewById(R.id.ob_native_lfo_media_well))
        assertSame(ad.admobNativeAd, s.shownAd())
        assertEquals(AdPlacement.Language2, s.slot.placement)
        assertEquals(listOf("bound", "bound"), s.log)
        assertEquals("fo_ad_bound once per slot; language1's ad is no language2 bind", listOf("language1"), bound())
        assertEquals("The replaced view is not the ad's registrant any more", 0,
            Shadow.extract<PoolGlueViewShadow>(lfoView).destroyCount)
    }

    @Test fun `a slot first bound after setPlacement reports its new placement once`() {
        val s = Slot(AdPlacement.Language1)
        OnboardingPool.claim(AdPlacement.Language1, LoadMode.FORCED)
        s.moveTo(Lifecycle.State.STARTED)
        assertTrue(s.view is ShimmerFrameLayout)

        s.slot.setPlacement(AdPlacement.Language2)
        OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        val ad = loader.fill("language2")
        main.idle()
        assertSame(ad.admobNativeAd, s.shownAd())
        assertEquals(listOf("language2"), bound())

        s.reenter(Lifecycle.State.STARTED)
        assertEquals(listOf("language2"), bound())
    }

    @Test fun `only a live slot hears the events of an ad it shares`() {
        val ad = filledLfo1()
        val lfo = Slot(AdPlacement.Language1)
        lfo.moveTo(Lifecycle.State.STARTED)
        val page = Slot(ob1, minState = Lifecycle.State.RESUMED)
        page.moveTo(Lifecycle.State.RESUMED)
        page.moveTo(Lifecycle.State.STARTED)
        lfo.slot.setFrozen(true)
        val dialog = Slot(AdPlacement.LanguageConfirm)
        dialog.moveTo(Lifecycle.State.STARTED)
        listOf(lfo, page, dialog).forEach { assertSame(ad.admobNativeAd, it.shownAd()) }

        loader.impress(ad)
        loader.click(ad)
        assertEquals(listOf("bound", "shown", "engaged:NONE"), dialog.log)
        assertEquals("Frozen behind the dialog", listOf("bound"), lfo.log)
        assertEquals("Paused below its min state", listOf("bound"), page.log)
    }

    @Test fun `a frozen slot keeps its view and draws the latest value when unfrozen`() {
        val first = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.moveTo(Lifecycle.State.STARTED)
        val frozenView = s.view

        s.slot.setFrozen(true)
        OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        val second = loader.fill("language2")
        main.idle()
        assertSame(frozenView, s.view)
        assertSame(first.admobNativeAd, s.shownAd())

        s.slot.setFrozen(false)
        main.idle()
        assertSame(second.admobNativeAd, s.shownAd())

        val shown = s.view
        s.slot.setFrozen(true)
        s.slot.setFrozen(false)
        main.idle()
        assertNotSame("Unfreezing re-registers the ad a modal may have taken", shown, s.view)
        assertSame(second.admobNativeAd, s.shownAd())
        assertEquals(listOf("bound", "bound", "bound"), s.log)
    }

    @Test fun `destroying the owner tears the slot down`() {
        val ad = filledLfo1()
        val s = Slot(AdPlacement.Language1)
        s.moveTo(Lifecycle.State.STARTED)
        val view = s.view as NativeAdView

        s.moveTo(Lifecycle.State.DESTROYED)
        assertEquals("Its view was the ad's last registrant", 1, Shadow.extract<PoolGlueViewShadow>(view).destroyCount)

        loader.impress(ad)
        OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        loader.fill("language2")
        main.idle()
        assertSame(view, s.view)
        assertEquals(listOf("bound"), s.log)
    }
}
