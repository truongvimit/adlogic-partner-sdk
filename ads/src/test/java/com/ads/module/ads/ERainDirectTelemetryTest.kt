package com.ads.module.ads

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.TrackingAdCallback
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
import java.util.concurrent.TimeUnit

/** Direct public ERain entry points with only external GMA loading replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ERainDirectTelemetryTest {
    private lateinit var activity: Activity
    private lateinit var vendor: MockedConstruction<AdLoader.Builder>
    private val requests = mutableListOf<NativeRequest>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var premium = false

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "direct-erain-recording"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        vendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            val request = NativeRequest()
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { request.loaded = it.getArgument(0); builder }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java)))
                .thenAnswer { request.listener = it.getArgument(0); builder }
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java)))
                .thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { requests += request; null }.`when`(loader)
                .loadAd(Mockito.any(AdRequest::class.java))
        }
    }

    @After
    fun tearDown() {
        vendor.close()
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `direct native load reports one actual request and one correlated terminal`() {
        AdTracking.registerPlacement("native-unit", "feed")
        var fills = 0
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0,
            object : AdCallback() {
                override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
            })
        assertEquals(1, requests.size)
        assertEquals(1, params("ad_request").size)

        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, fills)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
        val request = params("ad_request").single()
        assertEquals("feed", request["placement"])
        assertNotNull(request["attempt_id"])
        assertEquals(request["attempt_id"], params("ad_loaded").single()["attempt_id"])
    }

    @Test
    fun `a prewrapped direct native callback reports the load only once`() {
        var fills = 0
        val callback = TrackingAdCallback("explicit-feed", AdFormat.NATIVE, "native-unit",
            object : AdCallback() {
                override fun onNativeAdLoaded(ad: ApNativeAd) { fills++ }
            })
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0, callback)
        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, fills)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(1, params("ad_request").size)
        assertEquals("explicit-feed", params("ad_loaded").single()["placement"])
        assertEquals(params("ad_request").single()["attempt_id"],
            params("ad_loaded").single()["attempt_id"])
    }

    @Test
    fun `direct native success includes one correlated tier diagnostic`() {
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0, AdCallback())
        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        assertEquals(1, params("ad_tier_result").size)
        val tier = params("ad_tier_result").single()
        assertEquals(1, (tier["tier_index"] as Number).toInt())
        assertEquals("loaded", tier["outcome"])
        assertEquals("native-unit", tier["ad_unit_id"])
        assertEquals(params("ad_request").single()["attempt_id"], tier["attempt_id"])
        assertEquals(tier["attempt_id"], params("ad_loaded").single()["attempt_id"])
    }

    @Test
    fun `direct native failure has one failed tier and terminal despite repeated callbacks`() {
        var failureCalls = 0
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0,
            object : AdCallback() {
                override fun onAdFailedToLoad(error: LoadAdError?) { failureCalls++ }
            })
        val pending = requests.single()
        repeat(2) {
            pending.listener.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        }

        assertEquals(2, failureCalls)
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(1, params("ad_tier_result").size)
        assertEquals(0, params("ad_loaded").size)
        val tier = params("ad_tier_result").single()
        assertEquals("load_failed", tier["outcome"])
        assertEquals(3, (tier["error_code"] as Number).toInt())
        assertEquals(params("ad_request").single()["attempt_id"], tier["attempt_id"])
        assertEquals(tier["attempt_id"], params("ad_load_failed").single()["attempt_id"])
    }

    @Test
    fun `a premium interstitial decline forwards its empty wrapper without a load event`() {
        premium = true
        val results = mutableListOf<ApInterstitialAd?>()
        Mockito.mockStatic(InterstitialAd::class.java).use { interstitialVendor ->
            ERainAd.getInstance().getInterstitialAds(activity, "interstitial-unit",
                TrackingAdCallback("detail", AdFormat.INTERSTITIAL, "interstitial-unit",
                    object : AdCallback() {
                        override fun onApInterstitialLoad(ad: ApInterstitialAd?) { results += ad }
                    }))

            interstitialVendor.verifyNoInteractions()
        }

        assertEquals(1, results.size)
        assertEquals(false, results.single()?.isReady)
        assertEquals(0, params("ad_request").size)
        assertEquals(0, params("ad_tier_result").size)
        assertEquals(0, params("ad_loaded").size)
        assertEquals(0, params("ad_load_failed").size)
    }

    @Test
    fun `direct load freezes placement and measures from dispatch instead of callback construction`() {
        val callback = TrackingAdCallback("original-feed", AdFormat.NATIVE, "native-unit", AdCallback())
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0, callback)
        AdTracking.registerPlacement("native-unit", "another-screen")
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)

        requests.single().loaded.onNativeAdLoaded(Mockito.mock(NativeAd::class.java))

        val loaded = params("ad_loaded").single()
        assertEquals("original-feed", loaded["placement"])
        assertEquals(250L, (loaded["latency_ms"] as Number).toLong())
        assertEquals(250L, (params("ad_tier_result").single()["latency_ms"] as Number).toLong())
        assertEquals(params("ad_request").single()["attempt_id"], loaded["attempt_id"])
    }

    @Test
    fun `prewrapped native presentation forwards callbacks with one show and one real click`() {
        var impressions = 0
        var clicks = 0
        var opens = 0
        val callback = TrackingAdCallback("feed", AdFormat.NATIVE, "native-unit",
            object : AdCallback() {
                override fun onAdImpression() { impressions++ }
                override fun onAdClicked() { clicks++ }
                override fun onAdOpened() { opens++ }
            })
        ERainAd.getInstance().loadNativeAdResultCallback(activity, "native-unit", 0, callback)
        val ad = Mockito.mock(NativeAd::class.java)
        val pending = requests.single()
        pending.loaded.onNativeAdLoaded(ad)

        repeat(2) { pending.listener.onAdImpression() }
        pending.listener.onAdClicked()
        pending.listener.onAdOpened()

        assertEquals(2, impressions)
        assertEquals(1, clicks)
        assertEquals(1, opens)
        assertEquals(1, params("ad_show").size)
        assertEquals(1, params("ad_click").size)
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        Mockito.verify(ad).setOnPaidEventListener(Mockito.any(OnPaidEventListener::class.java))
    }

    @Test
    fun `direct prewrapped interstitial uses one request tier and terminal`() {
        var pending: InterstitialAdLoadCallback? = null
        val results = mutableListOf<ApInterstitialAd?>()
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") pending = call.getArgument(3)
            null
        }.use {
            val returned = ERainAd.getInstance().getInterstitialAds(activity, "interstitial-unit",
                TrackingAdCallback("detail", AdFormat.INTERSTITIAL, "interstitial-unit",
                    object : AdCallback() {
                        override fun onApInterstitialLoad(ad: ApInterstitialAd?) { results += ad }
                    }))
            assertNotNull(pending)
            pending!!.onAdLoaded(Mockito.mock(InterstitialAd::class.java))
            assertSame(returned, results.single())
            assertEquals(true, returned.isReady)
        }

        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_tier_result").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(params("ad_request").single()["attempt_id"],
            params("ad_loaded").single()["attempt_id"])
    }

    @Test
    fun `legacy splash preserves failure and navigation reporting without a synthetic request`() {
        var pending: InterstitialAdLoadCallback? = null
        var failures = 0
        var nextActions = 0
        Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") pending = call.getArgument(3)
            null
        }.use {
            ERainAd.getInstance().loadSplashInterstitialAds(activity, "splash-unit", 0, 0,
                TrackingAdCallback("splash", AdFormat.INTERSTITIAL, "splash-unit",
                    object : AdCallback() {
                        override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
                        override fun onNextAction() { nextActions++ }
                    }))
            assertNotNull(pending)
            pending!!.onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        }

        assertEquals(1, failures)
        assertEquals(1, nextActions)
        assertEquals(1, params("ad_load_failed").size)
        assertEquals(0, params("ad_request").size)
        assertEquals(0, params("ad_tier_result").size)
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private class NativeRequest {
        lateinit var loaded: NativeAd.OnNativeAdLoadedListener
        lateinit var listener: AdListener
    }
}
