package io.onboardkit.ui.pool

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.pool.AdMobPoolLoader
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.MainLooperPoolScheduler
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolAdLoader
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolExpiry
import com.ads.module.helper.adnative.pool.PoolLoadSink
import com.ads.module.helper.adnative.pool.PoolParity
import com.ads.module.helper.adnative.pool.PoolRequest
import com.ads.module.helper.adnative.pool.PoolRequestPolicy
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolSlotBinder
import com.ads.module.helper.adnative.pool.PoolSlotBinder.Outcome
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.SystemPoolClock
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAdView
import io.onboardkit.R
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val POOL_KEY = "pool_device_test"
private const val TEST_NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"
private const val FILL_WAIT_S = 30L
private const val IMPRESSION_WAIT_MS = 15_000L
private const val CLICK_WAIT_MS = 10_000L
private const val NO_IMPRESSION_WINDOW_MS = 5_000L
private const val SETTLE_MS = 3_000L
private val SLOT_LAYOUT = R.layout.ob_layout_native_lfo

/** SHARED_POOL rendering against Google's test native unit; a missing fill skips, never passes. */
@RunWith(AndroidJUnit4::class)
class PoolFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var probe: PoolProbe? = null

    @After fun tearDown() {
        onMain {
            probe?.release()
            ConsentCenter.clearHostConsent()
        }
    }

    @Test fun oneFillInThreeFreshViewsCountsOneImpressionAndTheLastViewTakesTheClick() {
        val (pool, ad) = loadOneFill()
        ActivityScenario.launch(PoolSlotDeviceActivity::class.java).use { scenario ->
            val host = scenario.host()
            eventually("Host window focus") { onMain { host.hasWindowFocus() } }
            val upperSlot = onMain { slotBinder(host.upper) }
            val lowerSlot = onMain { slotBinder(host.lower) }

            assertEquals(Outcome.BOUND_NEW, onMain { upperSlot.render(pool.selection()) })
            val first = onMain { host.upper.adView() }
            assertPopulated(first, ad)
            eventually("A visible, focused slot reports its impression", IMPRESSION_WAIT_MS) { pool.impressions.isNotEmpty() }

            val second = onMain {
                upperSlot.forceFreshOnNextRender()
                assertEquals(Outcome.BOUND_NEW, upperSlot.render(pool.selection()))
                host.upper.adView()
            }
            val third = onMain {
                assertEquals(Outcome.BOUND_NEW, lowerSlot.render(pool.selection()))
                host.lower.adView()
            }
            assertEquals("The bound ad is not re-inflated without a replay", Outcome.BOUND_SAME, onMain { lowerSlot.render(pool.selection()) })
            assertNotSame(first, second)
            assertNotSame(second, third)
            assertNotSame(first, third)
            onMain {
                assertNull("A replay replaces the slot's view", first.parent)
                assertSame(host.upper, second.parent)
                assertSame(host.lower, third.parent)
            }
            assertPopulated(second, ad)
            assertPopulated(third, ad)

            SystemClock.sleep(SETTLE_MS)
            assertExactlyOneImpression(pool, ad)
            assertTrue("The impression marks the lender Showing", onMain { pool.state() } is PoolState.Showing)

            val clicked = onMain { requireNotNull(third.callToActionView).performClick() }
            assertTrue("GMA listens on the last registered view's call to action", clicked)
            eventually("The last view's click reaches the sink", CLICK_WAIT_MS) { pool.clicks.isNotEmpty() }
            SystemClock.sleep(SETTLE_MS)
            assertEquals(
                "One CTA tap must be one sink click; 2 means onAdClicked and onAdOpened both reported it",
                1, pool.clicks.size,
            )
            assertSame(ad, pool.clicks.single())
            assertEquals(1, pool.events.count { it is PoolEvent.Clicked })
            returnFromClickDestination(host)
            onMain {
                upperSlot.teardown()
                lowerSlot.teardown()
            }
        }
    }

    @Test fun bindingWhileOnlyStartedUnderACoverCountsNoImpressionUntilVisible() {
        val (pool, ad) = loadOneFill()
        ActivityScenario.launch(PoolSlotDeviceActivity::class.java).use { scenario ->
            val host = scenario.host()
            eventually("Host window focus") { onMain { host.hasWindowFocus() } }
            onMain { host.startActivity(Intent(host, PoolCoverDeviceActivity::class.java)) }
            var cover: PoolCoverDeviceActivity? = null
            eventually("Cover resumed with focus") {
                cover = resumedCover()?.takeIf { onMain { it.hasWindowFocus() } }
                cover != null
            }
            assertEquals("The cover leaves the host only STARTED", Lifecycle.State.STARTED, onMain { host.lifecycle.currentState })

            val slot = onMain { slotBinder(host.upper) }
            assertEquals(Outcome.BOUND_NEW, onMain { slot.render(pool.selection()) })
            assertPopulated(onMain { host.upper.adView() }, ad)
            SystemClock.sleep(NO_IMPRESSION_WINDOW_MS)
            assertEquals(Lifecycle.State.STARTED, onMain { host.lifecycle.currentState })
            assertTrue(
                "GMA counted ${pool.impressions.size} impression(s) for a slot hidden under the cover",
                pool.impressions.isEmpty(),
            )

            onMain { requireNotNull(cover).finish() }
            eventually("Host resumes once the cover is gone") { onMain { host.lifecycle.currentState == Lifecycle.State.RESUMED } }
            eventually("The uncovered slot reports its impression", IMPRESSION_WAIT_MS) { pool.impressions.isNotEmpty() }
            SystemClock.sleep(SETTLE_MS)
            assertExactlyOneImpression(pool, ad)
            onMain { slot.teardown() }
        }
    }

    @Test fun rebindAfterTeardownDestroyedTheLastRegistrantPopulatesAFreshView() {
        val (pool, ad) = loadOneFill()
        ActivityScenario.launch(PoolSlotDeviceActivity::class.java).use { scenario ->
            val host = scenario.host()
            eventually("Host window focus") { onMain { host.hasWindowFocus() } }
            val firstSlot = onMain { slotBinder(host.upper) }
            assertEquals(Outcome.BOUND_NEW, onMain { firstSlot.render(pool.selection()) })
            val destroyed = onMain { host.upper.adView() }

            onMain {
                firstSlot.teardown()
                host.upper.removeAllViews()
            }
            onMain {
                assertNotNull("Teardown never destroys the ad", ad.admobNativeAd)
                assertTrue(ad.isUsable)
                assertSame(ad, pool.heldAd())
            }

            val nextSlot = onMain { slotBinder(host.upper) }
            assertEquals("IGNORED would mean the bind threw", Outcome.BOUND_NEW, onMain { nextSlot.render(pool.selection()) })
            val fresh = onMain { host.upper.adView() }
            assertNotSame(destroyed, fresh)
            assertPopulated(fresh, ad)
            eventually("The re-bound view is live", IMPRESSION_WAIT_MS) { pool.impressions.isNotEmpty() }
            SystemClock.sleep(SETTLE_MS)
            assertExactlyOneImpression(pool, ad)
            onMain { nextSlot.teardown() }
        }
    }

    @Test fun inPlaceLocaleReachesFragmentsInflatedAfterwards() {
        val defaultLocale = Locale.getDefault()
        val appLocales = app.resources.configuration.locales
        ActivityScenario.launch(PoolSlotDeviceActivity::class.java).use { scenario ->
            val host = scenario.host()
            val hostLocales = onMain { host.resources.configuration.locales }
            val target = if (hostLocales[0].language == Locale.FRENCH.language) Locale.GERMAN else Locale.FRENCH
            val expectedNext = onMain { host.stringIn(target, R.string.ob_next) }
            val expectedBack = onMain { host.stringIn(target, R.string.ob_back) }
            assertNotEquals("The test APK must translate ob_next for $target", onMain { host.getString(R.string.ob_next) }, expectedNext)
            try {
                onMain { applyLocaleLikePoolHub(host, target) }
                val fragment = PoolLocaleProbeFragment()
                onMain { host.supportFragmentManager.commitNow { replace(host.upper.id, fragment) } }
                onMain {
                    assertEquals(target, Locale.getDefault())
                    assertEquals(target, fragment.resources.configuration.locales[0])
                    assertEquals(expectedNext, fragment.getString(R.string.ob_next))
                    assertEquals(
                        "ObPrimaryButton ob_textNext from the content step layout",
                        expectedNext, fragment.requireView().findViewById<TextView>(R.id.ob_primary_cta).text.toString(),
                    )
                    assertEquals(
                        "android:contentDescription from the pooled pager layout",
                        expectedBack, fragment.pagerChrome.findViewById<View>(R.id.ob_pool_step_back).contentDescription?.toString(),
                    )
                }
            } finally {
                onMain {
                    host.resources.restoreLocales(hostLocales)
                    app.resources.restoreLocales(appLocales)
                    Locale.setDefault(defaultLocale)
                }
            }
        }
    }

    @Test fun pooledActivitiesAreNotExportedInTheMergedManifest() {
        listOf(ObPoolFlowActivity::class.java, ObPoolResumeActivity::class.java).forEach { activity ->
            val info = activityInfo(ComponentName(app, activity))
            assertFalse("${activity.simpleName} must not be exported", info.exported)
        }
    }

    private fun loadOneFill(): Pair<PoolProbe, ApNativeAd> {
        ensureAdsInitialized()
        onMain { ConsentCenter.setHostConsent(true, false) }
        val refusal = onMain { AdGate.skipReason(app, true) }
        assumeTrue("Device offline", refusal != AdSkipReason.OFFLINE)
        assertNull("Ads must be allowed in the test process", refusal)
        val pool = onMain { PoolProbe(app) }.also { probe = it }
        assertEquals(LoadResult.Requested, onMain { pool.requestFill() })
        val settled = pool.settled.await(FILL_WAIT_S, TimeUnit.SECONDS)
        val ad = onMain { pool.heldAd() }
        assumeTrue("No fill from the Google test native unit within ${FILL_WAIT_S}s", settled && ad != null)
        requireNotNull(ad)
        assertNotNull(ad.admobNativeAd)
        onMain {
            val selection = pool.selection()
            assertSame(ad, selection.ad)
            assertTrue(selection.show)
        }
        return pool to ad
    }

    private fun ensureAdsInitialized() {
        if (adsInitialized) return
        val initialized = CountDownLatch(1)
        onMain {
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
        }
        assumeTrue("GMA did not initialize within 45s", initialized.await(45, TimeUnit.SECONDS))
        adsInitialized = true
    }

    private fun slotBinder(container: FrameLayout) = PoolSlotBinder(container, SLOT_LAYOUT, { null }, destroyOnTeardown = true)

    private fun assertPopulated(view: NativeAdView, ad: ApNativeAd) = onMain {
        val native = requireNotNull(ad.admobNativeAd)
        assertEquals(native.headline, (view.headlineView as TextView).text.toString())
        native.callToAction?.let { assertEquals(it, (view.callToActionView as TextView).text.toString()) }
        assertNotNull("The slot template registers its MediaView", view.mediaView)
    }

    private fun assertExactlyOneImpression(pool: PoolProbe, ad: ApNativeAd) {
        assertEquals("onAdImpression count for one NativeAd", 1, pool.impressions.size)
        assertSame(ad, pool.impressions.single())
        assertEquals(1, pool.events.count { it is PoolEvent.Impression })
    }

    private fun returnFromClickDestination(host: PoolSlotDeviceActivity) {
        repeat(3) {
            if (onMain { host.lifecycle.currentState == Lifecycle.State.RESUMED }) return
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < deadline &&
                onMain { host.lifecycle.currentState } != Lifecycle.State.RESUMED
            ) {
                SystemClock.sleep(100)
            }
        }
    }

    private fun resumedCover(): PoolCoverDeviceActivity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<PoolCoverDeviceActivity>()
            .firstOrNull()
    }

    private fun activityInfo(component: ComponentName): ActivityInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getActivityInfo(component, 0)
        }

    private fun ActivityScenario<PoolSlotDeviceActivity>.host(): PoolSlotDeviceActivity {
        var host: PoolSlotDeviceActivity? = null
        onActivity { host = it }
        return requireNotNull(host)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun eventually(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    private companion object {
        @Volatile var adsInitialized = false
    }
}

private class PoolProbe(context: Context) {
    val impressions = CopyOnWriteArrayList<ApNativeAd>()
    val clicks = CopyOnWriteArrayList<ApNativeAd>()
    val events = CopyOnWriteArrayList<PoolEvent<ApNativeAd>>()
    val settled = CountDownLatch(1)

    private val adMob = AdMobPoolLoader(context)
    private val engine = NativePoolEngine<ApNativeAd>(
        loader = PoolAdLoader { request, id, sink -> adMob.load(request, id, RecordingSink(sink)) },
        policy = PoolRequestPolicy.ALLOW_ALL,
        parity = PoolParity(asyncGate = false, asyncSelector = false),
        clock = SystemPoolClock,
        scheduler = MainLooperPoolScheduler(),
        expiry = PoolExpiry { 0L },
        derivedDispatcher = Dispatchers.Unconfined,
        destroyAd = { it.destroy() },
        stuckLoadingLogMs = 0,
    )

    init {
        engine.addEventListener { event ->
            events += event
            if (event is PoolEvent.Filled || event is PoolEvent.Exhausted) settled.countDown()
        }
    }

    fun requestFill(): LoadResult =
        engine.load(PoolRequest(POOL_KEY, listOf(TEST_NATIVE_UNIT), SLOT_LAYOUT), LoadMode.FORCED)

    fun selection(): PoolSelection<ApNativeAd> = engine.currentSelection()

    fun heldAd(): ApNativeAd? = engine.debugSnapshot().loader(POOL_KEY)?.ad

    fun state(): PoolState? = engine.debugSnapshot().loader(POOL_KEY)?.state

    fun release() {
        engine.releaseAll()
        engine.close()
    }

    private inner class RecordingSink(private val next: PoolLoadSink<ApNativeAd>) : PoolLoadSink<ApNativeAd> {
        override fun onLoaded(ad: ApNativeAd) = next.onLoaded(ad)

        override fun onFailed() = next.onFailed()

        override fun onImpression(ad: ApNativeAd) {
            impressions += ad
            next.onImpression(ad)
        }

        override fun onClicked(ad: ApNativeAd) {
            clicks += ad
            next.onClicked(ad)
        }
    }
}

private fun FrameLayout.adView(): NativeAdView = getChildAt(0) as NativeAdView

private fun Context.stringIn(locale: Locale, @StringRes id: Int): String =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) }).getString(id)

// The deprecated call ObPoolFlowActivity.applyLocaleInPlace makes; replace both together.
@Suppress("DEPRECATION")
private fun applyLocaleLikePoolHub(activity: Activity, locale: Locale) {
    if (activity.resources.configuration.locales[0] == locale) return
    Locale.setDefault(locale)
    val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
}

@Suppress("DEPRECATION")
private fun Resources.restoreLocales(locales: LocaleList) {
    if (configuration.locales == locales) return
    updateConfiguration(Configuration(configuration).apply { setLocales(locales) }, displayMetrics)
}

class PoolSlotDeviceActivity : AppCompatActivity() {
    lateinit var upper: FrameLayout
        private set
    lateinit var lower: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        upper = FrameLayout(this).apply { id = View.generateViewId() }
        lower = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(upper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(lower, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }
}

/** Opaque content in a translucent-theme window, like GMA's AdActivity: the activity below stays STARTED. */
class PoolCoverDeviceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
    }
}

class PoolLocaleProbeFragment : Fragment() {
    lateinit var pagerChrome: View
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        pagerChrome = inflater.inflate(R.layout.ob_fragment_pool_pager, container, false)
        return inflater.inflate(R.layout.ob_fragment_content_step, container, false)
    }
}
