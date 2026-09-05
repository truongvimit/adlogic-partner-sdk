package com.ads.module.ads

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.ads.module.admob.Admob
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.adnative.NativeAdConfig
import com.ads.module.helper.adnative.NativeAdPreload
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.reward.RewardAdManager
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

/** Real cache owners and load adapters; only GMA's external load boundaries are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AdLoadAcceptanceTelemetryTest {
    private lateinit var activity: Activity
    private lateinit var nativeVendor: MockedConstruction<AdLoader.Builder>
    private lateinit var interstitialVendor: MockedStatic<InterstitialAd>
    private lateinit var rewardVendor: MockedStatic<RewardedAd>
    private val nativeRequests = mutableListOf<NativeAd.OnNativeAdLoadedListener>()
    private val interstitialRequests = mutableListOf<InterstitialAdLoadCallback>()
    private val rewardRequests = mutableListOf<RewardedAdLoadCallback>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val preloader get() = NativeAdPreload.getInstance()
    private var premium = false

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        preloader.releaseAll()
        InterstitialAdManager.releaseAll()
        RewardAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        Admob.getInstance().setMaxClickAdsPerDay(0)
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "load-acceptance-recording"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
            }
        })
        Tracker.install(activity, TrackerConfig(strictValidation = true, logLevel = 0))
        nativeVendor = Mockito.mockConstruction(AdLoader.Builder::class.java) { builder, _ ->
            lateinit var loaded: NativeAd.OnNativeAdLoadedListener
            Mockito.`when`(builder.forNativeAd(Mockito.any(NativeAd.OnNativeAdLoadedListener::class.java)))
                .thenAnswer { loaded = it.getArgument(0); builder }
            Mockito.`when`(builder.withAdListener(Mockito.any(AdListener::class.java))).thenReturn(builder)
            Mockito.`when`(builder.withNativeAdOptions(Mockito.any(NativeAdOptions::class.java))).thenReturn(builder)
            val loader = Mockito.mock(AdLoader::class.java)
            Mockito.`when`(builder.build()).thenReturn(loader)
            Mockito.doAnswer { nativeRequests += loaded; null }
                .`when`(loader).loadAd(Mockito.any(AdRequest::class.java))
        }
        interstitialVendor = Mockito.mockStatic(InterstitialAd::class.java) { call ->
            if (call.method.name == "load") interstitialRequests += call.getArgument<InterstitialAdLoadCallback>(3)
            null
        }
        rewardVendor = Mockito.mockStatic(RewardedAd::class.java) { call ->
            if (call.method.name == "load") rewardRequests += call.getArgument<RewardedAdLoadCallback>(3)
            null
        }
    }

    @After
    fun tearDown() {
        preloader.releaseAll()
        InterstitialAdManager.releaseAll()
        RewardAdManager.releaseAll()
        nativeVendor.close()
        interstitialVendor.close()
        rewardVendor.close()
        Admob.getInstance().setMaxClickAdsPerDay(0)
        Tracker.resetForTesting()
        activity.finish()
    }

    @Test
    fun `native consent rejection keeps the physical fill diagnostic but fails the logical attempt`() {
        startNative("native-unit")
        val results = mutableListOf<ApNativeAd?>()
        preloader.awaitNext("native", results::add)
        ConsentCenter.setHostConsent(false, false)
        val ad = Mockito.mock(NativeAd::class.java)

        nativeRequests.single().onNativeAdLoaded(ad)
        nativeRequests.single().onNativeAdLoaded(ad)

        assertEquals(listOf<ApNativeAd?>(null), results)
        assertFalse(preloader.isPreloadInProgress("native"))
        assertTrue(preloader.getNativeAdBuffer("native").isEmpty())
        Mockito.verify(ad).destroy()
        assertRejectedAttempt("native-unit")
    }

    @Test
    fun `released native generation cannot report success or settle its replacement`() {
        startNative("native-old")
        val oldResults = mutableListOf<ApNativeAd?>()
        preloader.awaitNext("native", oldResults::add)
        preloader.release("native")
        startNative("native-new")
        val newResults = mutableListOf<ApNativeAd?>()
        preloader.awaitNext("native", newResults::add)
        val oldAd = Mockito.mock(NativeAd::class.java)

        nativeRequests[0].onNativeAdLoaded(oldAd)

        assertEquals(listOf<ApNativeAd?>(null), oldResults)
        assertTrue(newResults.isEmpty())
        assertTrue(preloader.isPreloadInProgress("native"))
        assertTrue(preloader.getNativeAdBuffer("native").isEmpty())
        Mockito.verify(oldAd).destroy()
        assertRejectedAttempt("native-old")
        assertNoTerminal("native-new")
        nativeRequests[1].onNativeAdLoaded(Mockito.mock(NativeAd::class.java))
        assertEquals(1, newResults.size)
        assertTrue(newResults.single() != null)
        assertEquals(1, params("ad_loaded", "native-new").size)
    }

    @Test
    fun `interstitial personalization rejection answers the caller once without reporting loaded`() {
        val outcome = InterstitialOutcome()
        InterstitialAdManager.load(activity, "inter", listOf("inter-unit"), listener = outcome)
        ConsentCenter.setHostConsent(true, true)
        val ad = Mockito.mock(InterstitialAd::class.java)

        interstitialRequests.single().onAdLoaded(ad)
        interstitialRequests.single().onAdLoaded(ad)

        assertEquals(0, outcome.fills)
        assertEquals(1, outcome.failures)
        assertFalse(InterstitialAdManager.isReady("inter"))
        assertFalse(InterstitialAdManager.isLoading("inter"))
        assertRejectedAttempt("inter-unit")
    }

    @Test
    fun `released interstitial generation cannot report success or settle its replacement`() {
        val oldOutcome = InterstitialOutcome()
        InterstitialAdManager.load(activity, "inter", listOf("inter-old"), listener = oldOutcome)
        InterstitialAdManager.releaseAll()
        val newOutcome = InterstitialOutcome()
        InterstitialAdManager.load(activity, "inter", listOf("inter-new"), listener = newOutcome)

        interstitialRequests[0].onAdLoaded(Mockito.mock(InterstitialAd::class.java))

        assertEquals(1, oldOutcome.failures)
        assertEquals(0, oldOutcome.fills)
        assertEquals(0, newOutcome.failures)
        assertEquals(0, newOutcome.fills)
        assertFalse(InterstitialAdManager.isReady("inter"))
        assertTrue(InterstitialAdManager.isLoading("inter"))
        assertRejectedAttempt("inter-old")
        assertNoTerminal("inter-new")
        interstitialRequests[1].onAdLoaded(Mockito.mock(InterstitialAd::class.java))
        assertEquals(1, newOutcome.fills)
        assertTrue(InterstitialAdManager.isReady("inter"))
        assertEquals(1, params("ad_loaded", "inter-new").size)
    }

    @Test
    fun `reward personalization rejection answers the caller once without reporting loaded`() {
        val outcome = RewardOutcome()
        RewardAdManager.load(activity, "reward", listOf("reward-unit"), listener = outcome)
        ConsentCenter.setHostConsent(true, true)
        val ad = Mockito.mock(RewardedAd::class.java)

        rewardRequests.single().onAdLoaded(ad)
        rewardRequests.single().onAdLoaded(ad)

        assertEquals(0, outcome.fills)
        assertEquals(1, outcome.failures)
        assertFalse(RewardAdManager.isReady("reward"))
        assertRejectedAttempt("reward-unit")
    }

    @Test
    fun `released reward generation cannot report success or settle its replacement`() {
        val oldOutcome = RewardOutcome()
        RewardAdManager.load(activity, "reward", listOf("reward-old"), listener = oldOutcome)
        RewardAdManager.release("reward")
        val newOutcome = RewardOutcome()
        RewardAdManager.load(activity, "reward", listOf("reward-new"), listener = newOutcome)

        rewardRequests[0].onAdLoaded(Mockito.mock(RewardedAd::class.java))

        assertEquals(1, oldOutcome.failures)
        assertEquals(0, oldOutcome.fills)
        assertEquals(0, newOutcome.failures)
        assertEquals(0, newOutcome.fills)
        assertFalse(RewardAdManager.isReady("reward"))
        assertRejectedAttempt("reward-old")
        assertNoTerminal("reward-new")
        rewardRequests[1].onAdLoaded(Mockito.mock(RewardedAd::class.java))
        assertEquals(1, newOutcome.fills)
        assertTrue(RewardAdManager.isReady("reward"))
        assertEquals(1, params("ad_loaded", "reward-new").size)
    }

    @Test
    fun `premium during reward load and show preserves auto earn once while rejecting the late fill`() {
        var successes = 0
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("premium-reward"),
            onSuccess = Runnable { successes++ }, onFailed = Runnable { failures++ })
        premium = true
        RewardAdManager.releaseAll()
        val ad = Mockito.mock(RewardedAd::class.java)

        rewardRequests.single().onAdLoaded(ad)
        rewardRequests.single().onAdLoaded(ad)

        assertEquals(1, successes)
        assertEquals(0, failures)
        assertFalse(RewardAdManager.isReady("reward"))
        Mockito.verify(ad, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
        assertRejectedAttempt("premium-reward")
    }

    private fun startNative(unit: String) {
        AdTracking.registerPlacement(unit, "native")
        assertTrue(preloader.preloadWithKey("native", activity, NativeAdConfig(unit, true, true, 0)))
    }

    private fun assertRejectedAttempt(unit: String) {
        val request = params("ad_request", unit).single()
        val tier = params("ad_tier_result", unit).single()
        assertEquals("loaded", tier["outcome"])
        assertEquals(request["attempt_id"], tier["attempt_id"])
        assertEquals(0, params("ad_loaded", unit).size)
        val failed = params("ad_load_failed", unit).single()
        assertEquals(request["attempt_id"], failed["attempt_id"])
    }

    private fun assertNoTerminal(unit: String) {
        assertEquals(1, params("ad_request", unit).size)
        assertTrue(params("ad_loaded", unit).isEmpty())
        assertTrue(params("ad_load_failed", unit).isEmpty())
        assertTrue(params("ad_tier_result", unit).isEmpty())
    }

    private fun params(name: String, unit: String) = events
        .filter { it.first == name && it.second["ad_unit_id"] == unit }
        .map { it.second }

    private class InterstitialOutcome : AdCallback() {
        var fills = 0
        var failures = 0
        override fun onApInterstitialLoad(ad: ApInterstitialAd?) { fills++ }
        override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
    }

    private class RewardOutcome : AdCallback() {
        var fills = 0
        var failures = 0
        override fun onRewardAdLoaded(ad: RewardedAd?) { fills++ }
        override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
    }
}
