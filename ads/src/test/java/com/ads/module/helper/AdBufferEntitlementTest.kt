package com.ads.module.helper

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo

/** Calls the real helper/waterfall/adapter; only Google's network boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AdBufferEntitlementTest {
    private lateinit var app: Application
    private lateinit var vendor: MockedStatic<InterstitialAd>
    private val requests = mutableListOf<InterstitialAdLoadCallback>()
    private var premium = false

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InterstitialAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        vendor = Mockito.mockStatic(InterstitialAd::class.java) { invocation ->
            if (invocation.method.name == "load") {
                requests += invocation.getArgument<InterstitialAdLoadCallback>(3)
            }
            null
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        vendor.close()
        InterstitialAdManager.releaseAll()
        premium = false
        Entitlement.notifyChanged()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `purchase notification clears an already buffered interstitial`() {
        load()
        fill(0)
        assertTrue(InterstitialAdManager.isReady("premium-buffer"))

        premium = true
        Entitlement.notifyChanged()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(InterstitialAdManager.isReady("premium-buffer"))
    }

    @Test
    fun `fill from before a purchase cannot return after entitlement is revoked again`() {
        load()
        premium = true
        Entitlement.notifyChanged()
        shadowOf(Looper.getMainLooper()).idle()
        premium = false
        Entitlement.notifyChanged()
        shadowOf(Looper.getMainLooper()).idle()

        fill(0)

        assertFalse(InterstitialAdManager.isReady("premium-buffer"))
    }

    @Test
    fun `released request cannot settle a replacement request`() {
        load()
        AdGate.releaseBufferedAds()
        load()
        fill(0)
        assertTrue(InterstitialAdManager.isLoading("premium-buffer"))
        assertFalse(InterstitialAdManager.isReady("premium-buffer"))
        fill(1)
        assertFalse(InterstitialAdManager.isLoading("premium-buffer"))
        assertTrue(InterstitialAdManager.isReady("premium-buffer"))
    }

    @Test
    fun `optional premium observer handles seed cancellation and reinstall`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val signal = MutableStateFlow(true)
        try {
            load()
            fill(0)
            val first = AdGate.installPremiumObserver(scope, signal)
            assertFalse(InterstitialAdManager.isReady("premium-buffer"))
            first.cancel()
            load()
            fill(1)
            signal.value = false
            signal.value = true
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(InterstitialAdManager.isReady("premium-buffer"))
            AdGate.installPremiumObserver(scope, signal)
            assertFalse(InterstitialAdManager.isReady("premium-buffer"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `revocation stops a waterfall before its next vendor request`() {
        var failures = 0
        InterstitialAdManager.load(app, "tiers", listOf("high", "normal"),
            InterLoadOptions(reportTelemetry = false), object : AdCallback() {
                override fun onAdFailedToLoad(error: LoadAdError?) { failures++ }
            })
        ConsentCenter.setHostConsent(false, false)
        requests[0].onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null))

        assertEquals(1, requests.size)
        assertEquals(1, failures)
        assertFalse(InterstitialAdManager.isLoading("tiers"))
    }

    @Test
    fun `an authorized cached interstitial remains reusable offline`() {
        load()
        fill(0)
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, null)
        var fills = 0
        InterstitialAdManager.load(app, "premium-buffer", listOf("unit"),
            InterLoadOptions(reportTelemetry = false), object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { fills++ }
            })
        assertEquals(1, fills)
        assertEquals(1, requests.size)
    }

    @Test
    fun `changed personalization replaces an obsolete interstitial cache`() {
        ConsentCenter.setHostConsent(true, true)
        load()
        fill(0)
        ConsentCenter.setHostConsent(true, false)

        assertFalse(InterstitialAdManager.isReady("premium-buffer"))
        load()
        assertEquals(2, requests.size)
        fill(1)
        assertTrue(InterstitialAdManager.isReady("premium-buffer"))
    }

    @Test
    fun `buffer invalidation completes a waiting load once and permits reentrant replacement`() {
        var failures = 0
        InterstitialAdManager.load(app, "premium-buffer", listOf("unit"),
            InterLoadOptions(reportTelemetry = false), object : AdCallback() {
                override fun onAdFailedToLoad(error: LoadAdError?) {
                    failures++
                    load()
                }
            })

        AdGate.releaseBufferedAds()

        assertEquals(1, failures)
        assertEquals(2, requests.size)
        fill(0)
        assertFalse(InterstitialAdManager.isReady("premium-buffer"))
        assertTrue(InterstitialAdManager.isLoading("premium-buffer"))
        fill(1)
        assertTrue(InterstitialAdManager.isReady("premium-buffer"))
        assertEquals(1, failures)
    }

    private fun load() = InterstitialAdManager.load(app, "premium-buffer", listOf("unit"),
        InterLoadOptions(reportTelemetry = false))

    private fun fill(index: Int) {
        requests[index].onAdLoaded(Mockito.mock(InterstitialAd::class.java))
    }
}
