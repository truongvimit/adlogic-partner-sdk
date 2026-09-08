package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit
import java.util.Date
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`

/** Real AppOpenManager and public host consent/billing signals; only the external GMA API is shadowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ResumeLoadGmaShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenResumeLoadStateTest {
    private val manager get() = AppOpenManager.getInstance()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = ResumeLoadGmaShadow.requests
    private lateinit var app: Application
    private var premium = false

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        manager.disableAppResume()
        manager.currentActivity?.let { manager.onActivityDestroyed(it) }
        manager.releaseCachedAds()
        manager.init(app, "")
        manager.setInterstitialShowing(false)
        manager.setDisableAdResumeByClickAction(false)
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = premium
        })
        networkAvailable(true)
        requests.clear()
        // The delayed background load must work with only an Application context.
        assertNull(manager.currentActivity)
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        manager.setAppResumeAdId("")
        manager.releaseCachedAds()
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    @Test
    fun `startup enable and explicit foreground fetches do not buy a resume ad`() {
        enable()
        repeat(3) { manager.fetchAd(false) }
        main.idleFor(10, TimeUnit.SECONDS)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `background dispatches once at two seconds and reuses an in flight request`() {
        enable()
        manager.onStop()
        main.idleFor(1_999, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        repeat(3) { manager.fetchAd(false); manager.onStop() }
        nextBackground()
        assertEquals(1, requests.size)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `return before two seconds cancels the pending opportunity`() {
        enable()
        manager.onStop()
        main.idleFor(1_999, TimeUnit.MILLISECONDS)
        manager.onResume()
        main.idleFor(10, TimeUnit.SECONDS)
        assertTrue(requests.isEmpty())
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `activity start cancels before process foreground is delivered`() {
        enable()
        manager.onStop()
        main.idleFor(1_999, TimeUnit.MILLISECONDS)
        val host = Activity()
        manager.onActivityStarted(host)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        manager.onActivityDestroyed(host)
    }

    @Test
    fun `fill after foreground decision stays cached across later background cycles`() {
        startRequest()
        manager.onResume()
        main.idleFor(2, TimeUnit.SECONDS)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
        repeat(3) { nextBackground() }
        assertEquals(1, requests.size)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `failed request does not retry during the same long background stay`() {
        startRequest()
        fail(0)
        main.idleFor(10, TimeUnit.MINUTES)
        repeat(3) { manager.fetchAd(false); manager.onStop() }
        assertEquals(1, requests.size)
        nextBackground()
        assertEquals(2, requests.size)
    }

    @Test
    fun `failure backoff guards rapid background cycles and success resets it`() {
        startRequest()
        for (delayMs in listOf(5_000L, 30_000L, 120_000L, 120_000L)) {
            val count = requests.size
            fail(count - 1)
            nextBackground() // Only two seconds after failure: each backoff still blocks.
            assertEquals(count, requests.size)
            main.idleFor(delayMs, TimeUnit.MILLISECONDS)
            assertEquals("Time alone cannot buy another ad", count, requests.size)
            nextBackground()
            assertEquals(count + 1, requests.size)
        }
        fill(requests.lastIndex)
        manager.releaseCachedAds()
        nextBackground()
        val count = requests.size
        fail(count - 1)
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        nextBackground()
        assertEquals("A successful load resets retry protection to five seconds", count + 1, requests.size)
    }

    @Test
    fun `30 second request timeout rejects stale callbacks without background retry`() {
        startRequest()
        main.idleFor(30_000, TimeUnit.MILLISECONDS)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        manager.fetchAd(false)
        assertEquals(1, requests.size)
        nextBackground()
        assertEquals(2, requests.size)
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `release invalidates A and only a new background stay can buy B`() {
        startRequest()
        manager.releaseCachedAds()
        manager.fetchAd(false)
        assertEquals(1, requests.size)
        nextBackground()
        assertEquals(2, requests.size)
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `disable invalidates pending fill and reenable waits for next background`() {
        startRequest()
        manager.disableAppResume()
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        manager.enableAppResume()
        manager.fetchAd(false)
        assertEquals(1, requests.size)
        nextBackground()
        assertEquals(2, requests.size)
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `disable cancels scheduled background dispatch`() {
        enable()
        manager.onStop()
        main.idleFor(1_000, TimeUnit.MILLISECONDS)
        manager.disableAppResume()
        main.idleFor(2_000, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `unit replacement invalidates A without allowing old callbacks to overwrite B`() {
        startRequest()
        manager.setAppResumeAdId("resume-unit-B")
        nextBackground()
        assertEquals(listOf(UNIT, "resume-unit-B"), requests.map { it.unit })
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `disabled mode cannot dispatch through either lifecycle or explicit fetch`() {
        manager.setAppResumeAdId(UNIT)
        nextBackground()
        repeat(3) { manager.fetchAd(false) }
        assertTrue(requests.isEmpty())
        manager.enableAppResume()
        assertTrue(requests.isEmpty())
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `consent denial blocks background load and later grant needs another opportunity`() {
        enable()
        ConsentCenter.setHostConsent(false, false)
        nextBackground()
        assertTrue(requests.isEmpty())
        ConsentCenter.setHostConsent(true, false)
        manager.fetchAd(false)
        assertTrue(requests.isEmpty())
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `offline background does not load and connectivity alone does not retry`() {
        enable()
        networkAvailable(false)
        nextBackground()
        assertTrue(requests.isEmpty())
        networkAvailable(true)
        manager.fetchAd(false)
        assertTrue(requests.isEmpty())
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `premium source gates without a current Activity`() {
        enable()
        premium = true
        nextBackground()
        assertTrue(requests.isEmpty())
        premium = false
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `ad click suppression and fullscreen departure never buy a resume ad`() {
        enable()
        manager.setDisableAdResumeByClickAction(true)
        nextBackground()
        assertTrue(requests.isEmpty())
        manager.setDisableAdResumeByClickAction(false)
        manager.setInterstitialShowing(true)
        nextBackground()
        assertTrue(requests.isEmpty())
        manager.setInterstitialShowing(false)
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `raw splash fetch keeps its separate network guard`() {
        manager.disableAppResume()
        ConsentCenter.setHostConsent(false, false)
        manager.setSplashActivity(Activity::class.java, "raw-splash-unit", 5_000)
        try {
            networkAvailable(false)
            manager.fetchAd(true)
            assertTrue(requests.isEmpty())
            networkAvailable(true)
            manager.fetchAd(true)
            assertEquals(listOf("raw-splash-unit"), requests.map { it.unit })
        } finally {
            manager.setSplashActivity(null, "", 0)
        }
    }

    @Test
    fun `cache keeps its original four hour expiry and only next background replaces it`() {
        var now = 1_000_000L
        // Date is the external clock boundary used by the existing app-open TTL.
        mockConstruction(Date::class.java) { clock, _ ->
            `when`(clock.time).thenAnswer { now }
        }.use {
            startRequest()
            fill(0)
            now += TimeUnit.HOURS.toMillis(4) - 1
            repeat(3) { nextBackground() }
            assertTrue(manager.isAdAvailable(false))
            assertEquals(1, requests.size)
            now++
            assertFalse("Reusing the cache must not renew its original TTL", manager.isAdAvailable(false))
            manager.fetchAd(false)
            main.idleFor(10, TimeUnit.MINUTES)
            assertEquals("An expired ad does not cause a background refresh loop", 1, requests.size)
            nextBackground()
            assertEquals(2, requests.size)
            fill(1)
            assertTrue(manager.isAdAvailable(false))
        }
    }

    private fun enable() {
        manager.setAppResumeAdId(UNIT)
        manager.enableAppResume()
    }

    private fun nextBackground() {
        manager.onResume()
        manager.onStop()
        main.idleFor(2_000, TimeUnit.MILLISECONDS)
    }

    private fun startRequest() {
        enable()
        nextBackground()
        assertEquals(1, requests.size)
    }

    private fun assertOldRequestCannotOwnReplacement() {
        fail(0)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        manager.fetchAd(false)
        assertEquals(2, requests.size)
        fill(1)
        assertTrue(manager.isAdAvailable(false))
        nextBackground()
        assertEquals(2, requests.size)
    }

    private fun fill(index: Int) {
        requests[index].callback.onAdLoaded(ResumeLoadedGmaAd(requests[index].unit))
    }

    private fun fail(index: Int) {
        requests[index].callback.onAdFailedToLoad(LoadAdError(3, "Boundary no fill", "test.gma", null, null))
    }

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
        network.setNetworkCapabilities(connectivity.activeNetwork, NetworkCapabilities().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        })
    }

    private companion object { const val UNIT = "resume-unit-A" }
}

/** Only Google's static network dispatch is replaced. No SDK loader interface or debug API. */
@Implements(AppOpenAd::class)
class ResumeLoadGmaShadow {
    data class Request(val unit: String, val callback: AppOpenAd.AppOpenAdLoadCallback)
    companion object {
        val requests = mutableListOf<Request>()
        @JvmStatic
        @Implementation
        fun load(context: Context, unit: String, request: AdRequest, callback: AppOpenAd.AppOpenAdLoadCallback) {
            requests += Request(unit, callback)
        }
    }
}

private class ResumeLoadedGmaAd(private val unit: String) : AppOpenAd() {
    private var content: FullScreenContentCallback? = null
    private var paid: OnPaidEventListener? = null
    private var placement = 0L
    override fun show(activity: Activity): Unit = error("Load-state tests must never show an ad")
    override fun getAdUnitId() = unit
    override fun getResponseInfo(): ResponseInfo = error("No impression is produced by load-state tests")
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
    override fun getFullScreenContentCallback() = content
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
    override fun getOnPaidEventListener() = paid
    override fun setImmersiveMode(immersiveMode: Boolean) = Unit
    override fun getPlacementId() = placement
    override fun setPlacementId(value: Long) { placement = value }
}
