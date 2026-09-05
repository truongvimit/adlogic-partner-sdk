package com.ads.module.helper.banner

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.LoadAdError
import io.trackkit.PlacementRegistry
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

/** Public helper → real SDK banner adapters → fake GMA loadAd → actual Tracker and sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [BannerVendorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BannerAdTelemetryTest {
    private lateinit var activity: Activity
    private lateinit var owner: BannerLifecycleOwner
    private lateinit var helper: BannerAdHelper
    private lateinit var host: FrameLayout
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var loadedCalls = 0
    private var failedCalls = 0

    @Before
    fun setUp() {
        BannerVendorShadow.requests.clear()
        PlacementRegistry.clear()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        owner = BannerLifecycleOwner()
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        ConsentCenter.setHostConsent(true, false)
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        host = FrameLayout(activity)
        activity.setContentView(host)
        helper = createHelper()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "banner-telemetry"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
            }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        activity.finish()
        BannerVendorShadow.requests.clear()
        PlacementRegistry.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `banner fallback reports one logical request and one successful terminal`() {
        helper.requestAds(BannerAdParam.Request)
        assertEquals(listOf("banner-high"), BannerVendorShadow.requests.map { it.view.adUnitId })
        BannerVendorShadow.requests[0].listener.onAdFailedToLoad(noFill())
        assertEquals(listOf("banner-high", "banner-low"), BannerVendorShadow.requests.map { it.view.adUnitId })
        BannerVendorShadow.requests[1].listener.onAdLoaded()

        assertEquals(1, loadedCalls)
        assertEquals(0, failedCalls)
        assertEquals(1, params("ad_request").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
        val attempt = params("ad_request").single()["attempt_id"]
        assertNotNull(attempt)
        assertEquals(attempt, params("ad_loaded").single()["attempt_id"])
        assertEquals(listOf(attempt, attempt), params("ad_tier_result").map { it["attempt_id"] })
        assertEquals("banner-low", params("ad_loaded").single()["ad_unit_id"])
        assertEquals(listOf("load_failed", "loaded"), params("ad_tier_result").map { it["outcome"] })
    }

    @Test
    fun `decline before dispatch creates no attempt telemetry`() {
        ConsentCenter.setHostConsent(false, false)

        helper.requestAds(BannerAdParam.Request)

        assertEquals(0, BannerVendorShadow.requests.size)
        assertEquals(0, params("ad_request").size)
        assertEquals(0, params("ad_tier_result").size)
        assertEquals(0, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
    }

    @Test
    fun `a second caller while loading shares the existing attempt`() {
        helper.requestAds(BannerAdParam.Request)
        helper.requestAds(BannerAdParam.Request)
        helper.requestAds(BannerAdParam.Reload)

        assertEquals(1, BannerVendorShadow.requests.size)
        assertEquals(1, params("ad_request").size)
        BannerVendorShadow.requests.single().listener.onAdLoaded()
        assertEquals(1, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
    }

    @Test
    fun `all tiers failing produce one failure despite duplicate and late callbacks`() {
        helper.requestAds(BannerAdParam.Request)
        val first = BannerVendorShadow.requests.single()
        first.listener.onAdFailedToLoad(noFill())
        val last = BannerVendorShadow.requests.last()
        last.listener.onAdFailedToLoad(noFill())
        first.listener.onAdFailedToLoad(noFill())
        last.listener.onAdFailedToLoad(noFill())
        last.listener.onAdLoaded()

        assertEquals(2, BannerVendorShadow.requests.size)
        assertEquals(1, params("ad_request").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(0, params("ad_loaded").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals("banner-low", params("ad_load_failed").single()["ad_unit_id"])
        assertEquals(3, params("ad_load_failed").single()["error_code"])
        assertEquals(1, failedCalls)
        assertEquals(0, loadedCalls)
    }

    @Test
    fun `live refresh callbacks neither open attempts nor settle a replacement`() {
        helper.requestAds(BannerAdParam.Request)
        val survivor = BannerVendorShadow.requests.single()
        survivor.listener.onAdLoaded()
        survivor.listener.onAdFailedToLoad(noFill())
        survivor.listener.onAdLoaded()

        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
        assertEquals(2, loadedCalls)
        assertEquals(1, failedCalls)

        helper.requestAds(BannerAdParam.Reload)
        val replacement = BannerVendorShadow.requests.last()
        survivor.listener.onAdLoaded()
        survivor.listener.onAdFailedToLoad(noFill())
        assertEquals(AdBannerState.Loading, helper.bannerAdState.value)
        assertEquals(2, BannerVendorShadow.requests.size)
        assertEquals(2, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)

        replacement.listener.onAdLoaded()
        assertEquals(2, params("ad_loaded").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(
            params("ad_request").map { it["attempt_id"] },
            params("ad_loaded").map { it["attempt_id"] },
        )
    }

    @Test
    fun `cancel terminates only its dispatched attempt and late fill cannot settle the next one`() {
        helper.requestAds(BannerAdParam.Request)
        val abandoned = BannerVendorShadow.requests.single()
        helper.cancel()
        helper.cancel()

        assertEquals(1, params("ad_tier_result").size)
        assertEquals("load_failed", params("ad_tier_result").single()["outcome"])
        assertEquals(1, params("ad_load_failed").size)
        helper.requestAds(BannerAdParam.Request)
        val replacement = BannerVendorShadow.requests.last()
        abandoned.listener.onAdLoaded()
        abandoned.listener.onAdFailedToLoad(noFill())
        assertEquals(0, params("ad_loaded").size)
        assertEquals(1, params("ad_load_failed").size)

        replacement.listener.onAdLoaded()
        assertEquals(2, params("ad_request").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        val failedId = params("ad_load_failed").single()["attempt_id"]
        val loadedId = params("ad_loaded").single()["attempt_id"]
        assertNotEquals(failedId, loadedId)
        assertEquals(params("ad_request").first()["attempt_id"], failedId)
        assertEquals(params("ad_request").last()["attempt_id"], loadedId)
    }

    @Test
    fun `a physical fill rejected by authority is one failed logical attempt`() {
        helper.requestAds(BannerAdParam.Request)
        val pending = BannerVendorShadow.requests.single()
        ConsentCenter.setHostConsent(false, false)
        pending.listener.onAdLoaded()
        pending.listener.onAdLoaded()
        pending.listener.onAdFailedToLoad(noFill())

        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_tier_result").size)
        assertEquals("loaded", params("ad_tier_result").single()["outcome"])
        assertEquals(0, params("ad_loaded").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(0, loadedCalls)
        assertEquals(1, failedCalls)
    }

    @Test
    fun `null placement snapshots the first unit mapping for the whole attempt`() {
        helper.placement = null
        PlacementRegistry.register("banner-high", "original-placement")
        PlacementRegistry.register("banner-low", "other-placement")
        helper.requestAds(BannerAdParam.Request)
        PlacementRegistry.register("banner-high", "rewritten-placement")
        PlacementRegistry.register("banner-low", "rewritten-placement")
        helper.placement = "next-load-placement"
        BannerVendorShadow.requests[0].listener.onAdFailedToLoad(noFill())
        BannerVendorShadow.requests[1].listener.onAdLoaded()

        assertEquals(listOf("original-placement"), params("ad_request").map { it["placement"] })
        assertEquals(listOf("original-placement", "original-placement"), params("ad_tier_result").map { it["placement"] })
        assertEquals(listOf("original-placement"), params("ad_loaded").map { it["placement"] })
    }

    @Test
    fun `collapsible fallback uses the actual dispatch boundary and one consistent format`() {
        helper.cancel()
        helper = createHelper(BannerType.Collapsible())
        helper.requestAds(BannerAdParam.Request)
        assertEquals(1, BannerVendorShadow.requests.size)
        BannerVendorShadow.requests[0].listener.onAdFailedToLoad(noFill())
        BannerVendorShadow.requests[1].listener.onAdLoaded()

        assertEquals(1, params("ad_request").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
        assertEquals(1, loadedCalls)
        (params("ad_request") + params("ad_tier_result") + params("ad_loaded")).forEach {
            assertEquals("collapsible_banner", it["ad_format"])
        }
    }

    private fun createHelper(type: BannerType = BannerType.Fixed()): BannerAdHelper =
        BannerAdHelper(activity, owner, BannerAdConfig(
            listOf("banner-high", "banner-low"), true, true, type,
        )).attachInto(host).also { created ->
            created.placement = "banner-home"
            created.registerAdListener(object : AdCallback() {
                override fun onAdLoaded() { loadedCalls++ }
                override fun onAdFailedToLoad(error: LoadAdError?) { failedCalls++ }
            })
        }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private fun noFill() = LoadAdError(3, "No fill", "test", null, null)

    private class BannerLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }
}
