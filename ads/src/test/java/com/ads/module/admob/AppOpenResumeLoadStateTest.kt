package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdRemoteConfig
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
        AdRemoteConfig.reset()
        requests.clear()
        ResumeLoadGmaShadow.throwOnLoad = false
        // The delayed background load must work with only an Application context.
        assertNull(manager.currentActivity)
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        manager.setAppResumeAdId("")
        manager.releaseCachedAds()
        AdRemoteConfig.reset()
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    @Test
    fun `disabling open_resume then re-enabling it restores the unit in the same process`() {
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"id":"$UNIT","isEnable":true}}""")
        enable()
        nextBackground()
        assertEquals(1, requests.size)

        // isEnable:false empties the unit — the id is the only switch app-resume reads.
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"id":"$UNIT","isEnable":false}}""")
        main.idle()
        manager.onResume()
        nextBackground()
        assertEquals("A disabled placement must not request", 1, requests.size)

        AdRemoteConfig.initializeFromJson("""{"open_resume":{"id":"$UNIT","isEnable":true}}""")
        main.idle()
        manager.onResume()
        nextBackground()

        assertEquals("Re-enabling must take effect without a process restart", 2, requests.size)
    }

    @Test
    fun `open_resume enable_ua_check keeps the request from an organic install`() {
        // No Adjust attribution has landed in this process, so the module reports it organic.
        AdRemoteConfig.initializeFromJson(
            """{"open_resume":{"id":"$UNIT","isEnable":true,"enable_ua_check":true}}""",
        )
        enable()
        nextBackground()

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `open_resume without the UA flag still requests on an organic install`() {
        AdRemoteConfig.initializeFromJson(
            """{"open_resume":{"id":"$UNIT","isEnable":true,"enable_ua_check":false}}""",
        )
        enable()
        nextBackground()

        assertEquals(1, requests.size)
    }

    @Test
    fun `previous request failure cannot shorten the next background remote delay`() {
        startRequest()
        manager.onResume()
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":60000}}""")
        manager.onStop()
        main.idleFor(1_000, TimeUnit.MILLISECONDS)
        fail(0)
        main.idleFor(58_999, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
    }

    @Test
    fun `previous request timeout cannot shorten the next background remote delay`() {
        startRequest()
        manager.onResume()
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":60000}}""")
        manager.onStop()
        main.idleFor(59_999, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
    }

    @Test
    fun `network lost during request preparation rearms without dispatching or replacing old result`() {
        startRequest()
        manager.onResume()
        main.idleFor(31_000, TimeUnit.MILLISECONDS)
        // External GMA builder construction is the last boundary before dispatch.
        mockConstruction(AdRequest.Builder::class.java) { _, _ -> networkAvailable(false) }.use {
            manager.onStop()
            main.idleFor(5_000, TimeUnit.MILLISECONDS)
            assertEquals(1, requests.size)
        }
        networkAvailable(true)
        fill(0)
        assertTrue("No replacement was sent, so A still owns its late result", manager.isAdAvailable(false))
        main.idleFor(120_000, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
    }

    @Test
    fun `network lost during first request preparation recovers in same background`() {
        enable()
        mockConstruction(AdRequest.Builder::class.java) { _, _ -> networkAvailable(false) }.use {
            nextBackground()
            assertTrue(requests.isEmpty())
        }
        networkAvailable(true)
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
    }

    @Test
    fun `request preparation failure recovers without spending a vendor request`() {
        enable()
        mockConstruction(AdRequest.Builder::class.java) { builder, _ ->
            `when`(builder.build()).thenThrow(IllegalStateException("External request construction"))
        }.use {
            nextBackground()
            assertTrue(requests.isEmpty())
        }
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `rejecting an old personalization result does not cancel the next scheduled opportunity`() {
        startRequest()
        manager.onResume()
        main.idleFor(31_000, TimeUnit.MILLISECONDS)
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":60000}}""")
        manager.onStop()
        ConsentCenter.setHostConsent(true, true)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        main.idleFor(60_000, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
    }

    @Test
    fun `remote delay is applied to next background without moving the current schedule`() {
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":500}}""")
        enable()
        manager.onStop()
        main.idleFor(499, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":5000}}""")
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        manager.onResume()
        manager.releaseCachedAds()
        manager.onStop()
        main.idleFor(4_999, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
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
    fun `offline reentry cannot invalidate an already dispatched request`() {
        startRequest()
        manager.onResume()
        networkAvailable(false)
        manager.onStop()
        main.idleFor(2_000, TimeUnit.MILLISECONDS)
        networkAvailable(true)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
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
    fun `failed requests retry after backoff with at most three dispatches per background stay`() {
        startRequest()
        fail(0)
        main.idleFor(4_999, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
        fail(1)
        main.idleFor(29_999, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(3, requests.size)
        fail(2)
        main.idleFor(20, TimeUnit.MINUTES)
        repeat(3) { manager.fetchAd(false); manager.onStop() }
        assertEquals(3, requests.size)
        nextBackground()
        assertEquals(4, requests.size)
    }

    @Test
    fun `a new background waits for remaining backoff then retries without another lifecycle event`() {
        startRequest()
        for (delayMs in listOf(5_000L, 30_000L, 120_000L)) {
            val count = requests.size
            fail(count - 1)
            nextBackground()
            assertEquals(count, requests.size)
            main.idleFor(delayMs - 2_001, TimeUnit.MILLISECONDS)
            assertEquals(count, requests.size)
            main.idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(count + 1, requests.size)
        }
        fill(requests.lastIndex)
        main.idleFor(10, TimeUnit.MINUTES)
        assertEquals(4, requests.size)
    }

    @Test
    fun `fill after timeout before replacement is cached and cancels retry`() {
        startRequest()
        main.idleFor(31_000, TimeUnit.MILLISECONDS)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
        main.idleFor(10, TimeUnit.MINUTES)
        assertEquals(1, requests.size)
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
    fun `offline precheck can recover within the same background without spending a request`() {
        enable()
        networkAvailable(false)
        nextBackground()
        assertTrue(requests.isEmpty())
        networkAvailable(true)
        manager.fetchAd(false)
        main.idleFor(4_999, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        fill(0)
        main.idleFor(10, TimeUnit.MINUTES)
        assertEquals(1, requests.size)
    }

    @Test
    fun `offline recovery after the retry window needs a new background stay`() {
        enable()
        networkAvailable(false)
        nextBackground()
        main.idleFor(120_000, TimeUnit.MILLISECONDS)
        networkAvailable(true)
        main.idleFor(20, TimeUnit.MINUTES)
        assertTrue(requests.isEmpty())
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `foreground cancels a scheduled failure retry`() {
        startRequest()
        fail(0)
        main.idleFor(4_999, TimeUnit.MILLISECONDS)
        manager.onResume()
        main.idleFor(10, TimeUnit.MINUTES)
        assertEquals(1, requests.size)
    }

    @Test
    fun `foreground cancels offline rechecks`() {
        enable()
        networkAvailable(false)
        nextBackground()
        manager.onResume()
        networkAvailable(true)
        main.idleFor(10, TimeUnit.MINUTES)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `foreground keeps a result arriving after timeout without starting another request`() {
        startRequest()
        manager.onResume()
        main.idleFor(60_000, TimeUnit.MILLISECONDS)
        fill(0)
        assertTrue(manager.isAdAvailable(false))
        nextBackground()
        assertEquals(1, requests.size)
    }

    @Test
    fun `timeout replacement rejects old fill without cancelling the newer request`() {
        startRequest()
        main.idleFor(35_000, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        fill(1)
        assertTrue(manager.isAdAvailable(false))
        main.idleFor(120_000, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
    }

    @Test
    fun `late result still obeys consent personalization and premium changes`() {
        startRequest()
        manager.onResume()
        main.idleFor(31_000, TimeUnit.MILLISECONDS)
        ConsentCenter.setHostConsent(true, true)
        fill(0)
        assertFalse(manager.isAdAvailable(false))
        nextBackground()
        main.idleFor(3_000, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
        manager.onResume()
        main.idleFor(31_000, TimeUnit.MILLISECONDS)
        premium = true
        fill(1)
        assertFalse(manager.isAdAvailable(false))
    }

    @Test
    fun `duplicate vendor terminal callbacks cannot replace or discard a cached ad`() {
        startRequest()
        fill(0)
        fill(0)
        fail(0)
        main.idleFor(10, TimeUnit.MINUTES)
        assertTrue(manager.isAdAvailable(false))
        assertEquals(1, requests.size)
    }

    @Test
    fun `remote zero starts on process stop and invalid values fall back without dropping placements`() {
        for (value in listOf("-1", "null", "true", "{}", "[]", "1.5", "\"oops\"", "9223372036854775808", "86400001")) {
            val config = AdRemoteConfig.fromJson("""{"open_resume":{"app_resume_load_delay_ms":$value,"id":"qa","isEnable":true}}""")!!
            assertEquals(2000L, config.appResumeLoadDelayMs)
            assertEquals(2000L, config.unit("open_resume").appResumeLoadDelayMs)
            assertEquals(listOf("qa"), config.tiersFor("open_resume"))
        }
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":0}}""")
        enable()
        manager.onStop()
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `resume delay belongs to its placement and ignores the former root field`() {
        val config = AdRemoteConfig.fromJson(
            """{
                "app_resume_load_delay_ms": 60000,
                "open_resume": {"id":"qa","isEnable":true,"app_resume_load_delay_ms":"500"},
                "banner_all": {"id":"banner","isEnable":true,"reloadIntervalSeconds":30,"app_resume_load_delay_ms":9000}
            }""",
        )!!
        assertEquals(500L, config.unit("open_resume").appResumeLoadDelayMs)
        assertEquals(500L, config.appResumeLoadDelayMs)
        assertEquals(30, config.unit("banner_all").reloadIntervalSeconds)
        assertEquals(listOf("qa"), config.tiersFor("open_resume"))
        assertEquals(2000L, AdRemoteConfig.fromJson("""{"app_resume_load_delay_ms":60000}""")!!.appResumeLoadDelayMs)
        assertEquals(2000L, AdRemoteConfig.fromJson("""{"open_resume":{"id":"qa","isEnable":true}}""")!!.appResumeLoadDelayMs)
        val updated = config.copy(ads = config.ads + ("open_resume" to config.unit("open_resume").copy(appResumeLoadDelayMs = 7000)))
        assertEquals(7000L, updated.appResumeLoadDelayMs)
    }

    @Test
    fun `long remote delay still permits the first load and its bounded retry window`() {
        AdRemoteConfig.initializeFromJson("""{"open_resume":{"app_resume_load_delay_ms":"300000"}}""")
        enable()
        manager.onStop()
        main.idleFor(299_999, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        main.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, requests.size)
        fail(0)
        main.idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(2, requests.size)
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

        /** Reproduces a vendor dispatch that throws instead of returning a callback. */
        @JvmStatic
        var throwOnLoad = false

        @JvmStatic
        @Implementation
        fun load(context: Context, unit: String, request: AdRequest, callback: AppOpenAd.AppOpenAdLoadCallback) {
            requests += Request(unit, callback)
            if (throwOnLoad) throw IllegalStateException("External GMA dispatch failure")
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
