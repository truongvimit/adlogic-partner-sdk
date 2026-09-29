package com.ads.module.helper.interstitial

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.LoadAdError
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** Screen gating through the public buffer/manager APIs; only GMA and time are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialPreloadScreensTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var host: Int02Activity
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val vendors = mutableListOf<Int02VendorAd>()

    @Before
    fun setUp() {
        AdBehavior.document.acceptSuccessfulFetch(null)
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialAutoBuffer.setPreloadScreens(emptyMap())
        InterstitialAutoBuffer.setCurrentScreen(null)
        InterstitialFrequency.reset()
        InterstitialAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("content-test") })
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        AdRemoteConfig.initializeFromJson("""{
            "inter_all":{"id":"content-unit","isEnable":true},
            "inter_back":{"id":"back-unit","isEnable":true}
        }""")
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        host = controller.get()
        main.idle()
        requests.clear()
    }

    @After
    fun tearDown() {
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.setPreloadScreens(emptyMap())
        InterstitialAutoBuffer.setCurrentScreen(null)
        AdBehavior.document.acceptSuccessfulFetch(null)
        vendors.filter { it.hosts.isNotEmpty() }.forEach { it.callback.onAdDismissedFullScreenContent() }
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialAdManager.releaseAll()
        AdRemoteConfig.reset()
        ShadowDialog.getLatestDialog()?.dismiss()
        if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
        if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        if (host.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
        advance(800)
        requests.clear()
    }

    @Test
    fun `entering an allowed screen after the deadline preloads without waiting for a new cycle`() {
        arm()
        InterstitialAutoBuffer.setCurrentScreen("home")
        advance(35_000)
        assertEquals("Home must hold the request even after the cooldown", 0, requests.size)
        assertEquals(0L, InterstitialFrequency.remainingMs(host, BACK))
        InterstitialAutoBuffer.setCurrentScreen("translate")
        main.idle()
        assertEquals("Screen entry must wake the buffer without a new interval", 1, requests.size)
        assertTrue(InterstitialAdManager.isLoading(BACK))
        assertEquals(0L, InterstitialFrequency.remainingMs(host, BACK))
    }

    @Test
    fun `early entry and repeated updates retain the original preload and show deadlines`() {
        arm()
        advance(10_000)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(10_000)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(7_999)
        assertEquals(0, requests.size)
        advance(1)
        assertEquals(1, requests.size)
        fill()
        assertFalse(InterstitialAdManager.canShow(host, BACK))
        advance(2_000)
        assertTrue(InterstitialAdManager.canShow(host, BACK))
        assertEquals(1, requests.size)
    }

    @Test
    fun `leaving before the deadline holds the request until a later eligible screen`() {
        arm()
        advance(10_000)
        val screen = InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(10_000)
        screen.close()
        advance(15_000)
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(0, requests.size)
        InterstitialAutoBuffer.setCurrentScreen("camera")
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `stale and duplicate cleanup cannot clear a newer instance of the same screen`() {
        arm()
        val old = InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(10_000)
        val current = InterstitialAutoBuffer.setCurrentScreen("translate")
        old.close()
        old.close()
        advance(18_000)
        assertEquals(1, requests.size)
        current.close()
        // A late fill is retained, but leaving the screen prevents a new automatic request.
        fill()
        advance(2_000)
        assertTrue(InterstitialAdManager.isReady(BACK))
        InterstitialAdManager.release(BACK)
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `screen entry does not invent taps and preserves actions accumulated on home`() {
        arm(taps = 2)
        InterstitialAutoBuffer.setCurrentScreen("home")
        advance(35_000)
        click(timeoutMs = 0)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        main.idle()
        assertEquals("One action still falls short of the two-action rule", 0, requests.size)
        InterstitialAutoBuffer.setCurrentScreen("home")
        click(timeoutMs = 0)
        main.idle()
        assertEquals("Satisfied taps must still wait for an allowed screen", 0, requests.size)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        main.idle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `late fill can show after returning home and refill waits for a feature again`() {
        arm()
        InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(28_000)
        assertEquals(1, requests.size)
        InterstitialAutoBuffer.setCurrentScreen("home")
        val ad = fill()
        advance(2_000)
        assertTrue(InterstitialAdManager.isReady(BACK))
        assertTrue(InterstitialAdManager.canShow(host, BACK))
        val back = click()
        advance(800)
        assertEquals(listOf(host), ad.hosts)
        ad.callback.onAdShowedFullScreenContent()
        ad.callback.onAdDismissedFullScreenContent()
        assertEquals(1, back.completed)
        assertEquals(30_000L, InterstitialFrequency.remainingMs(host, BACK))
        advance(35_000)
        assertEquals("Returning Home must hold the next refill", 1, requests.size)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        main.idle()
        assertEquals(2, requests.size)
    }

    @Test
    fun `a deliberate back action on home can still load and show without screen permission`() {
        arm()
        InterstitialAutoBuffer.setCurrentScreen("home")
        advance(35_000)
        assertEquals(0, requests.size)
        val result = click()
        assertEquals(1, requests.size)
        val ad = fill()
        advance(800)
        assertEquals(listOf(host), ad.hosts)
        ad.callback.onAdShowedFullScreenContent()
        ad.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.completed)
    }

    @Test
    fun `screen changes preserve the full failure backoff`() {
        arm()
        InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(28_000)
        assertEquals(1, requests.size)
        requests.single().onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        val failedAt = SystemClock.elapsedRealtime()
        InterstitialAutoBuffer.setCurrentScreen("home")
        advance(10_000)
        InterstitialAutoBuffer.setCurrentScreen("camera")
        advanceUntil(failedAt + 29_999)
        assertEquals("Changing screens cannot bypass the failure backoff", 1, requests.size)
        advance(1)
        assertEquals("The backoff is anchored to failure, not screen entry", 2, requests.size)
    }

    @Test
    fun `screen blocked back never postpones another placements original deadline`() {
        InterstitialAutoBuffer.setPreloadScreens(mapOf(BACK to setOf("translate")))
        InterstitialAutoBuffer.setCurrentScreen("home")
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(ALL, BACK), placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 0, BACK to 0),
            intervalMsByPlacement = mapOf(ALL to 30_000L, BACK to 0L), tickMs = 60_000L,
        ))
        InterstitialAutoBuffer.start(host)
        advance(27_999)
        assertEquals(0, requests.size)
        advance(1)
        assertEquals(1, requests.size)
        assertTrue(InterstitialAdManager.isLoading(ALL))
        assertFalse(InterstitialAdManager.isLoading(BACK))
    }

    @Test
    fun `empty whitelist holds automatic loads and removing it retains legacy behavior`() {
        arm(intervalMs = 0)
        InterstitialAutoBuffer.setPreloadScreens(mapOf(BACK to emptySet()))
        InterstitialAutoBuffer.setCurrentScreen("translate")
        advance(35_000)
        assertEquals(0, requests.size)
        InterstitialAutoBuffer.setCurrentScreen(null)
        InterstitialAutoBuffer.setPreloadScreens(emptyMap())
        main.idle()
        assertEquals("An unlisted placement needs no screen reports", 1, requests.size)
    }

    @Test
    fun `whitelist is a snapshot and updates reuse elapsed eligibility`() {
        arm()
        val screens = mutableSetOf("translate")
        val whitelist = mutableMapOf(BACK to screens)
        InterstitialAutoBuffer.setPreloadScreens(whitelist)
        screens += "home"
        whitelist.clear()
        InterstitialAutoBuffer.setCurrentScreen("home")
        advance(35_000)
        assertEquals("External collection changes must not silently open the gate", 0, requests.size)
        InterstitialAutoBuffer.setPreloadScreens(mapOf(BACK to setOf("home")))
        main.idle()
        assertEquals("Changing the whitelist must not restart the clock", 1, requests.size)
    }

    @Test
    fun `eligible screen still respects foreground and consent gates`() {
        arm()
        ConsentCenter.setHostConsent(false, false)
        advance(35_000)
        InterstitialAutoBuffer.setCurrentScreen("translate")
        main.idle()
        assertEquals(0, requests.size)
        controller.pause().stop()
        advance(800)
        ConsentCenter.setHostConsent(true, false)
        InterstitialAutoBuffer.setCurrentScreen("camera")
        main.idle()
        assertEquals("A screen report cannot force a request in background", 0, requests.size)
        controller.restart().start().resume().visible()
        advance(1)
        assertEquals("Foreground return keeps elapsed time and current eligibility", 1, requests.size)
    }

    private fun arm(taps: Int = 0, intervalMs: Long = 30_000L) {
        InterstitialAutoBuffer.setPreloadScreens(mapOf(BACK to setOf("translate", "camera")))
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(BACK), placements = listOf(BACK),
            tapThresholds = mapOf(BACK to taps), intervalMsByPlacement = mapOf(BACK to intervalMs),
            tickMs = 60_000L,
        ))
        InterstitialAutoBuffer.start(host)
    }

    private fun click(placement: String = BACK, timeoutMs: Long = 5_000): Outcome = Outcome().also {
        InterstitialAdManager.loadAndShow(host, placement, listOf(UNIT), it,
            InterLoadAndShowOptions(allowWaitForAutoBuffer = true, timeoutMs = timeoutMs))
    }

    private fun fill(index: Int = requests.lastIndex): Int02VendorAd = Int02VendorAd(UNIT).also {
        vendors += it
        requests[index].onAdLoaded(it)
        main.idle()
    }

    private fun advance(ms: Long) = main.idleFor(ms, TimeUnit.MILLISECONDS)

    private fun advanceUntil(timeMs: Long) = advance((timeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))

    private class Outcome : InterShowCallback() {
        var completed = 0
        val skipped = mutableListOf<AdSkipReason>()
        override fun onComplete() { completed++ }
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
    }

    companion object {
        private const val ALL = "inter_all"
        private const val BACK = "inter_back"
        private const val UNIT = "content-unit"
    }
}
