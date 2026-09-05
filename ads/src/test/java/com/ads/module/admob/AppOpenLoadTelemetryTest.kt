package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Public manager/config/Tracker behavior; only the external GMA network boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenLoadTelemetryTest {
    private lateinit var app: Application
    private lateinit var vendor: VendorLoader
    private lateinit var manager: AppOpenManager
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var onEvent: ((String, Map<String, Any?>) -> Unit)? = null
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        vendor = VendorLoader()
        manager = AppOpenManager(vendor)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        ConsentCenter.setHostConsent(true, false)
        networkAvailable(true)
        mainLooper.idle()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "app-open-load-telemetry"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
                onEvent?.invoke(name, params)
            }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        app.unregisterActivityLifecycleCallbacks(manager)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(manager)
        mainLooper.idle()
        Tracker.resetForTesting()
    }

    @Test
    fun `one dispatched request owns accepted telemetry while coalesced and ready probes stay silent`() {
        manager.init(app, UNIT)
        repeat(3) {
            manager.fetchResumeAd()
            manager.isResumeAdAvailable()
        }
        assertEquals(1, vendor.requests.size)
        mainLooper.idleFor(125, TimeUnit.MILLISECONDS)
        val accepted = vendor.fill(0)
        vendor.deliver(0, accepted)
        vendor.fail(0, 17)
        repeat(3) {
            manager.init(app, UNIT)
            manager.fetchResumeAd()
            assertTrue(manager.isResumeAdAvailable())
        }
        mainLooper.idle()

        assertEquals(1, vendor.requests.size)
        val request = params("ad_request").single()
        val loaded = params("ad_loaded").single()
        assertContext(request, UNIT)
        assertContext(loaded, UNIT)
        assertTrue((request["attempt_id"] as String).isNotBlank())
        assertEquals(request["attempt_id"], loaded["attempt_id"])
        assertEquals(125L, loaded["latency_ms"])
        val tier = params("ad_tier_result").single()
        assertEquals(request["attempt_id"], tier["attempt_id"])
        assertEquals("loaded", tier["outcome"])
        assertEquals(1, tier["tier_index"])
        assertTrue(params("ad_load_failed").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
        assertTrue(params("ad_show").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
    }

    @Test
    fun `vendor error preserves its code and retry in the same generation has another attempt`() {
        manager.init(app, UNIT)
        mainLooper.idleFor(55, TimeUnit.MILLISECONDS)
        vendor.fail(0, 17)
        vendor.fail(0, 99)
        repeat(3) { manager.fetchResumeAd() }
        mainLooper.idle()
        assertEquals(1, vendor.requests.size)
        val failed = params("ad_load_failed").single()
        assertContext(failed, UNIT)
        assertEquals(17, failed["error_code"])
        assertEquals(55L, failed["latency_ms"])
        assertEquals("load_failed", params("ad_tier_result").single()["outcome"])
        assertTrue(params("ad_skipped").isEmpty())

        mainLooper.idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(1, vendor.requests.size)
        manager.fetchResumeAd()
        assertEquals(2, vendor.requests.size)
        vendor.fill(1)
        mainLooper.idle()
        val requests = params("ad_request")
        assertEquals(2, requests.size)
        assertNotEquals(requests[0]["attempt_id"], requests[1]["attempt_id"])
        assertEquals(requests[0]["attempt_id"], failed["attempt_id"])
        assertEquals(requests[1]["attempt_id"], params("ad_loaded").single()["attempt_id"])
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(2, params("ad_tier_result").size)
    }

    @Test
    fun `deadline settles once without invented vendor code or backoff probe events`() {
        manager.init(app, UNIT)
        mainLooper.idleFor(30_000, TimeUnit.MILLISECONDS)
        repeat(3) { manager.fetchResumeAd() }
        vendor.fill(0)
        vendor.fail(0, 17)
        mainLooper.idle()

        assertEquals(1, vendor.requests.size)
        assertFalse(manager.isResumeAdAvailable())
        assertEquals(1, params("ad_request").size)
        val failed = params("ad_load_failed").single()
        assertEquals(30_000L, failed["latency_ms"])
        assertFalse(failed.containsKey("error_code"))
        assertEquals("timeout", params("ad_tier_result").single()["outcome"])
        assertTrue(params("ad_loaded").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
    }

    @Test
    fun `unit replacement settles old pending request and late callbacks cannot relabel the new fill`() {
        manager.init(app, "old-resume-unit")
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)
        manager.setAppResumeAdId("new-resume-unit")
        assertEquals(listOf("old-resume-unit", "new-resume-unit"), vendor.requests.map { it.unit })
        vendor.fill(0)
        vendor.fail(0, 17)
        vendor.fill(1)
        mainLooper.idle()

        assertTrue(manager.isResumeAdAvailable())
        val requests = params("ad_request")
        assertEquals(2, requests.size)
        val failed = params("ad_load_failed").single()
        val loaded = params("ad_loaded").single()
        assertContext(failed, "old-resume-unit")
        assertContext(loaded, "new-resume-unit")
        assertEquals(requests[0]["attempt_id"], failed["attempt_id"])
        assertEquals(requests[1]["attempt_id"], loaded["attempt_id"])
        assertNotEquals(failed["attempt_id"], loaded["attempt_id"])
        assertFalse(failed.containsKey("error_code"))
        assertEquals(2, params("ad_tier_result").size)
    }

    @Test
    fun `pending release fails once but releasing an accepted buffer does not revise loaded history`() {
        manager.init(app, UNIT)
        manager.releaseCachedAds()
        manager.releaseCachedAds()
        vendor.fill(0)
        manager.fetchResumeAd()
        assertEquals(2, vendor.requests.size)
        vendor.fill(1)
        mainLooper.idle()
        assertTrue(manager.isResumeAdAvailable())
        manager.releaseCachedAds()
        mainLooper.idle()

        assertFalse(manager.isResumeAdAvailable())
        assertEquals(2, params("ad_request").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(2, params("ad_tier_result").size)
        assertTrue(params("ad_skipped").isEmpty())
    }

    @Test
    fun `consent invalidation settles a started request while denied preload probes are silent`() {
        ConsentCenter.setHostConsent(false, false)
        manager.init(app, UNIT)
        repeat(3) { manager.fetchResumeAd() }
        mainLooper.idle()
        assertTrue(vendor.requests.isEmpty())
        assertTrue(params("ad_request").isEmpty())
        assertTrue(params("ad_load_failed").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())

        ConsentCenter.setHostConsent(true, false)
        mainLooper.idle()
        assertEquals(1, vendor.requests.size)
        ConsentCenter.setHostConsent(false, false)
        mainLooper.idle()
        vendor.fill(0)
        repeat(3) { manager.fetchResumeAd() }
        mainLooper.idle()
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_load_failed").size)
        assertTrue(params("ad_loaded").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
        assertFalse(manager.isResumeAdAvailable())
    }

    @Test
    fun `physical fill rejected by final network policy is a tier fill and one failed accepted attempt`() {
        manager.init(app, UNIT)
        networkAvailable(false)
        vendor.fill(0)
        mainLooper.idle()

        assertEquals(1, vendor.requests.size)
        assertFalse(manager.isResumeAdAvailable())
        assertEquals(1, params("ad_request").size)
        assertEquals("loaded", params("ad_tier_result").single()["outcome"])
        assertEquals(1, params("ad_load_failed").size)
        assertFalse(params("ad_load_failed").single().containsKey("error_code"))
        assertTrue(params("ad_loaded").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
    }

    @Test
    fun `request sink consent revocation cancels logical dispatch before entering the vendor`() {
        manager.init(app, "")
        mainLooper.idle()
        onEvent = { name, _ ->
            if (name == "ad_request") ConsentCenter.setHostConsent(false, false)
        }
        manager.setAppResumeAdId(UNIT)
        mainLooper.idle()

        assertTrue(vendor.requests.isEmpty())
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_load_failed").size)
        assertTrue(params("ad_loaded").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
    }

    @Test
    fun `request sink release cancels old dispatch without adding network backoff`() {
        manager.init(app, "")
        mainLooper.idle()
        var released = false
        onEvent = { name, _ ->
            if (name == "ad_request" && !released) {
                released = true
                manager.releaseCachedAds()
            }
        }
        manager.setAppResumeAdId(UNIT)
        assertTrue(vendor.requests.isEmpty())
        assertEquals(1, params("ad_load_failed").size)

        manager.fetchResumeAd()
        assertEquals(1, vendor.requests.size)
        vendor.fill(0)
        assertEquals(2, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(1, params("ad_load_failed").size)
        assertNotEquals(params("ad_request")[0]["attempt_id"], params("ad_loaded").single()["attempt_id"])
    }

    @Test
    fun `request sink unit replacement dispatches only its current replacement`() {
        manager.init(app, "")
        mainLooper.idle()
        onEvent = { name, values ->
            if (name == "ad_request" && values["ad_unit_id"] == UNIT) {
                manager.setAppResumeAdId("sink-replacement")
            }
        }
        manager.setAppResumeAdId(UNIT)

        assertEquals(listOf("sink-replacement"), vendor.requests.map { it.unit })
        vendor.fill(0)
        assertContext(params("ad_load_failed").single(), UNIT)
        assertContext(params("ad_loaded").single(), "sink-replacement")
        assertEquals(2, params("ad_request").size)
    }

    @Test
    fun `deadline exhausted during paid listener setup cannot accept the original fill`() {
        manager.init(app, UNIT)
        val ad = VendorAd(UNIT).apply {
            onPaidInstalled = { ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(30_001)) }
        }
        vendor.deliver(0, ad)

        assertFalse(manager.isResumeAdAvailable())
        assertNull(ad.onPaidEventListener)
        assertTrue(params("ad_loaded").isEmpty())
        val failed = params("ad_load_failed").single()
        assertEquals(30_001L, failed["latency_ms"])
        assertFalse(failed.containsKey("error_code"))
        // The physical fill arrived on time; its acceptance was delayed by callback setup.
        assertEquals("loaded", params("ad_tier_result").single()["outcome"])
    }

    @Test
    fun `duplicate physical fill from a tier sink cannot replace the first callback being accepted`() {
        manager.init(app, UNIT)
        val duplicate = VendorAd(UNIT)
        var delivered = false
        onEvent = { name, _ ->
            if (name == "ad_tier_result" && !delivered) {
                delivered = true
                vendor.deliver(0, duplicate)
            }
        }
        val first = vendor.fill(0)

        assertTrue(manager.isResumeAdAvailable())
        assertNotNull(first.onPaidEventListener)
        assertNull(duplicate.onPaidEventListener)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(1, params("ad_tier_result").size)
        assertTrue(params("ad_load_failed").isEmpty())
    }

    @Test
    fun `expired pending request telemetry can start B without outer request replacing it with C`() {
        manager.init(app, UNIT)
        var restarted = false
        onEvent = { name, _ ->
            if (name == "ad_load_failed" && !restarted) {
                restarted = true
                manager.releaseCachedAds()
                manager.fetchResumeAd()
            }
        }
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(30_001))
        manager.fetchResumeAd()

        assertEquals(2, vendor.requests.size)
        vendor.fill(1)
        assertTrue(manager.isResumeAdAvailable())
        assertEquals(2, params("ad_request").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(1, params("ad_loaded").size)
    }

    @Test
    fun `expired buffer paid cleanup can start B without outer request dispatching another replacement`() {
        manager.init(app, UNIT)
        val first = vendor.fill(0)
        var restarted = false
        first.onPaidCleared = {
            if (!restarted) {
                restarted = true
                manager.fetchResumeAd()
            }
        }
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(4))
        manager.fetchResumeAd()

        assertEquals(2, vendor.requests.size)
        vendor.fill(1)
        assertTrue(manager.isResumeAdAvailable())
        assertEquals(2, params("ad_request").size)
        assertEquals(2, params("ad_loaded").size)
        assertTrue(params("ad_load_failed").isEmpty())
    }

    private fun assertContext(params: Map<String, Any?>, unit: String) {
        assertEquals("app_resume", params["placement"])
        assertEquals("app_open", params["ad_format"])
        assertEquals(unit, params["ad_unit_id"])
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private fun networkAvailable(available: Boolean) {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = shadowOf(connectivity)
        if (!available) {
            network.setNetworkCapabilities(connectivity.activeNetwork, null)
            return
        }
        network.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        network.setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
    }

    private class VendorLoader : AppResumeAdLoader {
        data class Request(val unit: String, val callback: AppOpenAd.AppOpenAdLoadCallback)
        val requests = mutableListOf<Request>()

        override fun load(context: Context, unitId: String, request: AdRequest,
                          callback: AppOpenAd.AppOpenAdLoadCallback) {
            requests += Request(unitId, callback)
        }

        fun fill(index: Int): VendorAd = VendorAd(requests[index].unit).also { deliver(index, it) }
        fun deliver(index: Int, ad: VendorAd) { requests[index].callback.onAdLoaded(ad) }
        fun fail(index: Int, code: Int) {
            requests[index].callback.onAdFailedToLoad(LoadAdError(code, "Vendor no fill", "vendor", null, null))
        }
    }

    private class VendorAd(private val unit: String) : AppOpenAd() {
        private var content: FullScreenContentCallback? = null
        private var paid: OnPaidEventListener? = null
        private var placement = 0L
        var onPaidInstalled: (() -> Unit)? = null
        var onPaidCleared: (() -> Unit)? = null
        override fun show(activity: Activity) = error("A preload must not present")
        override fun getAdUnitId() = unit
        override fun getResponseInfo(): ResponseInfo = error("No mediation response in this vendor fake")
        override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
        override fun getFullScreenContentCallback() = content
        override fun setOnPaidEventListener(listener: OnPaidEventListener?) {
            paid = listener
            if (listener == null) onPaidCleared?.invoke() else onPaidInstalled?.invoke()
        }
        override fun getOnPaidEventListener() = paid
        override fun setImmersiveMode(immersiveMode: Boolean) = Unit
        override fun getPlacementId() = placement
        override fun setPlacementId(value: Long) { placement = value }
    }

    private companion object {
        const val UNIT = "app-open-telemetry-unit"
    }
}
