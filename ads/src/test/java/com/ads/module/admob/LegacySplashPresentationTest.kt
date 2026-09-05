package com.ads.module.admob

import android.app.Activity
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
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterstitialPresentationActivity
import com.ads.module.helper.interstitial.InterstitialPresentationApplication
import com.facebook.FacebookSdk
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** Retained public splash APIs with real config, Activity/dialog and only external GMA fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = InterstitialPresentationApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LegacySplashPresentationTest {
    private lateinit var controller: ActivityController<InterstitialPresentationActivity>
    private lateinit var activity: InterstitialPresentationActivity
    private lateinit var vendorLoader: MockedStatic<InterstitialAd>
    private val pending = mutableListOf<PendingLoad>()
    private val vendorAds = mutableListOf<VendorAd>()
    private val shownUnits = mutableListOf<String>()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val sdk get() = Admob.getInstance()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        Mockito.mockStatic(MobileAds::class.java).use {
            Mockito.mockStatic(FacebookSdk::class.java).use {
                ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                    setFacebookClientToken("test-client-token")
                })
            }
        }
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        sdk.setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        vendorLoader = Mockito.mockStatic(InterstitialAd::class.java) { invocation ->
            if (invocation.method.name == "load") {
                pending += PendingLoad(invocation.getArgument(1), invocation.getArgument(3))
            }
            null
        }
        controller = Robolectric.buildActivity(InterstitialPresentationActivity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    @After
    fun tearDown() {
        // Let real reservation expiry clean up even when a failing admission hook escaped its task.
        // Dispatched ads remain owned until their actual external terminal callbacks below.
        if (::controller.isInitialized) {
            SystemClock.sleep(91_000)
            mainLooper.idle()
        }
        finishInvokedVendorAds()
        sdk.setOpenActivityAfterShowInterAds(false)
        if (::controller.isInitialized && !activity.isFinishing && !activity.isDestroyed) {
            assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            // Raw splash caches outlive a caller. Consume unused test fills through real public
            // presentation and GMA terminal callbacks so the next test starts without stale tiers.
            if (sdk.interstitialSplash != null) {
                sdk.onShowSplash(activity, AdCallback())
                mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
                finishInvokedVendorAds()
            }
            repeat(4) {
                sdk.onShowSplashPriority4(activity, AdCallback())
                mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
                finishInvokedVendorAds()
            }
        }
        ShadowDialog.getLatestDialog()?.dismiss()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        AppOpenManager.getInstance().releaseCachedAds()
        sdk.setOpenActivityAfterShowInterAds(false)
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        if (::vendorLoader.isInitialized) vendorLoader.close()
    }

    @Test
    fun `explicit B is rejected while A prepares and cannot replace A captured ad`() {
        val first = vendorAd("splash-a")
        val second = vendorAd("splash-b")
        val resultA = RecordingCallback()
        val rejectedB = RecordingCallback()
        sdk.onShowSplash(activity, resultA, first.raw)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        val dialogA = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialogA.isShowing)

        sdk.onShowSplash(activity, rejectedB, second.raw)

        assertEquals(listOf(AdSkipReason.PRESENTATION_BUSY), rejectedB.rejected)
        assertEquals(1, rejectedB.next)
        assertEquals(0, rejectedB.failed)
        assertEquals(0, rejectedB.closed)
        assertSame(dialogA, ShadowDialog.getLatestDialog())
        assertTrue(dialogA.isShowing)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals(listOf("splash-a"), shownUnits)
        assertEquals(1, first.shows)
        assertEquals(0, second.shows)
        first.callbacks().onAdShowedFullScreenContent()
        first.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, resultA.next)
        assertEquals(1, resultA.closed)

        val retryB = RecordingCallback()
        sdk.onShowSplash(activity, retryB, second.raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf("splash-a", "splash-b"), shownUnits)
        second.callbacks().onAdShowedFullScreenContent()
        second.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, retryB.next)
        assertEquals(1, retryB.closed)
        assertTrue(retryB.rejected.isEmpty())
        assertEquals(1, rejectedB.next)
    }

    @Test
    fun `UnderAd mode captured before preparation keeps next before show after global mode changes`() {
        sdk.setOpenActivityAfterShowInterAds(true)
        val order = mutableListOf<String>()
        val ad = vendorAd("under-ad") { order += "vendor.show" }
        val callback = object : AdCallback() {
            override fun onNextAction() { order += "next" }
            override fun onAdClosed() { order += "closed" }
        }
        sdk.onShowSplash(activity, callback, ad.raw)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        sdk.setOpenActivityAfterShowInterAds(false)
        mainLooper.idleFor(699, TimeUnit.MILLISECONDS)
        assertTrue(order.isEmpty())
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf("next", "vendor.show"), order)

        ad.callbacks().onAdShowedFullScreenContent()
        ad.callbacks().onAdDismissedFullScreenContent()
        ad.callbacks().onAdDismissedFullScreenContent()
        assertEquals(listOf("next", "vendor.show", "closed"), order)
    }

    @Test
    fun `AfterDismiss mode captured before preparation still waits after global mode changes`() {
        val order = mutableListOf<String>()
        val ad = vendorAd("after-dismiss") { order += "vendor.show" }
        val callback = object : AdCallback() {
            override fun onNextAction() { order += "next" }
            override fun onAdClosed() { order += "closed" }
        }
        sdk.onShowSplash(activity, callback, ad.raw)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        sdk.setOpenActivityAfterShowInterAds(true)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals(listOf("vendor.show"), order)

        ad.callbacks().onAdShowedFullScreenContent()
        ad.callbacks().onAdDismissedFullScreenContent()
        ad.callbacks().onAdDismissedFullScreenContent()
        assertEquals(listOf("vendor.show", "next", "closed"), order)
    }

    @Test
    fun `four tier splash releases failed High1 before High2 preparation and ignores duplicate failure`() {
        val units = listOf("splash-high1", "splash-high2", "splash-high3", "splash-normal")
        val ads = units.map { vendorAd(it) }
        sdk.loadInterSplashPriority4SameTime(activity,
            units[0], units[1], units[2], units[3], 0, 0, AdCallback())
        assertEquals(units, pending.map { it.unit })
        pending.forEachIndexed { index, request -> request.callback.onAdLoaded(ads[index].raw) }
        mainLooper.idle()
        val result = RecordingCallback()
        sdk.onShowSplashPriority4(activity, result)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(units[0]), shownUnits)
        assertEquals(0, result.next)
        val failedHigh1 = ads[0].callbacks()

        failedHigh1.onAdFailedToShowFullScreenContent(AdError(42, "High1 vendor failure", "test-vendor"))
        mainLooper.idle()
        val high2Dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue("High1 cleanup must finish before opening High2's dialog", high2Dialog.isShowing)
        assertEquals(1, result.priorityFailed)
        assertEquals(0, result.failed)
        assertEquals(0, result.next)
        assertEquals(0, result.closed)
        assertTrue(result.rejected.isEmpty())

        failedHigh1.onAdFailedToShowFullScreenContent(AdError(42, "duplicate High1 failure", "test-vendor"))
        assertEquals(1, result.priorityFailed)
        assertSame(high2Dialog, ShadowDialog.getLatestDialog())
        assertTrue(high2Dialog.isShowing)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(units[0], units[1]), shownUnits)
        assertEquals(1, ads[1].shows)
        ads[1].callbacks().onAdShowedFullScreenContent()
        ads[1].callbacks().onAdDismissedFullScreenContent()
        mainLooper.idle()

        assertEquals(1, result.next)
        assertEquals(1, result.closed)
        assertEquals(1, result.priorityFailed)
        assertEquals(0, result.failed)
        assertFalse(high2Dialog.isShowing)
        failedHigh1.onAdFailedToShowFullScreenContent(AdError(42, "obsolete High1 failure", "test-vendor"))
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(units[0], units[1]), shownUnits)
        assertEquals(1, result.next)
        assertEquals(1, result.closed)
        assertEquals(1, result.priorityFailed)
    }

    @Test
    fun `priority admission rejection forwards caller hook and leaves the same tier reusable`() {
        val first = loadPriorityFills(setOf(0)).getValue(0)
        var admissionChecks = 0
        val rejected = object : RecordingCallback() {
            override fun getAdShowSkipReason(): AdSkipReason {
                admissionChecks++
                return AdSkipReason.NOT_READY
            }
        }

        sdk.onShowSplashPriority4(activity, rejected)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertTrue("The priority adapter must consult its caller's admission hook", admissionChecks > 0)
        assertEquals(listOf(AdSkipReason.NOT_READY), rejected.rejected)
        assertEquals(1, rejected.next)
        assertEquals(0, rejected.failed)
        assertEquals(0, first.shows)
        val accepted = RecordingCallback()
        sdk.onShowSplashPriority4(activity, accepted)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, first.shows)
        first.callbacks().onAdShowedFullScreenContent()
        first.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, accepted.closed)
        assertEquals(1, accepted.next)
        assertEquals(4, pending.size)
    }

    @Test
    fun `delayed priority check preserves caller admission rejection and presented hooks`() {
        val normal = loadPriorityFills(setOf(3)).getValue(3)
        finishStandardWarmUp()
        var admissionChecks = 0
        val rejected = object : RecordingCallback() {
            override fun getAdShowSkipReason(): AdSkipReason {
                admissionChecks++
                return AdSkipReason.NOT_READY
            }
        }

        sdk.onCheckShowSplashPriority4WhenFail(activity, rejected, 20)
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)

        assertTrue("Delayed retry must retain the caller's admission hook", admissionChecks > 0)
        assertEquals(listOf(AdSkipReason.NOT_READY), rejected.rejected)
        assertEquals(1, rejected.next)
        assertEquals(0, normal.shows)
        var presented = 0
        val accepted = object : RecordingCallback() {
            override fun onAdPresented() { presented++ }
        }
        sdk.onCheckShowSplashPriority4WhenFail(activity, accepted, 20)
        sdk.setOpenActivityAfterShowInterAds(true)
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, normal.shows)
        assertEquals("Delayed retry must retain AfterDismiss chosen at the public call", 0, accepted.next)
        assertEquals(0, presented)
        normal.callbacks().onAdShowedFullScreenContent()
        normal.callbacks().onAdShowedFullScreenContent()
        assertEquals(1, presented)
        normal.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, accepted.next)
        assertEquals(1, accepted.closed)
    }

    @Test
    fun `delayed priority failure cannot clear loading started reentrantly by its caller`() {
        val normal = loadPriorityFills(setOf(3)).getValue(3)
        finishStandardWarmUp()
        val second = vendorAd("reentrant-standard-b")
        val resultB = RecordingCallback()
        val resultA = object : RecordingCallback() {
            override fun onAdFailedToShow(error: AdError?) {
                super.onAdFailedToShow(error)
                sdk.onShowSplash(activity, resultB, second.raw)
            }
        }
        sdk.onCheckShowSplashPriority4WhenFail(activity, resultA, 20)
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, normal.shows)

        normal.callbacks().onAdFailedToShowFullScreenContent(AdError(43, "normal failure", "test-vendor"))

        assertEquals(1, resultA.failed)
        assertEquals(1, resultA.next)
        assertTrue("A's wrapper must not clear B's preparation state after the callback", sdk.isShowLoadingSplash)
        val dialogB = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialogB.isShowing)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, second.shows)
        second.callbacks().onAdShowedFullScreenContent()
        second.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, resultB.next)
        assertEquals(1, resultB.closed)
    }

    @Test
    fun `High1 actual failure with unavailable tail forwards final failure once before next`() {
        val first = loadPriorityFills(setOf(0)).getValue(0)
        val order = mutableListOf<String>()
        val error = AdError(44, "only filled tier failed", "test-vendor")
        val callback = object : AdCallback() {
            override fun onAdPriorityFailedToShow(failure: AdError?) {
                assertSame(error, failure)
                order += "priority-failed"
            }
            override fun onAdFailedToShow(failure: AdError?) {
                assertSame(error, failure)
                order += "failed"
            }
            override fun onNextAction() { order += "next" }
        }
        sdk.onShowSplashPriority4(activity, callback)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, first.shows)

        first.callbacks().onAdFailedToShowFullScreenContent(error)
        first.callbacks().onAdFailedToShowFullScreenContent(error)

        assertEquals(listOf("priority-failed", "failed", "next"), order)
        assertFalse(sdk.isShowLoadingSplash)
    }

    @Test
    fun `checked exception in delayed admission rejects releases and preserves the captured fill`() {
        val first = vendorAd("delayed-admission-exception")
        var checks = 0
        val callback = object : RecordingCallback() {
            override fun getAdShowSkipReason(): AdSkipReason? {
                checks++
                if (checks > 1) throw Exception("host validity source failed")
                return null
            }
        }
        sdk.onShowSplash(activity, callback, first.raw)
        assertEquals(1, checks)

        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(AdSkipReason.PREPARATION_FAILED), callback.rejected)
        assertEquals(1, callback.next)
        assertEquals(0, callback.failed)
        assertEquals(0, first.shows)
        assertSame(first.raw, sdk.interstitialSplash)
        assertFalse(sdk.isShowLoadingSplash)
        val retry = RecordingCallback()
        sdk.onCheckShowSplashWhenFail(activity, retry, 20)
        sdk.setOpenActivityAfterShowInterAds(true)
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, first.shows)
        assertEquals("Standard retry must retain AfterDismiss chosen before scheduling", 0, retry.next)
        first.callbacks().onAdShowedFullScreenContent()
        first.callbacks().onAdDismissedFullScreenContent()
        assertEquals(1, retry.next)
        assertEquals(1, retry.closed)
        assertTrue(retry.rejected.isEmpty())
    }

    private fun loadPriorityFills(filled: Set<Int>): Map<Int, VendorAd> {
        val units = listOf("splash-high1", "splash-high2", "splash-high3", "splash-normal")
        val ads = filled.associateWith { vendorAd(units[it]) }
        val requestStart = pending.size
        sdk.loadInterSplashPriority4SameTime(activity,
            units[0], units[1], units[2], units[3], 0, 0, AdCallback())
        val requests = pending.drop(requestStart)
        assertEquals(units, requests.map { it.unit })
        requests.forEachIndexed { index, request ->
            val ad = ads[index]
            if (ad != null) request.callback.onAdLoaded(ad.raw)
            else request.callback.onAdFailedToLoad(LoadAdError(3, "no fill", "test-vendor", null, null))
        }
        mainLooper.idle()
        return ads
    }

    /** Complete an independent standard slot so the public retry checker can inspect filled tiers. */
    private fun finishStandardWarmUp() {
        val warmUp = vendorAd("standard-warm-up")
        sdk.onShowSplash(activity, AdCallback(), warmUp.raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, warmUp.shows)
        warmUp.callbacks().onAdShowedFullScreenContent()
        warmUp.callbacks().onAdDismissedFullScreenContent()
        assertFalse(sdk.isShowLoadingSplash)
    }

    private fun vendorAd(unit: String, onShow: () -> Unit = {}): VendorAd {
        val ad = VendorAd(Mockito.mock(InterstitialAd::class.java))
        Mockito.`when`(ad.raw.adUnitId).thenReturn(unit)
        Mockito.doAnswer { invocation ->
            ad.callback = invocation.getArgument(0)
            null
        }.`when`(ad.raw).setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
        Mockito.doAnswer { invocation ->
            assertSame(activity, invocation.getArgument<Activity>(0))
            ad.shows++
            shownUnits += unit
            onShow()
            null
        }.`when`(ad.raw).show(Mockito.any(Activity::class.java))
        vendorAds += ad
        return ad
    }

    private fun finishInvokedVendorAds() {
        vendorAds.toList().forEach { it.callback?.onAdDismissedFullScreenContent() }
    }

    private class VendorAd(val raw: InterstitialAd) {
        var callback: FullScreenContentCallback? = null
        var shows = 0
        fun callbacks(): FullScreenContentCallback = requireNotNull(callback)
    }

    private data class PendingLoad(val unit: String, val callback: InterstitialAdLoadCallback)

    private open class RecordingCallback : AdCallback() {
        var next = 0
        var closed = 0
        var failed = 0
        var priorityFailed = 0
        val rejected = mutableListOf<AdSkipReason>()
        override fun onNextAction() { next++ }
        override fun onAdClosed() { closed++ }
        override fun onAdFailedToShow(error: AdError?) { failed++ }
        override fun onAdPriorityFailedToShow(error: AdError?) { priorityFailed++ }
        override fun onAdShowRejected(reason: AdSkipReason) {
            rejected += reason
            super.onAdShowRejected(reason)
        }
    }
}
