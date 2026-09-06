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
        // No Activity needed: the source gate must work from the Application during warmup.
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
    fun `three public fetches share one pending vendor load and reuse its fill`() {
        manager.enableAppResume()
        manager.setAppResumeAdId(UNIT)
        repeat(3) { manager.fetchAd(false) }
        assertEquals(1, requests.size)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
        repeat(3) { manager.fetchAd(false) }
        assertEquals(1, requests.size)
    }

    @Test
    fun `GMA failure callbacks back off 5 then 30 then capped120 seconds and fill resets streak`() {
        startRequest()
        for (delayMs in listOf(5_000L, 30_000L, 120_000L, 120_000L)) {
            val count = requests.size
            fail(count - 1)
            repeat(3) { manager.fetchAd(false) }
            assertEquals(count, requests.size)
            main.idleFor(delayMs - 1, TimeUnit.MILLISECONDS)
            manager.fetchAd(false)
            assertEquals(count, requests.size)
            main.idleFor(1, TimeUnit.MILLISECONDS)
            manager.fetchAd(false)
            assertEquals(count + 1, requests.size)
        }
        fill(requests.lastIndex)
        assertTrue(manager.isAdAvailable(false))
        manager.releaseCachedAds()
        manager.fetchAd(false)
        val count = requests.size
        fail(count - 1)
        main.idleFor(4_999, TimeUnit.MILLISECONDS)
        manager.fetchAd(false)
        assertEquals(count, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        manager.fetchAd(false)
        assertEquals("Success resets the failure schedule to five seconds", count + 1, requests.size)
    }

    @Test
    fun `30 second timeout ignores late fill and old failure cannot clear the next request`() {
        startRequest()
        main.idleFor(30_000, TimeUnit.MILLISECONDS)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        manager.fetchAd(false)
        assertEquals(1, requests.size)
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        manager.fetchAd(false)
        assertEquals(2, requests.size)
        fail(0)
        manager.fetchAd(false)
        assertEquals(2, requests.size)
        fill(1)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `release invalidates A without letting its callbacks clear or replace pending B`() {
        startRequest()
        manager.releaseCachedAds()
        manager.fetchAd(false)
        assertEquals(2, requests.size)
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `disable invalidates pending fill and later enable owns a new request`() {
        startRequest()
        manager.disableAppResume()
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        manager.fetchAd(false)
        assertEquals(1, requests.size)
        manager.enableAppResume()
        manager.fetchAd(false)
        assertEquals(2, requests.size)
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `unit replacement invalidates A without letting it mutate the new unit request`() {
        startRequest()
        manager.setAppResumeAdId("resume-unit-B")
        manager.fetchAd(false)
        assertEquals(listOf(UNIT, "resume-unit-B"), requests.map { it.unit })
        assertOldRequestCannotOwnReplacement()
    }

    @Test
    fun `disabled mode does not dispatch despite explicit fetch`() {
        manager.setAppResumeAdId(UNIT)
        repeat(3) { manager.fetchAd(false) }
        assertTrue(requests.isEmpty())
        manager.enableAppResume()
        manager.fetchAd(false)
        assertEquals(1, requests.size)
    }

    @Test
    fun `current consent denial does not dispatch and grant permits a real load`() {
        ConsentCenter.setHostConsent(false, false)
        manager.enableAppResume()
        manager.setAppResumeAdId(UNIT)
        repeat(3) { manager.fetchAd(false) }
        assertTrue(requests.isEmpty())
        ConsentCenter.setHostConsent(true, false)
        manager.fetchAd(false)
        assertEquals(1, requests.size)
    }

    @Test
    fun `offline source does not dispatch and connectivity recovery permits a load`() {
        networkAvailable(false)
        manager.enableAppResume()
        manager.setAppResumeAdId(UNIT)
        repeat(3) { manager.fetchAd(false) }
        assertTrue(requests.isEmpty())
        networkAvailable(true)
        manager.fetchAd(false)
        assertEquals(1, requests.size)
    }

    @Test
    fun `Application premium source gates without a current Activity`() {
        premium = true
        manager.enableAppResume()
        manager.setAppResumeAdId(UNIT)
        repeat(3) { manager.fetchAd(false) }
        assertTrue(requests.isEmpty())
        premium = false
        manager.fetchAd(false)
        assertEquals(1, requests.size)
    }

    @Test
    fun `raw splash fetch uses the network gate without inheriting resume mode or consent gates`() {
        manager.disableAppResume()
        ConsentCenter.setHostConsent(false, false)
        manager.setSplashActivity(Activity::class.java, "raw-splash-unit", 5_000)
        try {
            networkAvailable(false)
            manager.fetchAd(true)
            assertTrue("Raw fetch must not dispatch while offline", requests.isEmpty())
            networkAvailable(true)
            manager.fetchAd(true)
            assertEquals(listOf("raw-splash-unit"), requests.map { it.unit })
        } finally {
            manager.setSplashActivity(null, "", 0)
        }
    }

    private fun startRequest() {
        manager.enableAppResume()
        manager.setAppResumeAdId(UNIT)
        manager.fetchAd(false)
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
        manager.fetchAd(false)
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
