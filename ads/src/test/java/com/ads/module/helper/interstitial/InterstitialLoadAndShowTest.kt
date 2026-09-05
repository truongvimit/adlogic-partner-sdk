package com.ads.module.helper.interstitial

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.LoadAdError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/** Public manager/ERain/Admob flow; reuse only INT-02's external GMA/bootstrap boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialLoadAndShowTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val vendorAds = mutableListOf<Int02VendorAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAdManager.releaseAll()
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            setFacebookClientToken("int01-test-client-token")
        })
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        vendorAds.filter { it.hosts.isNotEmpty() }.forEach {
            it.callback.onAdDismissedFullScreenContent()
        }
        InterstitialAdManager.releaseAll()
        ShadowDialog.getLatestDialog()?.dismiss()
        ConsentCenter.setHostConsent(false, false)
        if (::controller.isInitialized && activity.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        AppOpenManager.getInstance().setInterstitialShowing(false)
        requests.clear()
    }

    @Test
    fun `ready fill uses existing show and load timeout cannot cut short real dismissal`() {
        val raw = newVendor()
        loadAndFill(raw)
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result,
            InterLoadAndShowOptions(timeoutMs = 50))
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, requests.size)
        assertEquals(0, result.completed)
        mainLooper.idleFor(9, TimeUnit.SECONDS)
        assertEquals("The wait deadline does not dismiss a shown ad", 0, result.completed)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.closed)
        assertEquals(1, result.completed)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `cold fill keeps captured UnderAd next before vendor show`() {
        val result = RecordingShow()
        val options = InterLoadAndShowOptions(nextAction = InterNextAction.UnderAd)
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result, options)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(waitingDialog.isShowing)
        assertEquals(1, requests.size)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        val raw = newVendor().apply {
            beforeShow = { assertEquals("UnderAd advances before the vendor invocation", 1, result.completed) }
        }
        requests.single().onAdLoaded(raw)
        mainLooper.idle()
        assertFalse("Load UI is gone before show UI owns the screen", waitingDialog.isShowing)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, result.completed)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.closed)
        assertEquals(1, result.completed)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `joining preload keeps its persistent loaded and clicked listener`() {
        var loaded = 0
        var clicked = 0
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded++ }
            override fun onAdClicked() { clicked++ }
        })
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        assertEquals(1, requests.size)
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, loaded)
        assertEquals(listOf(activity), raw.hosts)
        raw.callback.onAdClicked()
        assertEquals(1, clicked)
        assertEquals(1, result.clicked)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.completed)
    }

    @Test
    fun `eight second UI timeout permits a later valid fill to cache without auto show`() {
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        mainLooper.idleFor(7, TimeUnit.SECONDS)
        assertEquals(0, result.completed)
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(waitingDialog.isShowing)
        assertTrue("UI timeout did not cancel the existing request", InterstitialAdManager.isLoading(PLACEMENT))
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, raw.hosts.size)
        assertEquals(1, result.completed)
        val retry = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, retry)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, requests.size)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, retry.completed)
        assertEquals(1, result.completed)
    }

    @Test
    fun `fill received after Home completes wait but keeps ad for an explicit return trigger`() {
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        controller.pause().stop()
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(waitingDialog.isShowing)
        assertEquals(0, raw.hosts.size)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        controller.restart().start().resume().visible()
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertEquals("Return alone cannot auto-show", 0, raw.hosts.size)
        val retry = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), retry)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, requests.size)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, retry.completed)
        assertEquals(1, result.completed)
    }

    @Test
    fun `destroy removes waiter immediately and late fill cannot target the dead host`() {
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        controller.pause().stop().destroy()
        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(waitingDialog.isShowing)
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(9, TimeUnit.SECONDS)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, raw.hosts.size)
        assertEquals(1, result.completed)
    }

    @Test
    fun `two waiters share a request and only one can spend its fill`() {
        val first = RecordingShow()
        val second = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), first)
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), second)
        assertEquals(1, requests.size)
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(0, first.completed)
        assertEquals(listOf(AdSkipReason.NOT_READY), second.skipped)
        assertEquals(1, second.completed)
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, first.completed)
        assertEquals(1, second.completed)
    }

    @Test
    fun `placement off and UA gates do not spend a ready ad`() {
        val raw = newVendor()
        val buffered = loadAndFill(raw)
        listOf(
            InterLoadAndShowOptions(enabled = false) to AdSkipReason.DISABLED_CONFIG,
            InterLoadAndShowOptions(passesUaGate = false) to AdSkipReason.UA_GATE,
        ).forEach { (options, expected) ->
            val result = RecordingShow()
            InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result, options)
            assertEquals(listOf(expected), result.skipped)
            assertEquals(1, result.completed)
        }
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertSame(raw, buffered.interstitialAd)
        assertEquals(1, requests.size)
        assertEquals(0, raw.hosts.size)
    }

    @Test
    fun `denied authority starts no request and authority revoked during wait prevents presentation`() {
        ConsentCenter.setHostConsent(false, false)
        val denied = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), denied)
        assertEquals(listOf(AdSkipReason.CONSENT_NOT_GRANTED), denied.skipped)
        assertEquals(1, denied.completed)
        assertEquals(0, requests.size)
        ConsentCenter.setHostConsent(true, false)
        val waiting = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), waiting)
        ConsentCenter.setHostConsent(false, false)
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(9, TimeUnit.SECONDS)
        assertEquals(listOf(AdSkipReason.CONSENT_NOT_GRANTED), waiting.skipped)
        assertEquals(1, waiting.completed)
        assertEquals(0, raw.hosts.size)
    }

    @Test
    fun `frequency blocked cold trigger does not waste a new request`() {
        val raw = newVendor()
        loadAndFill(raw)
        InterstitialAdManager.show(activity, PLACEMENT, RecordingShow())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        raw.callback.onAdDismissedFullScreenContent()
        ERainAd.getInstance().setIntervalInterstitialAd(60)
        val blocked = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), blocked)
        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), blocked.skipped)
        assertEquals(1, blocked.completed)
        assertEquals(1, requests.size)
        assertFalse(InterstitialAdManager.isLoading(PLACEMENT))
    }

    @Test
    fun `no fill settles once and cannot later timeout again`() {
        var preloadFailures = 0
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onAdFailedToLoad(error: LoadAdError?) { preloadFailures++ }
        })
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        requests.single().onAdFailedToLoad(LoadAdError(3, "no fill", "com.google.android.gms.ads", null, null))
        mainLooper.idleFor(9, TimeUnit.SECONDS)
        assertEquals(1, preloadFailures)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(waitingDialog.isShowing)
        assertFalse(InterstitialAdManager.isLoading(PLACEMENT))
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
    }

    @Test
    fun `release ends a waiting trigger but does not cancel the existing fill request`() {
        val result = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), result)
        val waitingDialog = requireNotNull(ShadowDialog.getLatestDialog())
        InterstitialAdManager.release(PLACEMENT)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(waitingDialog.isShowing)
        val raw = newVendor()
        requests.single().onAdLoaded(raw)
        mainLooper.idleFor(9, TimeUnit.SECONDS)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, raw.hosts.size)
        assertEquals(1, result.completed)
    }

    @Test
    fun `reentrant B waiter cannot receive A terminal callback`() {
        val oldWaiter = RecordingShow()
        val newWaiter = RecordingShow()
        val persistentShow = RecordingShow()
        var firstFill = true
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) {
                if (!firstFill) return
                firstFill = false
                InterstitialAdManager.show(activity, PLACEMENT, persistentShow)
                InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), newWaiter)
            }
        })
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), oldWaiter)
        val rawA = newVendor()
        requests[0].onAdLoaded(rawA)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), rawA.hosts)
        assertEquals(listOf(AdSkipReason.NOT_READY), oldWaiter.skipped)
        assertEquals(1, oldWaiter.completed)
        assertEquals("B still waits for its own fill", 0, newWaiter.completed)
        assertEquals(2, requests.size)
        rawA.callback.onAdDismissedFullScreenContent()
        val rawB = newVendor()
        requests[1].onAdLoaded(rawB)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), rawB.hosts)
        assertTrue(newWaiter.skipped.isEmpty())
        rawB.callback.onAdDismissedFullScreenContent()
        assertEquals(1, newWaiter.completed)
        assertEquals(1, oldWaiter.completed)
    }

    @Test
    fun `release from persistent fill callback cannot let old waiter spend replacement B`() {
        assertReleaseDuringDeliveryKeepsReplacement { InterstitialAdManager.release(PLACEMENT) }
    }

    @Test
    fun `releaseAll from persistent fill callback cannot let old waiter spend replacement B`() {
        assertReleaseDuringDeliveryKeepsReplacement { InterstitialAdManager.releaseAll() }
    }

    private fun assertReleaseDuringDeliveryKeepsReplacement(release: () -> Unit) {
        val rawA = newVendor()
        val rawB = newVendor()
        var bufferedB: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) {
                release()
                InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
                    override fun onApInterstitialLoad(ad: ApInterstitialAd?) { bufferedB = ad }
                })
                requests.last().onAdLoaded(rawB)
            }
        })
        val oldWaiter = RecordingShow()
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), oldWaiter)
        requests[0].onAdLoaded(rawA)
        mainLooper.idleFor(9, TimeUnit.SECONDS)

        assertEquals(listOf(AdSkipReason.NOT_READY), oldWaiter.skipped)
        assertEquals(1, oldWaiter.completed)
        assertEquals(0, rawA.hosts.size)
        assertEquals("A's cancelled trigger must not consume B", 0, rawB.hosts.size)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertSame(rawB, requireNotNull(bufferedB).interstitialAd)
        assertEquals(2, requests.size)
    }

    private fun newVendor() = Int02VendorAd(UNIT).also { vendorAds += it }

    private fun loadAndFill(raw: Int02VendorAd): ApInterstitialAd {
        var loaded: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
        })
        requests.last().onAdLoaded(raw)
        mainLooper.idle()
        return requireNotNull(loaded)
    }

    private class RecordingShow : InterShowCallback() {
        var completed = 0
        var closed = 0
        var clicked = 0
        val skipped = mutableListOf<AdSkipReason>()
        override fun onClosed() { closed++ }
        override fun onClicked() { clicked++ }
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
        override fun onComplete() { completed++ }
    }

    companion object {
        private const val PLACEMENT = "int01-load-and-show"
        private const val UNIT = "int01-test-unit"
    }
}
