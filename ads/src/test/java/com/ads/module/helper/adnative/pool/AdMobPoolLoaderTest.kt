package com.ads.module.helper.adnative.pool

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import io.trackkit.PlacementRegistry
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AdMobPoolLoaderTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var gma: GmaRequests
    private lateinit var loader: AdMobPoolLoader
    private val events = mutableListOf<String>()
    private val sinkSpy = object : TrackSink {
        override val id = "pool-loader-test"
        override fun onEvent(name: String, params: Map<String, Any?>) { events += name }
    }

    private fun request(key: String = "language1", adConfigKey: String? = null) =
        PoolRequest(key, listOf("unit-a", "unit-b"), R.layout.custom_native_admob_medium, adConfigKey)

    @Before fun setUp() {
        AdBehavior.document.install(null, null)
        AdRemoteConfig.reset()
        PlacementRegistry.clear()
        allowAds(app)
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("pool-test") })
        Tracker.install(app)
        Tracker.addSink(sinkSpy)
        gma = GmaRequests()
        loader = AdMobPoolLoader(app)
        main.idle()
        events.clear()
    }

    @After fun tearDown() {
        Tracker.removeSink(sinkSpy)
        gma.close()
        AdBehavior.document.install(null, null)
        AdRemoteConfig.reset()
        ConsentCenter.clearHostConsent()
        setPremium(false)
        main.idleFor(31, TimeUnit.SECONDS)
    }

    @Test fun `every AdGate refusal fails the id within one loop and sends no request`() {
        val refusals: List<Pair<String, () -> AutoCloseable>> = listOf(
            "requests_held" to { AdGate.holdRequests() },
            "ads_off_by_remote" to {
                AdBehavior.document.install("""{"global":{"ads_enabled":false}}""", null)
                AutoCloseable { AdBehavior.document.install(null, null) }
            },
            "premium" to {
                setPremium(true)
                AutoCloseable { setPremium(false) }
            },
            "offline" to {
                setOnline(app, false)
                AutoCloseable { setOnline(app, true) }
            },
            "ua_gate" to {
                AdRemoteConfig.updateCodeFromJson(
                    """{"native_ua":{"ids":[{"id":"ua-unit"}],"isEnable":true,"enable_ua_check":true}}""")
                AutoCloseable { AdRemoteConfig.reset() }
            },
            "consent" to {
                ConsentCenter.setHostConsent(false, false)
                AutoCloseable { ConsentCenter.setHostConsent(true, false) }
            },
        )
        // Without the refusal the same request goes out, so a restore that leaked cannot hide a case.
        fun assertAllowed(case: String) {
            val sink = RecordingSink()
            loader.load(request(adConfigKey = "native_ua"), "control-$case", sink)
            main.idle()
            assertTrue("$case: allowed again", sink.names.isEmpty())
            assertEquals("$case: allowed again", listOf("control-$case"), gma.all.map { it.unit })
            gma.all.clear()
        }
        assertAllowed("before")
        refusals.forEach { (reason, refuse) ->
            val sink = RecordingSink()
            refuse().use {
                loader.load(request(adConfigKey = "native_ua"), "unit-a", sink)
                assertTrue("$reason: the failure is posted, not reentrant", sink.calls.isEmpty())
                main.idle()
            }
            assertEquals(reason, listOf("failed"), sink.names)
            assertTrue("$reason: delivered on main", sink.calls.single().onMain)
            assertTrue("$reason: no vendor request", gma.all.isEmpty())
            assertEquals("$reason: an unrequested id is not registered", "unknown",
                PlacementRegistry.placementOf("unit-a"))
            assertAllowed(reason)
        }
    }

    @Test fun `the UA gate reads the request's ad_config key, falling back to its pool key`() {
        AdRemoteConfig.updateCodeFromJson(
            """{"language1":{"ids":[{"id":"ua-unit"}],"isEnable":true,"enable_ua_check":true}}""")
        val sink = RecordingSink()
        loader.load(request(key = "language1", adConfigKey = null), "unit-a", sink)
        main.idle()
        assertEquals(listOf("failed"), sink.names)

        val other = RecordingSink()
        loader.load(request(key = "language1", adConfigKey = "native_lang"), "unit-a", other)
        main.idle()
        assertTrue("A clean ad_config key passes", other.calls.isEmpty())
        assertEquals(1, gma.all.size)
    }

    @Test fun `a throwing request fails the id once`() {
        gma.throwOnLoad = IllegalStateException("vendor down")
        val sink = RecordingSink()
        loader.load(request(), "unit-a", sink)
        main.idle()
        assertEquals(listOf("failed"), sink.names)
        assertTrue(sink.calls.single().onMain)
    }

    @Test fun `each requested id is registered to the lender key and no request event is sent`() {
        val sink = RecordingSink()
        loader.load(request(key = "step_ob1"), "unit-a", sink)
        loader.load(request(key = "step_ob1"), "unit-b", RecordingSink())
        assertEquals("step_ob1", PlacementRegistry.placementOf("unit-a"))
        assertEquals("step_ob1", PlacementRegistry.placementOf("unit-b"))
        assertEquals(listOf("unit-a", "unit-b"), gma.all.map { it.unit })

        gma.all.first().fill()
        assertTrue("The vendor lifecycle is still tracked", "ad_loaded" in events)
        assertFalse("The glue reports requests, never the loader", "ad_request" in events)
    }

    @Test fun `a second loader of the same placement registers its ids to that placement`() {
        val carousel = PoolRequest("language1_carousel", listOf("unit-a"), R.layout.custom_native_admob_medium,
            trackingKey = "language1")
        loader.load(carousel, "unit-a", RecordingSink())
        assertEquals("language1", PlacementRegistry.placementOf("unit-a"))
    }

    @Test fun `the fill and every later event carry the produced ad`() {
        val sink = RecordingSink()
        loader.load(request(), "unit-a", sink)
        val vendor = gma.last().fill()
        val ad = sink.calls.single().ad!!
        assertSame(vendor, ad.admobNativeAd)
        assertEquals(R.layout.custom_native_admob_medium, ad.layoutCustomNative)

        gma.last().listener.onAdImpression()
        gma.last().listener.onAdClicked()
        gma.last().listener.onAdOpened()
        assertEquals(listOf("loaded", "impression", "clicked"), sink.names)
        assertTrue(sink.calls.all { it.ad === ad && it.onMain })
    }

    @Test fun `one tap is one click in either callback order, and a second tap is another`() {
        listOf<(AdListener) -> Unit>(
            { it.onAdClicked(); it.onAdOpened() },
            { it.onAdOpened(); it.onAdClicked() },
        ).forEach { tap ->
            val sink = RecordingSink()
            loader.load(request(), "unit-a", sink)
            gma.last().fill()
            tap(gma.last().listener)
            assertEquals(listOf("loaded", "clicked"), sink.names)
            main.idleFor(1, TimeUnit.SECONDS)
            tap(gma.last().listener)
            assertEquals(listOf("loaded", "clicked", "clicked"), sink.names)
        }
    }

    @Test fun `an adapter that reports a tap with one callback only has every tap counted`() {
        val clickedOnly = RecordingSink()
        loader.load(request(), "unit-a", clickedOnly)
        gma.last().fill()
        repeat(2) { gma.last().listener.onAdClicked() }
        assertEquals("Pangle-like", listOf("loaded", "clicked", "clicked"), clickedOnly.names)

        val openedOnly = RecordingSink()
        loader.load(request(), "unit-b", openedOnly)
        gma.last().fill()
        repeat(2) { gma.last().listener.onAdOpened() }
        assertEquals("Meta-like", listOf("loaded", "clicked", "clicked"), openedOnly.names)
    }

    @Test fun `each id pairs only its own ad's callbacks`() {
        val first = RecordingSink()
        loader.load(request(), "unit-a", first)
        val firstGma = gma.last()
        firstGma.fill()
        val second = RecordingSink()
        loader.load(request(), "unit-b", second)
        val secondGma = gma.last()
        secondGma.fill()

        firstGma.listener.onAdClicked()
        secondGma.listener.onAdOpened()
        assertEquals(listOf("loaded", "clicked"), first.names)
        assertEquals(listOf("loaded", "clicked"), second.names)
    }

    @Test fun `failures settle the id at most once and never after a fill`() {
        val failed = RecordingSink()
        loader.load(request(), "unit-a", failed)
        gma.last().fail()
        gma.last().fail()
        gma.last().listener.onAdImpression()
        assertEquals(listOf("failed"), failed.names)

        val shown = RecordingSink()
        loader.load(request(), "unit-b", shown)
        gma.last().fill()
        gma.last().fail()
        assertEquals(listOf("loaded"), shown.names)
    }

    @Test fun `a show failure before the fill fails the id, after it is ignored`() {
        // GMA has no show failure for natives; only ERainAd's wrapper can forward one.
        val handed = mutableListOf<AdCallback>()
        val seamed = AdMobPoolLoader(app) { _, _, _, callback -> handed += callback }
        val early = RecordingSink()
        seamed.load(request(), "unit-a", early)
        handed.last().onAdFailedToShow(AdError(0, "early", "test"))
        handed.last().onAdFailedToShow(AdError(0, "again", "test"))
        handed.last().onAdFailedToLoad(null)
        assertEquals(listOf("failed"), early.names)

        val late = RecordingSink()
        seamed.load(request(), "unit-b", late)
        val ad = ApNativeAd(R.layout.custom_native_admob_medium, PoolVendorAd())
        handed.last().onNativeAdLoaded(ad)
        handed.last().onAdFailedToShow(AdError(0, "late", "test"))
        handed.last().onAdImpression()
        assertEquals(listOf("loaded", "impression"), late.names)
        assertTrue(late.calls.all { it.ad === ad })
    }

    @Test fun `events before a fill have no ad to carry and are dropped`() {
        val handed = mutableListOf<AdCallback>()
        val seamed = AdMobPoolLoader(app) { _, _, _, callback -> handed += callback }
        val sink = RecordingSink()
        seamed.load(request(), "unit-a", sink)
        handed.last().onAdImpression()
        handed.last().onAdClicked()
        assertTrue(sink.calls.isEmpty())
    }

    @Test fun `a fill after the id failed is destroyed and never forwarded`() {
        val sink = RecordingSink()
        loader.load(request(), "unit-a", sink)
        gma.last().fail()
        val vendor = gma.last().fill() as PoolVendorAd
        assertEquals(listOf("failed"), sink.names)
        assertEquals(1, vendor.destroyed)
    }

    @Test fun `callbacks off the main thread reach the sink on main`() {
        val sink = RecordingSink()
        loader.load(request(), "unit-a", sink)
        val request = gma.last()
        Thread {
            request.fill()
            request.listener.onAdImpression()
        }.apply { start(); join() }
        assertTrue(sink.calls.isEmpty())
        main.idle()
        assertEquals(listOf("loaded", "impression"), sink.names)
        assertTrue(sink.calls.all { it.onMain })
    }

    @Test fun `the main looper scheduler runs a zero delay on the next loop and honours cancel`() {
        val scheduler = MainLooperPoolScheduler()
        val ran = mutableListOf<String>()
        scheduler.postDelayed(0) { ran += "zero" }
        scheduler.postDelayed(50) { ran += "cancelled" }.cancel()
        scheduler.postDelayed(100) { ran += "later" }
        assertTrue("Never inline", ran.isEmpty())
        main.idle()
        assertEquals(listOf("zero"), ran)
        main.idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(listOf("zero", "later"), ran)
    }

    @Test fun `the system clock follows elapsed realtime`() {
        val before = SystemPoolClock.nowMs()
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertTrue(SystemPoolClock.nowMs() - before >= 250)
    }
}
