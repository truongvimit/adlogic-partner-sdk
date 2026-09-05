package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import io.trackkit.PlacementRegistry
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

/** Public manager, load owner, real Tracker and lifecycle; fake only the vendor/window boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ResumeLifecycleApplication::class,
    shadows = [ResumeOwnershipDialogShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenPresentationTelemetryTest {
    private lateinit var app: Application
    private lateinit var manager: AppOpenManager
    private lateinit var host: ActivityController<ComponentActivity>
    private val requests = mutableListOf<Pair<String, AppOpenAd.AppOpenAdLoadCallback>>()
    private val ads = mutableListOf<VendorAd>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        mainLooper.idle()
        ResumeOwnershipDialogShadow.beforeShow = null
        ResumeOwnershipDialogShadow.throwNextShow = false
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "app-open-presentation"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
        manager = AppOpenManager { _, unit, _, callback -> requests += unit to callback }
        manager.setInterstitialShowing(false)
        manager.init(app, "resume-a")
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        mainLooper.idle()
        // Activity/process startup can report an ordinary no-ready opportunity before the test.
        events.removeAll { it.first == "ad_skipped" }
    }

    @After
    fun tearDown() {
        ResumeOwnershipDialogShadow.beforeShow = null
        ResumeOwnershipDialogShadow.throwNextShow = false
        manager.disableAppResume()
        ads.toList().forEach { it.fullScreenContentCallback?.onAdDismissedFullScreenContent() }
        manager.setInterstitialShowing(false)
        app.unregisterActivityLifecycleCallbacks(manager)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(manager)
        ShadowDialog.getLatestDialog()?.dismiss()
        host.pause().stop().destroy()
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        PlacementRegistry.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `notifications off still report one actual show and confirmed close with original load correlation`() {
        val ad = fill()
        val loadId = params("ad_loaded").single()["attempt_id"]
        assertNotNull(loadId)
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.shows)
        assertTrue(params("ad_show").isEmpty())
        ad.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        ad.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        ad.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        ad.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        ad.fullScreenContentCallback!!.onAdFailedToShowFullScreenContent(AdError(7, "late", "vendor"))
        for (event in listOf("ad_show", "ad_closed")) {
            val payload = params(event).single()
            assertEquals("app_resume", payload["placement"])
            assertEquals("app_open", payload["ad_format"])
            assertEquals("resume-a", payload["ad_unit_id"])
            assertEquals(loadId, payload["attempt_id"])
        }
        assertTrue(params("ad_show_failed").isEmpty())
    }

    @Test
    fun `vendor dismissal without confirmed shown is cleanup rather than a fabricated show or close`() {
        val ad = fill()
        manager.showResumeAdIfAvailable()
        ad.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        assertFalse(manager.isShowingAd)
        assertTrue(params("ad_show").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
        assertTrue(params("ad_show_failed").isEmpty())
    }

    @Test
    fun `actual vendor failure reports its code once and preserves the accepted load identity`() {
        val ad = fill()
        manager.showResumeAdIfAvailable()
        val error = AdError(42, "vendor rejected presentation", "vendor")
        ad.fullScreenContentCallback!!.onAdFailedToShowFullScreenContent(error)
        ad.fullScreenContentCallback!!.onAdFailedToShowFullScreenContent(error)
        val failure = params("ad_show_failed").single()
        assertEquals(42, (failure["error_code"] as Number).toInt())
        assertEquals(params("ad_loaded").single()["attempt_id"], failure["attempt_id"])
        assertTrue(params("ad_show").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
        assertFalse(manager.isShowingAd)
    }

    @Test
    fun `host rejection completes compatibility callback without vendor failure or canonical close`() {
        val ad = fill()
        var compatibilityDismissed = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { compatibilityDismissed++ }
        })
        ResumeOwnershipDialogShadow.beforeShow = { host.get().finish() }
        manager.showResumeAdIfAvailable()
        assertEquals(0, ad.shows)
        assertEquals(1, compatibilityDismissed)
        assertEquals("invalid_host", params("ad_skipped").single()["reason"])
        assertTrue(params("ad_show").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
        assertTrue(params("ad_show_failed").isEmpty())
        assertTrue(manager.isResumeAdAvailable())
    }

    @Test
    fun `preload and readiness probes are silent while an explicit miss ends without late auto show`() {
        repeat(5) {
            manager.fetchResumeAd()
            manager.isResumeAdAvailable()
            manager.isResumeSuppressedFor(host.get())
        }
        assertEquals(1, requests.size)
        assertTrue(params("ad_skipped").isEmpty())
        manager.showResumeAdIfAvailable()
        assertEquals("not_ready", params("ad_skipped").single()["reason"])
        val ad = fill()
        assertEquals(0, ad.shows)
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        assertTrue(params("ad_show").isEmpty())
    }

    @Test
    fun `busy opportunity and newer unit cannot relabel the presentation already in flight`() {
        val first = fill()
        manager.showResumeAdIfAvailable()
        manager.setAppResumeAdId("resume-b")
        val second = fill(1)
        PlacementRegistry.register("resume-a", "unrelated-placement")
        manager.showResumeAdIfAvailable()
        assertEquals("presentation_busy", params("ad_skipped").single()["reason"])
        assertEquals(0, second.shows)
        first.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        first.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        assertEquals("resume-a", params("ad_show").single()["ad_unit_id"])
        assertEquals("app_resume", params("ad_show").single()["placement"])
        assertEquals(params("ad_loaded").first()["attempt_id"], params("ad_closed").single()["attempt_id"])
        assertTrue(manager.isResumeAdAvailable())
        manager.showResumeAdIfAvailable()
        assertEquals(1, second.shows)
        second.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        assertEquals("resume-b", params("ad_show").last()["ad_unit_id"])
        assertEquals(params("ad_loaded").last()["attempt_id"], params("ad_show").last()["attempt_id"])
    }

    @Test
    fun `active clicks keep captured attribution after unit replacement and remain distinct`() {
        val first = fill()
        manager.showResumeAdIfAvailable()
        manager.setAppResumeAdId("resume-b")
        fill(1)
        PlacementRegistry.register("resume-a", "replacement-screen")
        first.fullScreenContentCallback!!.onAdClicked()
        first.fullScreenContentCallback!!.onAdClicked()
        assertEquals(2, params("ad_click").size)
        params("ad_click").forEach {
            assertEquals("app_resume", it["placement"])
            assertEquals("app_open", it["ad_format"])
            assertEquals("resume-a", it["ad_unit_id"])
        }
    }

    @Test
    fun `disabled lifecycle is quiet while explicit disabled show has one rejection`() {
        manager.disableAppResume()
        repeat(3) { manager.onResume(); manager.isResumeAdAvailable() }
        assertTrue(params("ad_skipped").isEmpty())
        manager.showResumeAdIfAvailable()
        assertEquals("disabled_config", params("ad_skipped").single()["reason"])
        assertTrue(params("ad_show_failed").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
    }

    @Test
    fun `lifecycle suppression reports once without dispatching or consuming a ready fill`() {
        val ad = fill()
        manager.disableAppResumeWithActivity(ComponentActivity::class.java)
        manager.onResume()
        assertEquals("suppressed_by_flow", params("ad_skipped").single()["reason"])
        manager.enableAppResumeWithActivity(ComponentActivity::class.java)
        manager.disableAdResumeByClickAction()
        manager.onResume()
        assertEquals("returning_from_ad_click", params("ad_skipped").last()["reason"])
        assertEquals(2, params("ad_skipped").size)
        assertEquals(0, ad.shows)
        assertTrue(manager.isResumeAdAvailable())
        manager.onResume()
        assertEquals(1, ad.shows)
        assertEquals(2, params("ad_skipped").size)
    }

    @Test
    fun `host context premium remains a compatibility rejection even when application is eligible`() {
        val ad = fill()
        var dismissals = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissals++ }
        })
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = context is Activity
        })
        mainLooper.idle()
        manager.showResumeAdIfAvailable()
        assertEquals(0, ad.shows)
        assertEquals(1, dismissals)
        assertEquals("purchased", params("ad_skipped").single()["reason"])
        assertTrue(params("ad_show_failed").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
    }

    @Test
    fun `synchronous vendor show exception settles failure without inventing a vendor error code`() {
        val ad = fill()
        ad.throwOnShow = true
        manager.showResumeAdIfAvailable()
        val failure = params("ad_show_failed").single()
        assertFalse(failure.containsKey("error_code"))
        assertEquals(params("ad_loaded").single()["attempt_id"], failure["attempt_id"])
        assertFalse(manager.isShowingAd)
        assertTrue(params("ad_closed").isEmpty())
        assertTrue(params("ad_skipped").isEmpty())
    }

    @Test
    fun `callback preparation failure retains the fill and reports rejection rather than vendor failure`() {
        val ad = fill()
        ad.throwOnCallbackSetup = true
        manager.showResumeAdIfAvailable()
        assertEquals(0, ad.shows)
        assertTrue(manager.isResumeAdAvailable())
        assertFalse(manager.isShowingAd)
        assertEquals("preparation_failed", params("ad_skipped").single()["reason"])
        assertTrue(params("ad_show_failed").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
        ad.throwOnCallbackSetup = false
        manager.showResumeAdIfAvailable()
        ad.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        assertEquals(params("ad_loaded").single()["attempt_id"], params("ad_show").single()["attempt_id"])
    }

    @Test
    fun `direct show respects an excluded host without consuming its ready fill`() {
        assertDirectShowRespectsExclusion(ComponentActivity::class.java)
    }

    @Test
    fun `direct show respects exclusion of the host base class`() {
        assertNotEquals(Activity::class.java, host.get().javaClass)
        assertDirectShowRespectsExclusion(Activity::class.java)
    }

    @Test
    fun `vendor AdActivity is an invalid direct show host until the application host returns`() {
        val ad = fill()
        // A real vendor Activity delivered through the public OS callback boundary. Its vendor
        // onCreate implementation is not invoked: this test supplies no GMA overlay controller.
        val vendorActivity = Robolectric.buildActivity(AdActivity::class.java).get()
        assertFalse(vendorActivity.isFinishing)
        assertFalse(vendorActivity.isDestroyed)
        manager.onActivityStarted(vendorActivity)
        manager.onActivityResumed(vendorActivity)
        try {
            val eventsBeforeQueries = events.toList()
            val requestsBeforeQueries = requests.size
            repeat(3) {
                assertTrue(manager.isResumeAdAvailable())
                manager.isResumeSuppressedFor(vendorActivity)
            }
            assertEquals(eventsBeforeQueries, events)
            assertEquals(requestsBeforeQueries, requests.size)

            manager.showResumeAdIfAvailable()

            assertEquals("A GMA Activity must not host a second fullscreen ad", 0, ad.shows)
            assertEquals("invalid_host", params("ad_skipped").single()["reason"])
            assertTrue(manager.isResumeAdAvailable())
            assertFalse(manager.isShowingAd)
            assertTrue(params("ad_show").isEmpty())
            assertTrue(params("ad_show_failed").isEmpty())
            assertTrue(params("ad_closed").isEmpty())
            assertEquals(1, requests.size)
        } finally {
            // Resume the original host through the same callback boundary; no SDK state reset.
            manager.onActivityStarted(host.get())
            manager.onActivityResumed(host.get())
        }
        mainLooper.idle()
        assertEquals("Returning to a valid host does not auto-show the rejected fill", 0, ad.shows)
        assertTrue(manager.isResumeAdAvailable())
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.shows)
        ad.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        assertEquals(1, params("ad_show").size)
    }

    private fun assertDirectShowRespectsExclusion(excludedClass: Class<out Activity>) {
        val ad = fill()
        manager.disableAppResumeWithActivity(excludedClass)
        val eventsBeforeQueries = events.toList()
        val requestsBeforeQueries = requests.size
        repeat(3) {
            assertTrue(manager.isResumeSuppressedFor(host.get()))
            assertTrue(manager.isResumeAdAvailable())
        }
        assertEquals(eventsBeforeQueries, events)
        assertEquals(requestsBeforeQueries, requests.size)

        manager.showResumeAdIfAvailable()

        assertEquals("Direct calls must honor the exclusion used by foreground resume", 0, ad.shows)
        assertEquals("suppressed_by_flow", params("ad_skipped").single()["reason"])
        assertTrue(manager.isResumeAdAvailable())
        assertFalse(manager.isShowingAd)
        assertTrue(params("ad_show").isEmpty())
        assertTrue(params("ad_show_failed").isEmpty())
        assertTrue(params("ad_closed").isEmpty())
        assertEquals(1, requests.size)

        manager.enableAppResumeWithActivity(excludedClass)
        assertFalse(manager.isResumeSuppressedFor(host.get()))
        mainLooper.idle()
        assertEquals("Lifting suppression must not create an auto-show opportunity", 0, ad.shows)
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.shows)
        ad.fullScreenContentCallback!!.onAdShowedFullScreenContent()
        assertEquals(1, params("ad_show").size)
        assertEquals(1, params("ad_skipped").size)
    }

    @Test
    fun `exclusion raised during dialog preparation rejects before consuming the selected fill`() {
        val ad = fill()
        var dismissals = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissals++ }
        })
        ResumeOwnershipDialogShadow.beforeShow = {
            manager.disableAppResumeWithActivity(ComponentActivity::class.java)
        }
        manager.showResumeAdIfAvailable()
        assertEquals(0, ad.shows)
        assertEquals(1, dismissals)
        assertTrue(manager.isResumeAdAvailable())
        assertFalse(manager.isShowingAd)
        assertEquals("suppressed_by_flow", params("ad_skipped").single()["reason"])
        assertTrue(params("ad_closed").isEmpty())
        assertTrue(params("ad_show_failed").isEmpty())
        ResumeOwnershipDialogShadow.beforeShow = null
        manager.enableAppResumeWithActivity(ComponentActivity::class.java)
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.shows)
    }

    @Test
    fun `vendor Activity arriving during dialog preparation invalidates the captured application host`() {
        val ad = fill()
        var dismissals = 0
        manager.setEnableScreenContentCallback(true)
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { dismissals++ }
        })
        val vendorActivity = Robolectric.buildActivity(AdActivity::class.java).get()
        ResumeOwnershipDialogShadow.beforeShow = {
            manager.onActivityStarted(vendorActivity)
            manager.onActivityResumed(vendorActivity)
        }
        try {
            manager.showResumeAdIfAvailable()
            assertEquals(0, ad.shows)
            assertEquals(1, dismissals)
            assertTrue(manager.isResumeAdAvailable())
            assertFalse(manager.isShowingAd)
            assertEquals("invalid_host", params("ad_skipped").single()["reason"])
            assertTrue(params("ad_show").isEmpty())
            assertTrue(params("ad_closed").isEmpty())
            assertTrue(params("ad_show_failed").isEmpty())
        } finally {
            ResumeOwnershipDialogShadow.beforeShow = null
            manager.onActivityStarted(host.get())
            manager.onActivityResumed(host.get())
        }
        manager.showResumeAdIfAvailable()
        assertEquals(1, ad.shows)
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private fun fill(index: Int = 0): VendorAd = VendorAd(requests[index].first).also {
        ads += it
        requests[index].second.onAdLoaded(it)
        mainLooper.idle()
    }

    private class VendorAd(private val unit: String) : AppOpenAd() {
        var shows = 0
        var throwOnShow = false
        var throwOnCallbackSetup = false
        private var callback: FullScreenContentCallback? = null
        private var paid: OnPaidEventListener? = null
        private var placement = 0L
        private val response = Mockito.mock(ResponseInfo::class.java)
        override fun show(activity: Activity) {
            shows++
            if (throwOnShow) throw IllegalStateException("external vendor invocation failed")
        }
        override fun getAdUnitId() = unit
        override fun getResponseInfo(): ResponseInfo = response
        override fun setFullScreenContentCallback(value: FullScreenContentCallback?) {
            if (throwOnCallbackSetup) throw IllegalStateException("external vendor callback setup failed")
            callback = value
        }
        override fun getFullScreenContentCallback() = callback
        override fun setOnPaidEventListener(value: OnPaidEventListener?) { paid = value }
        override fun getOnPaidEventListener() = paid
        override fun setImmersiveMode(value: Boolean) = Unit
        override fun getPlacementId() = placement
        override fun setPlacementId(value: Long) { placement = value }
    }
}
