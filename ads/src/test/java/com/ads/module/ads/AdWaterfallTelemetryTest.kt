package com.ads.module.ads

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import com.ads.module.admob.Admob
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdmobHelper
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.reward.RewardAdManager
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.TrackingAdCallback
import io.trackkit.AdFormat
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import com.google.android.gms.ads.nativead.NativeAdOptions
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Public waterfall → actual ad adapter → fake GMA boundary → real Tracker/sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AdWaterfallTelemetryTest {
    private lateinit var activity: Activity
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        Admob.getInstance().setMaxClickAdsPerDay(0)
        InterstitialAdManager.releaseAll()
        RewardAdManager.releaseAll()
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "waterfall-recording"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
            }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, context ->
            val request = NativeRequest(context.arguments()[1] as String)
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { request.loaded = it.getArgument(0); builder }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { request.listener = it.getArgument(0); builder }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
    }

    @After
    fun tearDown() {
        vendor.close()
        InterstitialAdManager.releaseAll()
        RewardAdManager.releaseAll()
        Admob.getInstance().setMaxClickAdsPerDay(0)
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `native fallback is one request and one successful placement terminal`() {
        AdTracking.registerPlacement("high", "feed")
        AdTracking.registerPlacement("normal", "feed")
        var fills = 0
        var failures = 0
        AdWaterfall.loadNative(activity, listOf("high", "normal"), 0, object : AdCallback() {
            override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
            override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
        })
        assertEquals(listOf("high"), requests.map { it.unit })
        requests[0].listener.onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null))
        assertEquals(listOf("high", "normal"), requests.map { it.unit })
        requests[1].loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, fills)
        assertEquals(0, failures)
        assertEquals(1, params("ad_request").size)
        assertEquals(2, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
        val attempt = params("ad_request").single()["attempt_id"]
        assertNotNull(attempt)
        assertEquals(attempt, params("ad_loaded").single()["attempt_id"])
        assertEquals(listOf(attempt, attempt), params("ad_tier_result").map { it["attempt_id"] })
        assertEquals("normal", params("ad_loaded").single()["ad_unit_id"])
    }

    @Test
    fun `interstitial fallback reports one logical terminal across vendor tiers`() {
        val interRequests = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }.use {
            var fills = 0
            AdWaterfall.loadInterstitial(activity, listOf("high", "normal"), object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { if (ad?.isReady == true) fills++ }
            })
            interRequests[0].onAdFailedToLoad(noFill())
            interRequests[1].onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            assertEquals(1, fills)
            assertEquals(1, params("ad_request").size)
            assertEquals(2, params("ad_tier_result").size)
            assertEquals(1, params("ad_loaded").size)
            assertEquals(0, params("ad_load_failed").size)
        }
    }

    @Test
    fun `reward waterfall exhaustion is one placement failure`() {
        val rewardRequests = mutableListOf<RewardedAdLoadCallback>()
        Mockito.mockStatic(RewardedAd::class.java) { call ->
            if (call.method.name == "load") rewardRequests += call.getArgument<RewardedAdLoadCallback>(3)
            null
        }.use {
            var failures = 0
            AdWaterfall.loadReward(activity, listOf("high", "normal"), object : AdCallback() {
                override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
            })
            rewardRequests[0].onAdFailedToLoad(noFill())
            rewardRequests[1].onAdFailedToLoad(noFill())
            assertEquals(1, failures)
            assertEquals(1, params("ad_request").size)
            assertEquals(2, params("ad_tier_result").size)
            assertEquals(0, params("ad_loaded").size)
            assertEquals(1, params("ad_load_failed").size)
            assertEquals(3, params("ad_load_failed").single()["error_code"])
        }
    }

    @Test
    fun `native timeout and late fill retain one attempt and immutable attribution`() {
        AdTracking.registerPlacement("high", "original")
        AdTracking.registerPlacement("normal", "original")
        var fills = 0
        AdWaterfall.loadNative(activity, listOf("high", "normal"), 0, 100L, object : AdCallback() {
            override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
        })
        val attemptId = params("ad_request").single()["attempt_id"]
        AdTracking.registerPlacement("high", "another_screen")
        AdTracking.registerPlacement("normal", "another_screen")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val late = Mockito.mock(NativeAd::class.java)
        requests[0].loaded.onNativeAdLoaded(late)
        Mockito.verify(late).destroy()
        requests[1].loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests[1].listener.onAdFailedToLoad(noFill())
        assertEquals(1, fills)
        assertEquals(listOf("timeout", "loaded"), params("ad_tier_result").map { it["outcome"] })
        assertEquals(1, params("ad_request").size)
        assertEquals(0, params("ad_load_failed").size)
        val loaded = params("ad_loaded").single()
        assertEquals("original", loaded["placement"])
        assertEquals(attemptId, loaded["attempt_id"])
        assertEquals(100L, loaded["latency_ms"])
    }

    @Test
    fun `native winning ad keeps impression click and paid listener forwarding`() {
        var impressions = 0
        var clicks = 0
        AdWaterfall.loadNative(activity, listOf("normal"), 0, object : AdCallback() {
            override fun onAdImpression() { impressions++ }
            override fun onAdClicked() { clicks++ }
        })
        val ad = Mockito.mock(NativeAd::class.java)
        requests.single().loaded.onNativeAdLoaded(ad)
        requests.single().listener.onAdImpression()
        requests.single().listener.onAdClicked()
        assertEquals(1, impressions)
        assertEquals(1, clicks)
        assertEquals(1, params("ad_show").size)
        Mockito.verify(ad).setOnPaidEventListener(Mockito.any())
    }

    @Test
    fun `interstitial cache and in flight joins do not count new requests`() {
        val interRequests = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }.use {
            InterstitialAdManager.load(activity, "home", listOf("unit"))
            InterstitialAdManager.load(activity, "home", listOf("unit"))
            assertEquals(1, interRequests.size)
            interRequests.single().onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            InterstitialAdManager.load(activity, "home", listOf("unit"))
            assertEquals(1, interRequests.size)
            assertEquals(1, params("ad_request").size)
            assertEquals(1, params("ad_loaded").size)
            assertEquals("home", params("ad_loaded").single()["placement"])
        }
    }

    @Test
    fun `reward cache and in flight joins do not count new requests`() {
        val rewardRequests = mutableListOf<RewardedAdLoadCallback>()
        Mockito.mockStatic(RewardedAd::class.java) { call ->
            if (call.method.name == "load") rewardRequests += call.getArgument<RewardedAdLoadCallback>(3)
            null
        }.use {
            RewardAdManager.load(activity, "reward", listOf("unit"))
            RewardAdManager.load(activity, "reward", listOf("unit"))
            assertEquals(1, rewardRequests.size)
            rewardRequests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))
            RewardAdManager.load(activity, "reward", listOf("unit"))
            assertEquals(1, rewardRequests.size)
            assertEquals(1, params("ad_request").size)
            assertEquals(1, params("ad_loaded").size)
            assertEquals("reward", params("ad_loaded").single()["placement"])
        }
    }

    @Test
    fun `interstitial reporting policy disables all load events without suppressing the fill`() {
        val interRequests = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }.use {
            var fills = 0
            InterstitialAdManager.load(activity, "quiet", listOf("unit"),
                InterLoadOptions(reportTelemetry = false), object : AdCallback() {
                    override fun onApInterstitialLoad(ad: ApInterstitialAd?) { if (ad?.isReady == true) fills++ }
                })
            interRequests.single().onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            assertEquals(1, fills)
            assertEquals(0, params("ad_request").size)
            assertEquals(0, params("ad_loaded").size)
            assertEquals(0, params("ad_tier_result").size)
        }
    }

    @Test
    fun `click capped tiers cannot fabricate a request or ready interstitial`() {
        Admob.getInstance().setMaxClickAdsPerDay(1)
        AdmobHelper.increaseNumClickAdsPerDay(activity, "capped")
        var vendorCalls = 0
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") vendorCalls++
            null
        }.use {
            var fills = 0
            var failures = 0
            AdWaterfall.loadInterstitial(activity, listOf("capped"), object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { fills++ }
                override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
            })
            assertEquals(0, vendorCalls)
            assertEquals(0, fills)
            assertEquals(1, failures)
            assertEquals(0, params("ad_request").size)
            assertEquals(0, params("ad_loaded").size)
            assertEquals(0, params("ad_load_failed").size)
            assertEquals(0, params("ad_tier_result").size)
        }
    }

    @Test
    fun `prewrapped native waterfall callback never duplicates lifecycle telemetry`() {
        AdTracking.registerPlacement("unit", "native_home")
        var fills = 0
        var impressions = 0
        val callback = TrackingAdCallback("native_home", AdFormat.NATIVE, "unit", object : AdCallback() {
            override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
            override fun onAdImpression() { impressions++ }
        })
        AdWaterfall.loadNative(activity, listOf("unit"), 0, callback)
        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        requests.single().listener.onAdImpression()
        assertEquals(1, fills)
        assertEquals(1, impressions)
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(1, params("ad_tier_result").size)
        assertEquals(1, params("ad_show").size)
    }

    @Test
    fun `duplicate native fill does not destroy the creative already delivered`() {
        var fills = 0
        AdWaterfall.loadNative(activity, listOf("unit"), 0, object : AdCallback() {
            override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
        })
        val ad = Mockito.mock(NativeAd::class.java)
        requests.single().loaded.onNativeAdLoaded(ad)
        requests.single().loaded.onNativeAdLoaded(ad)
        assertEquals(1, fills)
        Mockito.verify(ad, Mockito.never()).destroy()
        assertEquals(1, params("ad_loaded").size)
    }

    @Test
    fun `prewrapped interstitial waterfall retains one load owner`() {
        val interRequests = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }.use {
            val callback = TrackingAdCallback("home", AdFormat.INTERSTITIAL, "unit", null)
            AdWaterfall.loadInterstitial(activity, listOf("unit"), callback)
            interRequests.single().onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            assertEquals(1, params("ad_request").size)
            assertEquals(1, params("ad_loaded").size)
            assertEquals(1, params("ad_tier_result").size)
        }
    }

    @Test
    fun `prewrapped reward waterfall retains one load owner`() {
        val rewardRequests = mutableListOf<RewardedAdLoadCallback>()
        Mockito.mockStatic(RewardedAd::class.java) { call ->
            if (call.method.name == "load") rewardRequests += call.getArgument<RewardedAdLoadCallback>(3)
            null
        }.use {
            val callback = TrackingAdCallback("reward", AdFormat.REWARDED, "unit", null)
            AdWaterfall.loadReward(activity, listOf("unit"), callback)
            rewardRequests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))
            assertEquals(1, params("ad_request").size)
            assertEquals(1, params("ad_loaded").size)
            assertEquals(1, params("ad_tier_result").size)
        }
    }

    @Test
    fun `skipped capped tier does not take the first actual request attribution`() {
        Admob.getInstance().setMaxClickAdsPerDay(1)
        AdmobHelper.increaseNumClickAdsPerDay(activity, "capped")
        val interRequests = mutableListOf<InterstitialAdLoadCallback>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }.use {
            AdWaterfall.loadInterstitial(activity, listOf("capped", "normal"), object : AdCallback() {})
            interRequests.single().onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            assertEquals("normal", params("ad_request").single()["ad_unit_id"])
            assertEquals(2, params("ad_tier_result").single()["tier_index"])
            assertEquals(1, params("ad_loaded").size)
            assertEquals(0, params("ad_load_failed").size)
        }
    }

    @Test
    fun `attempt elapsed latency includes time asleep without advancing UI timeout`() {
        AdWaterfall.loadNative(activity, listOf("unit"), 0, object : AdCallback() {})
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(5))
        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        assertEquals(5_000L, params("ad_loaded").single()["latency_ms"])
        assertEquals(5_000L, params("ad_tier_result").single()["latency_ms"])
        assertEquals(1, params("ad_request").size)
    }

    private fun noFill() = LoadAdError(3, "No fill", "test", null, null)

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private class NativeRequest(val unit: String) {
        lateinit var loaded: NativeAd.OnNativeAdLoadedListener
        lateinit var listener: AdListener
    }
}
