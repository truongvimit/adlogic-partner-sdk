package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Public resume calls and real lifecycle/dialogs; only the external GMA loader/ad is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ResumeLifecycleApplication::class,
    shadows = [ResumeOwnershipDialogShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenPresentationOwnershipTest {
    private lateinit var app: Application
    private lateinit var manager: AppOpenManager
    private lateinit var vendor: VendorLoader
    private lateinit var activity: ActivityController<ComponentActivity>
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        ResumeOwnershipDialogShadow.beforeShow = null
        ResumeOwnershipDialogShadow.throwNextShow = false
        app = ApplicationProvider.getApplicationContext()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        mainLooper.idle()
        vendor = VendorLoader()
        manager = AppOpenManager(vendor)
        manager.setInterstitialShowing(false)
        manager.init(app, "resume-unit")
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        ResumeOwnershipDialogShadow.beforeShow = null
        ResumeOwnershipDialogShadow.throwNextShow = false
        manager.disableAppResume()
        vendor.ads.forEach { it.fullScreenContentCallback?.onAdDismissedFullScreenContent() }
        manager.setInterstitialShowing(false)
        ShadowDialog.getLatestDialog()?.dismiss()
        app.unregisterActivityLifecycleCallbacks(manager)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(manager)
        activity.pause().stop().destroy()
        mainLooper.idleFor(1, TimeUnit.SECONDS)
    }

    @Test
    fun `an invoked resume ad still excludes the next fill after ninety seconds without a terminal`() {
        val first = vendor.fill(0)
        manager.showResumeAdIfAvailable()
        assertEquals(1, first.showCount)
        manager.fetchResumeAd()
        val second = vendor.fill(1)

        mainLooper.idleFor(90_001, TimeUnit.MILLISECONDS)
        manager.showResumeAdIfAvailable()

        assertEquals(0, second.showCount)
        assertTrue(manager.isShowingAd)
        assertTrue(manager.isResumeAdAvailable())
        first.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        manager.showResumeAdIfAvailable()
        assertEquals(1, second.showCount)
    }

    @Test
    fun `expiry during vendor callback setup does not dispatch or consume the ready resume buffer`() {
        val ad = vendor.fill(0)
        var dismissed = 0
        var shown = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissed++ }
            override fun onAdShowedFullScreenContent() { shown++ }
        })
        ad.onCallbackInstalled = { ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(90_001)) }

        manager.showResumeAdIfAvailable()

        assertEquals(0, ad.showCount)
        assertEquals(0, shown)
        assertEquals(1, dismissed)
        assertFalse(manager.isShowingAd)
        assertTrue("A refused reservation must leave its uninvoked buffer available", manager.isResumeAdAvailable())
        ad.onCallbackInstalled = null
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.showCount)
    }

    @Test
    fun `a throwing host shown callback does not release a presented ad or report vendor failure`() {
        val first = vendor.fill(0)
        var failed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { throw IllegalStateException("Host callback failed") }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
        })

        runCatching { manager.showResumeAdIfAvailable() }

        assertEquals(1, first.showCount)
        assertEquals(0, failed)
        assertTrue(manager.isShowingAd)
        manager.fetchResumeAd()
        val second = vendor.fill(1)
        manager.showResumeAdIfAvailable()
        assertEquals(0, second.showCount)
    }

    @Test
    fun `a cosmetic dialog window failure does not prevent an authorized resume presentation`() {
        val ad = vendor.fill(0)
        var shown = 0
        var failed = 0
        var dismissed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { shown++ }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
            override fun onAdDismissedFullScreenContent() { dismissed++ }
        })
        ResumeOwnershipDialogShadow.throwNextShow = true

        manager.showResumeAdIfAvailable()

        assertEquals(1, ad.showCount)
        assertEquals(1, shown)
        assertEquals(0, failed)
        assertEquals(0, dismissed)
        assertTrue(manager.isShowingAd)
    }

    @Test
    fun `the resume reservation is visible before dialog show can reenter the manager`() {
        val ad = vendor.fill(0)
        var dialogEntries = 0
        ResumeOwnershipDialogShadow.beforeShow = {
            dialogEntries++
            assertTrue("The dialog cannot run before its reservation", manager.isShowingAd)
            manager.showResumeAdIfAvailable()
        }

        manager.showResumeAdIfAvailable()

        assertEquals(1, dialogEntries)
        assertEquals(1, ad.showCount)
        assertTrue(manager.isShowingAd)
    }

    @Test
    fun `dialog reentry replacing A with B rejects the captured attempt and leaves B for an explicit retry`() {
        val first = vendor.fill(0)
        lateinit var second: VendorAd
        var shown = 0
        var dismissed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { shown++ }
            override fun onAdDismissedFullScreenContent() { dismissed++ }
        })
        ResumeOwnershipDialogShadow.beforeShow = {
            assertTrue(manager.isShowingAd)
            manager.setAppResumeAdId("replacement-resume-unit")
            second = vendor.fill(1)
            assertEquals("replacement-resume-unit", second.adUnitId)
        }

        manager.showResumeAdIfAvailable()

        assertEquals(0, first.showCount)
        assertEquals("A's original opportunity must not silently dispatch replacement B", 0, second.showCount)
        assertEquals(0, shown)
        assertEquals(1, dismissed)
        assertFalse(manager.isShowingAd)
        assertTrue("B must stay buffered after A's identity check rejects the attempt", manager.isResumeAdAvailable())
        assertEquals(2, vendor.requestCount)

        manager.showResumeAdIfAvailable()

        assertEquals(0, first.showCount)
        assertEquals(1, second.showCount)
        assertEquals(1, shown)
        assertTrue(manager.isShowingAd)
        assertFalse(manager.isResumeAdAvailable())
        assertEquals(2, vendor.requestCount)
    }

    @Test
    fun `legacy false and cache invalidation cannot release the active resume presentation`() {
        val first = vendor.fill(0)
        manager.showResumeAdIfAvailable()
        manager.setInterstitialShowing(true)
        assertTrue(manager.isInterstitialShowing)
        manager.releaseCachedAds()
        mainLooper.idleFor(90_001, TimeUnit.MILLISECONDS)
        manager.setInterstitialShowing(false)
        assertFalse(manager.isInterstitialShowing)
        assertTrue(manager.isShowingAd)
        manager.fetchResumeAd()
        val second = vendor.fill(1)

        manager.showResumeAdIfAvailable()

        assertEquals(0, second.showCount)
        assertTrue(manager.isResumeAdAvailable())
        first.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        manager.showResumeAdIfAvailable()
        assertEquals(1, second.showCount)
    }

    @Test
    fun `terminal A can show B and late A callbacks cannot close B or its dialog`() {
        var shown = 0
        var dismissed = 0
        var failed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { shown++ }
            override fun onAdDismissedFullScreenContent() {
                dismissed++
                if (dismissed == 1) manager.showResumeAdIfAvailable()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
        })
        val first = vendor.fill(0)
        manager.showResumeAdIfAvailable()
        val oldCallback = first.fullScreenContentCallback!!
        manager.fetchResumeAd()
        val second = vendor.fill(1)

        oldCallback.onAdDismissedFullScreenContent()
        val dialogB = requireNotNull(ShadowDialog.getLatestDialog())
        oldCallback.onAdShowedFullScreenContent()
        oldCallback.onAdFailedToShowFullScreenContent(AdError(1, "late failure", "vendor"))
        oldCallback.onAdDismissedFullScreenContent()
        oldCallback.onAdImpression()
        oldCallback.onAdClicked()

        assertEquals(1, second.showCount)
        assertEquals(2, shown)
        assertEquals(1, dismissed)
        assertEquals(0, failed)
        assertSame(dialogB, ShadowDialog.getLatestDialog())
        assertTrue(dialogB.isShowing)
        assertTrue(manager.isShowingAd)
        manager.fetchResumeAd()
        val third = vendor.fill(2)
        manager.showResumeAdIfAvailable()
        assertEquals(0, third.showCount)
        second.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        manager.showResumeAdIfAvailable()
        assertEquals(1, third.showCount)
    }

    @Test
    fun `expired reservation cleanup can reenter and show the same unconsumed buffer`() {
        val ad = vendor.fill(0)
        var expiredCallback: FullScreenContentCallback? = null
        var dismissed = 0
        var shown = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                dismissed++
                if (dismissed == 1) manager.showResumeAdIfAvailable()
            }
            override fun onAdShowedFullScreenContent() { shown++ }
        })
        ad.onCallbackInstalled = {
            expiredCallback = ad.fullScreenContentCallback
            ad.onCallbackInstalled = null
            ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(90_001))
        }

        manager.showResumeAdIfAvailable()
        expiredCallback!!.onAdDismissedFullScreenContent()
        expiredCallback!!.onAdFailedToShowFullScreenContent(AdError(1, "late failure", "vendor"))
        expiredCallback!!.onAdShowedFullScreenContent()

        assertEquals(1, ad.showCount)
        assertEquals(1, dismissed)
        assertEquals(1, shown)
        assertTrue(manager.isShowingAd)
        assertFalse(manager.isResumeAdAvailable())
    }

    @Test
    fun `a host finished during vendor setup is rejected without consuming the buffer`() {
        val ad = vendor.fill(0)
        var dismissed = 0
        var failed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissed++ }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
        })
        ad.onCallbackInstalled = { activity.get().finish() }

        manager.showResumeAdIfAvailable()

        assertEquals(0, ad.showCount)
        assertEquals(1, dismissed)
        assertEquals(0, failed)
        assertFalse(manager.isShowingAd)
        assertTrue(manager.isResumeAdAvailable())
    }

    @Test
    fun `vendor callback setup failure completes before show without a vendor failure or refill`() {
        val ad = vendor.fill(0)
        var dismissed = 0
        var failed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissed++ }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
        })
        ad.onCallbackInstalled = { throw IllegalStateException("Vendor callback setup failed") }

        manager.showResumeAdIfAvailable()

        assertEquals(0, ad.showCount)
        assertEquals(0, failed)
        assertEquals(1, dismissed)
        assertEquals(1, vendor.requestCount)
        assertFalse(manager.isShowingAd)
        assertTrue(manager.isResumeAdAvailable())
    }

    private class VendorLoader : AppResumeAdLoader {
        private val requests = mutableListOf<Pair<String, AppOpenAd.AppOpenAdLoadCallback>>()
        val ads = mutableListOf<VendorAd>()
        val requestCount get() = requests.size

        override fun load(context: Context, unitId: String, request: AdRequest,
                          callback: AppOpenAd.AppOpenAdLoadCallback) {
            requests += unitId to callback
        }

        fun fill(index: Int): VendorAd = VendorAd(requests[index].first).also {
            ads += it
            requests[index].second.onAdLoaded(it)
        }
    }

    private class VendorAd(private val unitId: String) : AppOpenAd() {
        var showCount = 0
        var onCallbackInstalled: (() -> Unit)? = null
        private var callback: FullScreenContentCallback? = null
        private var paidListener: OnPaidEventListener? = null
        private var placement = 0L

        override fun show(activity: Activity) {
            showCount++
            callback!!.onAdShowedFullScreenContent()
        }

        override fun getAdUnitId(): String = unitId
        override fun getResponseInfo(): ResponseInfo = error("The vendor fake has no mediation response")
        override fun setFullScreenContentCallback(value: FullScreenContentCallback?) {
            callback = value
            onCallbackInstalled?.invoke()
        }
        override fun getFullScreenContentCallback(): FullScreenContentCallback? = callback
        override fun setOnPaidEventListener(value: OnPaidEventListener?) { paidListener = value }
        override fun getOnPaidEventListener(): OnPaidEventListener? = paidListener
        override fun setImmersiveMode(immersiveMode: Boolean) = Unit
        override fun getPlacementId(): Long = placement
        override fun setPlacementId(value: Long) { placement = value }
    }
}

/** Replaces only Android's window boundary; ordinary dialogs still use Robolectric's real shadow. */
@Implements(Dialog::class)
class ResumeOwnershipDialogShadow : ShadowDialog() {
    @Implementation
    public override fun show() {
        val before = beforeShow
        beforeShow = null
        before?.invoke()
        if (throwNextShow) {
            throwNextShow = false
            throw WindowManager.BadTokenException("The window manager refused the cosmetic dialog")
        }
        super.show()
    }

    companion object {
        var beforeShow: (() -> Unit)? = null
        var throwNextShow = false
    }
}
