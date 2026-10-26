package com.ads.module.helper.interstitial

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.consent.ConsentOptions
import com.ads.module.consent.PlatformUmpClient
import com.ads.module.consent.UmpClient
import com.google.android.ump.ConsentInformation
import io.trackkit.ConsentState
import org.mockito.Mockito.*
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

/** Public buffer/manager lifecycle, real waterfall and presentation; only GMA is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialBufferLifecycleTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var host: Int02Activity
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val ads = mutableListOf<Int02VendorAd>()
    private var premium = false

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialAdManager.releaseAll()
        AdRemoteConfig.initializeFromJson("""{
            "inter_all": {"ids":[{"id":"buffer-all-unit"}], "isEnable":true},
            "inter_back": {"ids":[{"id":"buffer-back-unit"}], "isEnable":true}
        }""")
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply { setFacebookClientToken("buffer-test") })
        // This suite exercises the shared group policy, independently of bundled defaults.
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"shared_config":true}}""")
        ERainAd.getInstance().setIntervalInterstitialAd(30)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        InterstitialFrequency.resetAll()
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        host = controller.get()
        main.idle()
        requests.clear()
    }

    @After
    fun tearDown() {
        AdBehavior.document.acceptSuccessfulFetch(null)
        InterstitialAutoBuffer.stop()
        ads.filter { it.hosts.isNotEmpty() }.forEach { it.callback.onAdDismissedFullScreenContent() }
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialAdManager.releaseAll()
        AdRemoteConfig.reset()
        ConsentCenter.reset(host)
        ConsentCenter.configure(ConsentOptions())
        ConsentCenter.umpClient = PlatformUmpClient
        ShadowDialog.getLatestDialog()?.dismiss()
        if (::controller.isInitialized && host.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        advance(800)
        requests.clear()
    }

    @Test
    fun `startup update hold prevents automatic buffer requests and allows them after release`() {
        com.ads.module.helper.AdGate.holdRequests().use {
            arm()
            advance(60_000)
            InterstitialAutoBuffer.topUpNow()
            main.idle()
            assertEquals(0, requests.size)
        }
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(2, requests.size)
    }

    @Test
    fun `remote rule switch only blocks its own placement and can reenable it`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"shared_config":true,"rules":{"inter_all":{"enabled":false}}}}""")
        arm(listOf(ALL))
        advance(30_000)
        assertEquals(0, requests.size)
        InterstitialAdManager.load(host, ALL, listOf("buffer-all-unit"))
        assertEquals(0, requests.size)
        InterstitialAdManager.load(host, "outside", listOf("outside-unit"))
        assertEquals(1, requests.size)
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"shared_config":true,"rules":{"inter_all":{"enabled":true}}}}""")
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(2, requests.size)
    }

    @Test
    fun `remote buffer timeout above five seconds remains captured until its deadline`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"shared_config":true},"interstitial":{"load_and_show":{"buffer_wait_timeout_ms":12000}}}""")
        arm(listOf(ALL))
        advance(30_000)
        assertEquals(1, requests.size)
        val result = Outcome()
        val deadline = SystemClock.elapsedRealtime() + 12_000
        InterstitialAdManager.loadAndShow(host, ALL, listOf("buffer-all-unit"), result)
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial_auto_buffer":{"shared_config":true},"interstitial":{"load_and_show":{"buffer_wait_timeout_ms":1000}}}""")
        advance(deadline - SystemClock.elapsedRealtime() - 1)
        assertEquals("Remote 12000ms must not be capped at 5000ms or changed mid-wait", 0, result.completed)
        advance(1)
        assertEquals(1, result.completed)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skipped)
    }

    @Test
    fun `UMP request authority loads both placements even when personalization is unknown`() {
        val information = umpAuthorityWithUnknownChoice(allowed = true)
        assertEquals(ConsentState.UNKNOWN, ConsentCenter.state.value)
        assertTrue(ConsentCenter.canRequestAds())
        assertFalse(ConsentCenter.canPersonalize())

        arm()
        advance(29_999)
        assertEquals("Consent authorization must not bypass the interval", 0, requests.size)
        advance(1)
        assertEquals("Both enabled placements must reach GMA when UMP authorizes requests", 2, requests.size)
        fill(0, ALL)
        fill(1, BACK)
        assertTrue(InterstitialAdManager.canShow(host, ALL))
        assertTrue(InterstitialAdManager.canShow(host, BACK))

        doReturn(false).`when`(information).canRequestAds()
        val denied = Outcome()
        InterstitialAdManager.show(host, ALL, denied)
        assertEquals(listOf(AdSkipReason.CONSENT_NOT_GRANTED), denied.skipped)
        assertEquals(1, denied.completed)
    }

    @Test
    fun `unknown choice without UMP authority does not request while update is pending`() {
        umpAuthorityWithUnknownChoice(allowed = false)
        arm()
        advance(60_000)
        assertFalse(ConsentCenter.canRequestAds())
        assertEquals(0, requests.size)
    }

    @Test
    fun `UMP timeout allows both placements without granting personalization`() {
        umpAuthorityWithUnknownChoice(allowed = false, timeoutMs = 20_000)
        arm()
        advance(19_999)
        assertFalse(ConsentCenter.canRequestAds())
        assertEquals(0, requests.size)
        advance(1)
        assertTrue(ConsentCenter.canRequestAds())
        assertEquals(ConsentState.UNKNOWN, ConsentCenter.state.value)
        assertFalse(ConsentCenter.canPersonalize())
        advance(10_000)
        assertEquals(2, requests.size)
    }

    @Test
    fun `remote disabling all tiers still prevents buffering with UMP authority`() {
        umpAuthorityWithUnknownChoice(allowed = true)
        AdRemoteConfig.initializeFromJson("""{
            "inter_all": {"ids":[{"id":"all-high"}, {"id":"buffer-all-unit"}], "isEnable":false},
            "inter_back": {"ids":[{"id":"back-high"}, {"id":"buffer-back-unit"}], "isEnable":false}
        }""")
        arm()
        advance(30_000)
        assertEquals("Disabled remote tiers must never reach GMA", 0, requests.size)
        AdRemoteConfig.initializeFromJson("""{
            "inter_all": {"ids":[{"id":"buffer-all-unit"}], "isEnable":true},
            "inter_back": {"ids":[{"id":"buffer-back-unit"}], "isEnable":false}
        }""")
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals("Only the enabled placement resumes loading", 1, requests.size)
        fill(0, ALL)
        assertFalse(InterstitialAdManager.isReady(BACK))
    }

    /** Replay the Pixel 5's UMP outputs through ConsentCenter, without overriding its state. */
    private fun umpAuthorityWithUnknownChoice(
        allowed: Boolean,
        timeoutMs: Long = 120_000,
    ): ConsentInformation {
        val information = mock(ConsentInformation::class.java)
        doReturn(ConsentInformation.ConsentStatus.UNKNOWN).`when`(information).getConsentStatus()
        doReturn(allowed).`when`(information).canRequestAds()
        ConsentCenter.umpClient = object : UmpClient {
            override fun consentInformation(context: Context) = information
            override fun loadForm(
                context: Context,
                onLoaded: (com.google.android.ump.ConsentForm) -> Unit,
                onError: (com.google.android.ump.FormError) -> Unit,
            ) = error("No form expected for this replay")
        }
        ConsentCenter.reset(host)
        // Keep authority-only scenarios pending beyond the buffer interval. Timeout behavior
        // has its own scenario using the SDK's normal deadline.
        ConsentCenter.configure(ConsentOptions(timeoutMs = timeoutMs, debug = false))
        ConsentCenter.request(host) { }
        return information
    }

    @Test
    fun `after ob3 dismissal gates the group for the remote interval and a fill is showable without refill`() {
        arm()
        val started = SystemClock.elapsedRealtime()
        advance(10_000)
        InterstitialAutoBuffer.start(host)
        repeat(3) {
            InterstitialAutoBuffer.topUpNow()
            InterstitialAdManager.load(host, ALL, listOf("buffer-all-unit"))
        }
        advance(started + 29_999 - SystemClock.elapsedRealtime())
        assertEquals(0, requests.size)
        advance(1)
        assertEquals(2, requests.size)
        val all = fill(0, ALL)
        fill(1, BACK)
        assertTrue(InterstitialAdManager.canShow(host, ALL))
        assertTrue(InterstitialAdManager.canShow(host, BACK))
        repeat(3) { InterstitialAutoBuffer.topUpNow() }
        advance(90_000)
        assertEquals("Keep unused fills across timer checks", 2, requests.size)
        assertTrue("A timer must never show", all.hosts.isEmpty())
        val outcome = Outcome()
        InterstitialAdManager.show(host, ALL, outcome)
        advance(800)
        assertEquals(listOf(host), all.hosts)
        assertEquals(0, outcome.completed)
        all.callback.onAdDismissedFullScreenContent()
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `closing all gates back and replacement loads without consuming ready back`() {
        arm()
        advance(30_000)
        val all = fill(0, ALL)
        val back = fill(1, BACK)
        val first = Outcome()
        InterstitialAdManager.show(host, ALL, first, nextAction = InterNextAction.UnderAd)
        advance(800)
        assertEquals(1, first.completed)
        assertFalse(InterstitialAdManager.canShow(host, BACK))
        InterstitialAutoBuffer.topUpNow()
        advance(40_000)
        assertEquals("Neither UnderAd next nor elapsed time can refill a live show", 2, requests.size)
        all.callback.onAdDismissedFullScreenContent()
        val closedAt = SystemClock.elapsedRealtime()
        val blocked = Outcome()
        InterstitialAdManager.show(host, BACK, blocked)
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), blocked.skipped)
        assertEquals(1, blocked.completed)
        assertTrue(InterstitialAdManager.isReady(BACK))
        InterstitialAdManager.load(host, ALL, listOf("buffer-all-unit"))
        InterstitialAutoBuffer.topUpNow()
        advance(10_000)
        all.callback.onAdDismissedFullScreenContent() // duplicate must not extend the group gate
        advance(closedAt + 29_999 - SystemClock.elapsedRealtime())
        assertEquals(2, requests.size)
        assertFalse(InterstitialAdManager.canShow(host, BACK))
        advance(1)
        assertEquals(3, requests.size)
        assertTrue(InterstitialAdManager.canShow(host, BACK))
        val second = Outcome()
        InterstitialAdManager.show(host, BACK, second)
        advance(800)
        assertEquals(listOf(host), back.hosts)
        back.callback.onAdDismissedFullScreenContent()
        assertEquals(1, second.completed)
    }

    @Test
    fun `final load failure gates entire group for exactly remote interval on every retry`() {
        arm()
        advance(30_000)
        fill(1, BACK)
        fail(0)
        repeat(2) { retry ->
            val requestCount = 2 + retry
            val failedAt = SystemClock.elapsedRealtime()
            assertTrue(InterstitialAdManager.isReady(BACK))
            assertFalse(InterstitialAdManager.canShow(host, BACK))
            InterstitialAutoBuffer.resetBackoff()
            InterstitialAdManager.load(host, ALL, listOf("buffer-all-unit"))
            advance(failedAt + 29_999 - SystemClock.elapsedRealtime())
            assertEquals(requestCount, requests.size)
            advance(1)
            assertEquals("No exponential extension on retry", requestCount + 1, requests.size)
            if (retry == 0) fail(requests.lastIndex)
        }
        fill(requests.lastIndex, ALL)
        assertTrue("Success must not start another gate", InterstitialAdManager.canShow(host, ALL))
        assertTrue(InterstitialAdManager.canShow(host, BACK))
    }

    @Test
    fun `background pauses the interval and retains late fills on foreground return`() {
        arm()
        advance(10_000)
        leaveProcess()
        val left = InterstitialFrequency.remainingMs(host)
        advance(60_000)
        assertEquals("Background time does not count toward the interval", left, InterstitialFrequency.remainingMs(host))
        InterstitialAdManager.load(host, ALL, listOf("buffer-all-unit"))
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(0, requests.size)
        returnToProcess()
        val resumed = InterstitialFrequency.remainingMs(host)
        assertTrue("Foreground resumes the interval where it stopped", resumed in left - 10..left)
        advance(resumed - 1)
        assertEquals(0, requests.size)
        advance(1)
        assertEquals(2, requests.size)
        leaveProcess()
        fill(0, ALL)
        fill(1, BACK)
        advance(60_000)
        assertEquals(2, requests.size)
        returnToProcess()
        advance(30_000)
        assertEquals(2, requests.size)
        assertTrue(InterstitialAdManager.canShow(host, ALL))
        assertTrue(InterstitialAdManager.canShow(host, BACK))
    }

    @Test
    fun `without an after ob3 dismissal start opens the group at once`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 0, BACK to 0),
        ))
        InterstitialAutoBuffer.start(host)
        advance(1_000)
        assertEquals(2, requests.size)
    }

    @Test
    fun `splash never stamps the group and after ob3 always shows and restarts it`() {
        arm()
        val armedAt = SystemClock.elapsedRealtime()
        advance(10_000)
        InterstitialAdManager.load(host, "inter_splash", listOf("outside-unit"))
        val splash = fill(0, "inter_splash")
        InterstitialAdManager.show(host, "inter_splash", Outcome())
        advance(800)
        assertEquals(listOf(host), splash.hosts)
        splash.callback.onAdDismissedFullScreenContent()
        advance(armedAt + 29_999 - SystemClock.elapsedRealtime())
        assertEquals(1, requests.size)
        advance(1)
        assertEquals("Splash dismissal must not postpone the group", 3, requests.size)
        val all = fill(1, ALL)
        fill(2, BACK)
        InterstitialAdManager.show(host, ALL, Outcome())
        advance(800)
        all.callback.onAdDismissedFullScreenContent()
        val allClosedAt = SystemClock.elapsedRealtime()
        advance(10_000)
        InterstitialAdManager.load(host, AFTER_OB, listOf("outside-unit"))
        val ob = fill(3, AFTER_OB)
        assertTrue("The group gate never caps after ob3", InterstitialAdManager.canShow(host, AFTER_OB))
        InterstitialAdManager.show(host, AFTER_OB, Outcome())
        advance(800)
        assertEquals(listOf(host), ob.hosts)
        advance(allClosedAt + 35_000 - SystemClock.elapsedRealtime())
        assertEquals("No refill while after ob3 is on screen", 4, requests.size)
        ob.callback.onAdDismissedFullScreenContent()
        val obClosedAt = SystemClock.elapsedRealtime()
        advance(obClosedAt + 29_999 - SystemClock.elapsedRealtime())
        assertEquals(4, requests.size)
        assertFalse(InterstitialAdManager.canShow(host, BACK))
        advance(1)
        assertEquals("The after ob3 dismissal restarts the group interval", 5, requests.size)
        assertTrue(InterstitialAdManager.canShow(host, BACK))
    }

    @Test
    fun `managed convenience show waits the full eight second default buffer timeout`() {
        arm()
        val gated = Outcome()
        InterstitialAdManager.loadAndShow(host, ALL, listOf("buffer-all-unit"), gated)
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), gated.skipped)
        assertEquals(1, gated.completed)
        assertEquals(0, requests.size)
        advance(30_000)
        val loading = Outcome()
        val clickedAt = SystemClock.elapsedRealtime()
        InterstitialAdManager.loadAndShow(host, ALL, listOf("buffer-all-unit"), loading)
        assertTrue(loading.skipped.isEmpty())
        assertEquals(0, loading.completed)
        advance(clickedAt + 7_999 - SystemClock.elapsedRealtime())
        assertTrue(loading.skipped.isEmpty())
        advance(1)
        assertEquals(listOf(AdSkipReason.NOT_READY), loading.skipped)
        assertEquals(1, loading.completed)
        assertEquals(2, requests.size)
        val raw = fill(0, ALL)
        fill(1, BACK)
        advance(10_000)
        assertTrue("A late fill must not replay the earlier navigation trigger", raw.hosts.isEmpty())
        assertEquals(1, loading.completed)
    }

    @Test
    fun `managed convenience show can opt out of waiting`() {
        arm()
        val gated = Outcome()
        InterstitialAdManager.loadAndShow(host, ALL, listOf("buffer-all-unit"), gated, InterLoadAndShowOptions(allowWaitForAutoBuffer = false))
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), gated.skipped)
        assertEquals(1, gated.completed)
        assertEquals(0, requests.size)
        advance(30_000)
        val loading = Outcome()
        InterstitialAdManager.loadAndShow(host, ALL, listOf("buffer-all-unit"), loading, InterLoadAndShowOptions(allowWaitForAutoBuffer = false))
        assertEquals(listOf(AdSkipReason.NOT_READY), loading.skipped)
        assertEquals(1, loading.completed)
        assertEquals(2, requests.size)
        val raw = fill(0, ALL)
        fill(1, BACK)
        advance(10_000)
        assertTrue("A late fill must not replay the earlier navigation trigger", raw.hosts.isEmpty())
        assertEquals(1, loading.completed)
    }

    @Test
    fun `stopping cancels queued topup and restarting preserves elapsed gate`() {
        arm()
        advance(10_000)
        InterstitialAutoBuffer.topUpNow()
        InterstitialAutoBuffer.stop()
        advance(30_000)
        assertEquals(0, requests.size)
        InterstitialAutoBuffer.start(host)
        advance(1_000)
        assertEquals(2, requests.size)
    }

    @Test
    fun `shortening remote interval rechecks an already eligible empty buffer promptly`() {
        arm()
        advance(10_000)
        ERainAd.getInstance().setIntervalInterstitialAd(5)
        advance(1_000)
        assertEquals("No additional five-second polling interval after gate already elapsed", 2, requests.size)
    }

    @Test
    fun `a failed high tier does not gate the group when base is still loading`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 0, BACK to 0),
        ))
        // Explicit loading uses the same group policy and the same waterfall as the buffer.
        AdRemoteConfig.initializeFromJson("{}")
        InterstitialAutoBuffer.start(host)
        advance(30_000)
        InterstitialAdManager.load(host, ALL, listOf("high-unit", "buffer-all-unit"))
        fail(0)
        assertEquals(2, requests.size)
        assertTrue(InterstitialAdManager.isLoading(ALL))
        InterstitialAdManager.load(host, BACK, listOf("buffer-back-unit"))
        assertEquals("An individual tier failure cannot start the final-failure gate", 3, requests.size)
        fill(1, ALL)
        fill(2, BACK)
        assertTrue(InterstitialAdManager.canShow(host, ALL))
    }

    @Test
    @Config(instrumentedPackages = ["com.ads.module.helper.CachedAd"])
    fun `interstitial cache keeps original expiry across repeated timer checks`() {
        arm(listOf(ALL))
        advance(30_000)
        fill(0, ALL)
        advance(TimeUnit.HOURS.toMillis(1) - 1)
        assertTrue(InterstitialAdManager.isReady(ALL))
        assertEquals(1, requests.size)
        advance(1)
        assertFalse(InterstitialAdManager.isReady(ALL))
        InterstitialAutoBuffer.topUpNow()
        main.idle()
        assertEquals(2, requests.size)
    }

    private fun leaveProcess() {
        controller.pause().stop()
        advance(800)
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    private fun returnToProcess() {
        controller.restart().start().resume().visible()
        main.idle()
    }

    private fun arm(placements: List<String> = listOf(ALL, BACK)) {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            placements = placements,
            tapThresholds = placements.associateWith { 0 },
        ))
        InterstitialAutoBuffer.start(host)
        dismissAfterOnboarding()
    }

    private fun advance(ms: Long) = main.idleFor(ms, TimeUnit.MILLISECONDS)

    private fun fill(index: Int, placement: String): Int02VendorAd {
        val unit = when (placement) {
            ALL -> "buffer-all-unit"
            BACK -> "buffer-back-unit"
            else -> "outside-unit"
        }
        val ad = Int02VendorAd(unit).also(ads::add)
        requests[index].onAdLoaded(ad)
        main.idle()
        assertTrue(InterstitialAdManager.isReady(placement))
        return ad
    }

    private fun fail(index: Int) {
        requests[index].onAdFailedToLoad(LoadAdError(3, "No fill", "test.gma", null, null))
        main.idle()
    }

    private class Outcome : InterShowCallback() {
        var completed = 0
        val skipped = mutableListOf<AdSkipReason>()
        override fun onComplete() { completed++ }
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
    }

    companion object {
        private const val ALL = "inter_all"
        private const val BACK = "inter_back"
    }
}
