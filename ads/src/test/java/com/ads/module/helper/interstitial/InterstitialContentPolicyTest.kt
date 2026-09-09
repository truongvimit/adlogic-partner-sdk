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
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit

/** Public content flow. Only vendor requests/bootstrap and the clock are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialContentPolicyTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var host: Int02Activity
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val vendors = mutableListOf<Int02VendorAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
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
    fun `opted managed click joins preload and caps UI wait at five seconds`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf(ALL)))
        InterstitialAutoBuffer.start(host)
        InterstitialAdManager.load(host, ALL, listOf(UNIT))
        val clickedAt = SystemClock.elapsedRealtime()
        val outcome = click(timeoutMs = 99_000)
        assertEquals("An in-flight preload must be awaited", 0, outcome.completed)
        assertEquals(1, requests.size)
        advanceUntil(clickedAt + 4_999)
        assertEquals(0, outcome.completed)
        advance(1)
        assertEquals(1, outcome.completed)
        assertEquals(listOf(AdSkipReason.NOT_READY), outcome.skipped)
        assertTrue(InterstitialAdManager.isLoading(ALL))
        val ad = fill()
        advance(1_000)
        assertTrue(InterstitialAdManager.isReady(ALL))
        assertTrue(ad.hosts.isEmpty())
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `two taps allow preload at 28 seconds but a fresh action is required to show at 30`() {
        arm()
        assertEquals(1, click().completed)
        assertEquals(1, click().completed)
        advance(27_999)
        assertTrue(requests.isEmpty())
        advance(1)
        assertEquals(2, requests.size)
        val ad = fill(0)
        assertFalse(InterstitialAdManager.canShow(host, ALL))
        advance(2_000)
        assertTrue(ad.hosts.isEmpty())
        assertTrue(InterstitialAdManager.canShow(host, ALL))
        val outcome = click()
        advance(800)
        assertEquals(listOf(host), ad.hosts)
        assertEquals(0, outcome.completed)
        ad.callback.onAdDismissedFullScreenContent()
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `prepare rejection retains taps while actual shown resets them`() {
        arm(0)
        click()
        val first = click()
        val ad = fill()
        controller.pause()
        advance(800)
        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), first.skipped)
        assertTrue(ad.hosts.isEmpty())
        controller.resume()
        main.idle()
        assertTrue("No ad appeared, so both tap gates must remain satisfied",
            InterstitialAdManager.canShow(host, ALL))
        click()
        advance(800)
        assertEquals(listOf(host), ad.hosts)
        ad.callback.onAdShowedFullScreenContent()
        ad.callback.onAdDismissedFullScreenContent()
        val firstAfterShow = click()
        assertEquals("The first action after a real ad must not wait or show", 1, firstAfterShow.completed)
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), firstAfterShow.skipped)
        val secondAfterShow = click()
        assertEquals(0, secondAfterShow.completed)
        assertTrue(InterstitialAdManager.isLoading(ALL))
    }

    @Test
    fun `remote disabling the placement during preparation prevents vendor show`() {
        arm(0)
        click()
        val outcome = click()
        val ad = fill()
        AdRemoteConfig.initializeFromJson("""{"inter_all":{"id":"content-unit","isEnable":false}}""")
        advance(800)
        assertTrue("Permission must be rechecked after the prepare delay", ad.hosts.isEmpty())
        assertEquals(listOf(AdSkipReason.DISABLED_CONFIG), outcome.skipped)
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `gate revoked while waiting settles the action even if it is enabled before fill`() {
        arm(0)
        click()
        val outcome = click()
        AdRemoteConfig.initializeFromJson("""{"inter_all":{"id":"content-unit","isEnable":false}}""")
        main.idle()
        assertEquals(listOf(AdSkipReason.DISABLED_CONFIG), outcome.skipped)
        assertEquals(1, outcome.completed)
        AdRemoteConfig.initializeFromJson("""{"inter_all":{"id":"content-unit","isEnable":true}}""")
        val ad = fill()
        advance(800)
        assertTrue(ad.hosts.isEmpty())
        assertTrue(InterstitialAdManager.isReady(ALL))
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `offline click can join an existing request without issuing a new network request`() {
        arm(1_000)
        click()
        click()
        advance(1_000)
        assertEquals(2, requests.size)
        val connectivity = host.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, NetworkCapabilities())
        val outcome = click()
        assertEquals("Network availability only gates a new request", 0, outcome.completed)
        assertEquals(2, requests.size)
        val ad = fill(0)
        advance(800)
        assertEquals(listOf(host), ad.hosts)
    }

    @Test
    fun `failure keeps the full retry interval without delaying the other placement`() {
        arm(10_000)
        click()
        click()
        advance(8_000)
        assertEquals(2, requests.size)
        requests[0].onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        fill(1)
        advance(2_000)
        assertTrue(InterstitialAdManager.canShow(host, BACK))
        assertFalse(InterstitialAdManager.canShow(host, ALL))
        advance(7_999)
        assertEquals("Retry must not use the two-second preload lead", 2, requests.size)
        advance(1)
        assertEquals(3, requests.size)
    }

    @Test
    fun `closing all leaves back clock eligible but the next action cannot show another ad`() {
        arm()
        click()
        click()
        advance(28_000)
        val all = fill(0)
        val back = fill(1)
        advance(2_000)
        click()
        advance(800)
        all.callback.onAdShowedFullScreenContent()
        all.callback.onAdDismissedFullScreenContent()
        assertEquals(0L, InterstitialFrequency.remainingMs(host, BACK))
        val blocked = click(BACK)
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), blocked.skipped)
        val allowed = click(BACK)
        advance(800)
        assertEquals(listOf(host), back.hosts)
        back.callback.onAdShowedFullScreenContent()
        back.callback.onAdDismissedFullScreenContent()
        assertEquals(1, allowed.completed)
        assertTrue("Back's close must not restart ALL's 30-second clock",
            InterstitialFrequency.remainingMs(host, ALL) in 29_000..29_200)
    }

    @Test
    fun `zero and negative budgets skip empty cache without starting an invocation request`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf(ALL)))
        InterstitialAutoBuffer.start(host)
        for (budget in listOf(0L, -10L)) {
            val result = click(timeoutMs = budget)
            assertEquals(1, result.completed)
            assertTrue(requests.isEmpty())
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        }
        InterstitialAdManager.load(host, ALL, listOf(UNIT))
        val ad = fill()
        val result = click(timeoutMs = 0)
        advance(800)
        assertEquals(listOf(host), ad.hosts)
        assertEquals(0, result.completed)
    }

    @Test
    fun `fill at the deadline loses even when the timeout runnable has not executed`() {
        arm(0)
        click()
        val clickedAt = SystemClock.elapsedRealtime()
        val outcome = click()
        ShadowSystemClock.advanceBy(clickedAt + 5_000 - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
        val ad = fill()
        advance(800)
        assertEquals(1, outcome.completed)
        assertTrue(ad.hosts.isEmpty())
        assertTrue(InterstitialAdManager.isReady(ALL))
    }

    @Test
    fun `background cancels the wait and returning cannot replay the action`() {
        arm(0)
        click()
        val outcome = click()
        controller.pause().stop()
        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), outcome.skipped)
        controller.start().resume()
        val ad = fill()
        advance(800)
        assertEquals(1, outcome.completed)
        assertTrue(ad.hosts.isEmpty())
    }

    @Test
    fun `second tap in preload window only loads and never reserves a future show`() {
        arm()
        click()
        advance(29_000)
        assertEquals("BACK may preload without a placement tap threshold", 1, requests.size)
        val skipped = click()
        assertEquals(1, skipped.completed)
        advance(1)
        assertEquals(2, requests.size)
        val ad = fill(1)
        advance(1_000)
        assertTrue(ad.hosts.isEmpty())
        click()
        advance(800)
        assertEquals(listOf(host), ad.hosts)
    }

    @Test
    fun `partner flag gates preload and click from the same live source`() {
        var enabled = false
        arm(0, isPlacementEnabled = { enabled })
        click()
        click()
        advance(10_000)
        assertTrue(requests.isEmpty())
        enabled = true
        InterstitialAutoBuffer.onGateChanged()
        advance(1)
        assertEquals(2, requests.size)
        val pending = click()
        enabled = false
        InterstitialAutoBuffer.onGateChanged()
        assertEquals(listOf(AdSkipReason.DISABLED_CONFIG), pending.skipped)
        assertEquals(1, pending.completed)
    }

    @Test
    fun `zero interval failures retry at idle cadence without a no fill loop`() {
        arm(0)
        click()
        val outcome = click()
        requests[0].onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        assertEquals(1, outcome.completed)
        advance(1)
        assertEquals(2, requests.size)
        requests[1].onAdFailedToLoad(LoadAdError(3, "no fill", "test", null, null))
        advance(29_998)
        assertEquals(2, requests.size)
        advance(2)
        assertEquals(4, requests.size)
    }

    @Test
    fun `disabled due back cannot delay all preload with a different interval`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(ALL, BACK), placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 2), intervalMsByPlacement = mapOf(ALL to 30_000L, BACK to 0L),
            isPlacementEnabled = { it != BACK },
        ))
        InterstitialAutoBuffer.start(host)
        click()
        click()
        advance(27_999)
        assertTrue(requests.isEmpty())
        advance(1)
        assertEquals(1, requests.size)
        assertTrue(InterstitialAdManager.isLoading(ALL))
        assertFalse(InterstitialAdManager.isLoading(BACK))
    }

    private fun arm(intervalMs: Long = 30_000, isPlacementEnabled: (String) -> Boolean = { true }) {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(ALL, BACK),
            placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 2),
            intervalMsByPlacement = mapOf(ALL to intervalMs, BACK to intervalMs),
            isPlacementEnabled = isPlacementEnabled,
        ))
        InterstitialAutoBuffer.start(host)
    }

    private fun click(placement: String = ALL, timeoutMs: Long = 5_000): Outcome = Outcome().also {
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
