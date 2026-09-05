package com.ads.module.helper.adnative

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.AdLoadContext
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import io.trackkit.PlacementRegistry
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeAdHelperTelemetryTest {
    private lateinit var activity: ComponentActivity
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val preloader get() = NativeAdPreload.getInstance()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        preloader.releaseAll()
        PlacementRegistry.clear()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "native-helper-telemetry"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            val request = NativeRequest()
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { invocation ->
                    request.onLoaded = invocation.getArgument(0)
                    builder
                }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { invocation ->
                    request.adListener = invocation.getArgument(0)
                    builder
                }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
    }

    @After
    fun tearDown() {
        preloader.releaseAll()
        vendor.close()
        PlacementRegistry.clear()
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `native helper and refill each report one request under the semantic placement`() {
        val config = NativeAdConfig("unit", true, true, 0).apply { autoShimmer = false }
        var binds = 0
        val helper = NativeAdHelper(activity, activity, config)
            .setNativeContentView(FrameLayout(activity))
            .setNativeAdBinder { _, _, _, _ -> binds++ }
            .setEnablePreload(true, "inventory-cache-key")
            .setPreloadAdOption(NativeAdPreloadClientOption(preloadAfterShow = true))
        helper.placement = "native_home"

        helper.requestAds(NativeAdParam.Request)
        PlacementRegistry.register("unit", "other_screen")
        requests[0].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests[1].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, binds)
        assertEquals(2, requests.size)
        assertEquals(1, preloader.getNativeAdBuffer("inventory-cache-key").size)
        assertEquals(2, params("ad_request").size)
        assertEquals(listOf("native_home", "native_home"), params("ad_request").map { it["placement"] })
        assertEquals(listOf("native_home", "native_home"), params("ad_loaded").map { it["placement"] })
        assertEquals(2, params("ad_request").map { it["attempt_id"] }.distinct().size)
    }

    @Test
    fun `explicit load reporting policy applies to the native helper and its refill`() {
        val config = NativeAdConfig("unit", true, true, 0).apply { autoShimmer = false }
        val helper = NativeAdHelper(activity, activity, config)
            .setNativeContentView(FrameLayout(activity))
            .setNativeAdBinder { _, _, _, _ -> }
            .setEnablePreload(true, "inventory")
            .setPreloadAdOption(NativeAdPreloadClientOption(preloadAfterShow = true))
        helper.placement = "legacy_placement"
        helper.loadContext = AdLoadContext("fullscreen", AdFormat.NATIVE_FULL_SCREEN, false)

        helper.requestAds(NativeAdParam.Request)
        requests[0].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests[1].onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(2, requests.size)
        assertTrue(helper.nativeAdState.value is AdNativeState.Loaded)
        assertEquals(1, preloader.getNativeAdBuffer("inventory").size)
        assertTrue(params("ad_request").isEmpty())
        assertTrue(params("ad_loaded").isEmpty())
        assertTrue(params("ad_tier_result").isEmpty())
    }

    @Test
    fun `precompiled Kotlin preload caller retains the original default argument entry point`() {
        val entry = NativeAdPreload::class.java.getMethod(
            "preload\$default", NativeAdPreload::class.java, android.app.Activity::class.java,
            NativeAdConfig::class.java, Integer.TYPE, Integer.TYPE, Any::class.java,
        )
        val config = NativeAdConfig("unit", true, false, 0)

        assertEquals(true, entry.invoke(null, preloader, activity, config, 0, 4, null))
        assertEquals(1, requests.size)
        requests.single().onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, preloader.getNativeAdBuffer("unit").size)
        assertEquals(1, params("ad_request").size)
    }

    @Test
    fun `precompiled Kotlin keyed preload caller retains the original default argument entry point`() {
        val entry = NativeAdPreload::class.java.getMethod(
            "preloadWithKey\$default", NativeAdPreload::class.java, String::class.java,
            android.app.Activity::class.java, NativeAdConfig::class.java,
            Integer.TYPE, Integer.TYPE, Any::class.java,
        )
        val config = NativeAdConfig("unit", true, false, 0)

        assertEquals(true, entry.invoke(null, preloader, "inventory", activity, config, 0, 8, null))
        assertEquals(1, requests.size)
        requests.single().onLoaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, preloader.getNativeAdBuffer("inventory").size)
        assertEquals(1, params("ad_request").size)
    }

    @Test
    fun `direct native helper rejects stale policy fill without successful telemetry or impression`() {
        val config = NativeAdConfig("unit", true, true, 0).apply { autoShimmer = false }
        var binds = 0
        val helper = NativeAdHelper(activity, activity, config)
            .setNativeContentView(FrameLayout(activity))
            .setNativeAdBinder { _, _, _, _ -> binds++ }
        helper.placement = "native_home"
        helper.requestAds(NativeAdParam.Request)
        ConsentCenter.setHostConsent(false, false)
        val ad = Mockito.mock(NativeAd::class.java)
        requests.single().onLoaded.onNativeAdLoaded(ad)
        requests.single().adListener.onAdImpression()
        assertEquals(0, binds)
        Mockito.verify(ad).destroy()
        assertEquals(0, params("ad_loaded").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals("loaded", params("ad_tier_result").single()["outcome"])
        assertEquals(0, params("ad_show").size)
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private class NativeRequest {
        lateinit var onLoaded: NativeAd.OnNativeAdLoadedListener
        lateinit var adListener: AdListener
    }
}
