package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
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
import java.util.concurrent.TimeUnit

/** Real manager and Activity/process callbacks; only Google's ad and Android Dialog are external fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Int02Application::class,
    shadows = [ResumeLoadGmaShadow::class, ResumePresentationDialogShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenResumePresentationTest {
    private val manager get() = AppOpenManager.getInstance()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = ResumeLoadGmaShadow.requests
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var host: Int02Activity
    private val ads = mutableListOf<ResumePresentationAd>()
    private var premium = false
    private var tearingDown = false

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        manager.disableAppResume()
        manager.init(app, "")
        // Robolectric replaces the Application between tests while the SDK singleton can
        // survive. Bind the real public callbacks to this test's Application exactly once.
        app.unregisterActivityLifecycleCallbacks(manager)
        app.registerActivityLifecycleCallbacks(manager)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(manager)
        ProcessLifecycleOwner.get().lifecycle.addObserver(manager)
        manager.releaseCachedAds()
        manager.setSplashActivity(null, "", 0)
        manager.setInterstitialShowing(false)
        manager.setDisableAdResumeByClickAction(false)
        manager.setResumeSkipPolicy(null)
        manager.removeFullScreenContentCallback()
        manager.setEnableScreenContentCallback(true)
        manager.enableAppResumeWithActivity(Int02Activity::class.java)
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ResumePresentationDialogShadow.afterShow = null
        ResumePresentationDialogShadow.throwOnShow = false
        requests.clear()
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        host = controller.get()
        main.idle()
        assertSame(host, manager.currentActivity)
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertFalse("No prior test may leave a live fake vendor presentation", manager.isShowingAd)
    }

    @After
    fun tearDown() {
        tearingDown = true
        ResumePresentationDialogShadow.afterShow = null
        ResumePresentationDialogShadow.throwOnShow = false
        manager.disableAppResume()
        manager.removeFullScreenContentCallback()
        manager.setEnableScreenContentCallback(false)
        // Real vendor terminal delivery, not private state reset. The reentrant test delegate
        // stops its own work during teardown; captured production callbacks remain untouched.
        ads.forEach { it.content?.onAdDismissedFullScreenContent() }
        ShadowDialog.getLatestDialog()?.dismiss()
        manager.setInterstitialShowing(false)
        manager.setAppResumeAdId("")
        manager.releaseCachedAds()
        manager.setDisableAdResumeByClickAction(false)
        manager.setResumeSkipPolicy(null)
        manager.enableAppResumeWithActivity(Int02Activity::class.java)
        manager.setInitialized(true)
        ConsentCenter.clearHostConsent()
        if (::controller.isInitialized) {
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            main.idleFor(800, TimeUnit.MILLISECONDS)
        }
        requests.clear()
    }

    @Test
    fun `no vendor callback leaves no loading dialog but keeps dispatched ad busy beyond90 seconds`() {
        val ad = load()
        manager.showAdIfAvailable(false)
        assertEquals(listOf(host), ad.hosts)
        assertFalse("Cosmetic dialog must not depend on GMA callbacks", latestDialogShowing())
        assertTrue(manager.isShowingAd)
        main.idleFor(91_000, TimeUnit.MILLISECONDS)
        manager.showAdIfAvailable(false)
        assertTrue("A live presentation has no 90-second lifetime guarantee", manager.isShowingAd)
        assertEquals(1, ad.hosts.size)
        ad.content!!.onAdDismissedFullScreenContent()
        assertFalse(manager.isShowingAd)
    }

    @Test
    fun `interstitial compatibility suppression cannot time out while vendor still owns screen`() {
        val ad = load()
        manager.setInterstitialShowing(true)
        main.idleFor(91_000, TimeUnit.MILLISECONDS)
        assertTrue(manager.isInterstitialShowing)
        manager.showAdIfAvailable(false)
        assertTrue(ad.hosts.isEmpty())
        manager.setInterstitialShowing(false)
        manager.showAdIfAvailable(false)
        assertEquals(1, ad.hosts.size)
    }

    @Test
    fun `A close cleans before delegate and late A callbacks cannot erase reentrant B`() {
        val a = load()
        val bEvents = Events()
        var aClosed = 0
        var b: ResumePresentationAd? = null
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (tearingDown) return
                aClosed++
                assertFalse("A is released before the partner continues", manager.isShowingAd)
                assertFalse(latestDialogShowing())
                manager.setFullScreenContentCallback(bEvents)
                b = load()
                manager.showAdIfAvailable(false)
            }
        })
        manager.showAdIfAvailable(false)
        val aCallback = a.content!!
        aCallback.onAdShowedFullScreenContent()
        aCallback.onAdDismissedFullScreenContent()
        assertEquals(1, b!!.hosts.size)
        b!!.content!!.onAdShowedFullScreenContent()
        aCallback.onAdFailedToShowFullScreenContent(error())
        aCallback.onAdDismissedFullScreenContent()
        aCallback.onAdShowedFullScreenContent()
        aCallback.onAdImpression()
        assertEquals(1, aClosed)
        assertEquals(1, bEvents.shown)
        assertEquals(0, bEvents.closed + bEvents.failed + bEvents.impressions)
        assertTrue(manager.isShowingAd)
        b!!.content!!.onAdDismissedFullScreenContent()
        assertEquals(1, bEvents.closed)
        assertFalse(manager.isShowingAd)
    }

    @Test
    fun `callback registered for B cannot receive A terminal`() {
        val a = load()
        val original = Events()
        val replacement = Events()
        manager.setFullScreenContentCallback(original)
        manager.showAdIfAvailable(false)
        manager.setFullScreenContentCallback(replacement)
        a.content!!.onAdShowedFullScreenContent()
        a.content!!.onAdFailedToShowFullScreenContent(error())
        a.content!!.onAdDismissedFullScreenContent()
        assertEquals(1, original.shown)
        assertEquals(1, original.failed)
        assertEquals(0, original.closed)
        assertEquals(0, replacement.shown + replacement.failed + replacement.closed)
        assertFalse(manager.isShowingAd)
    }

    @Test
    fun `pause during dialog attachment preserves fill and does not dispatch on paused host`() {
        val ad = load()
        ResumePresentationDialogShadow.afterShow = { controller.pause() }
        manager.showAdIfAvailable(false)
        assertTrue(ad.hosts.isEmpty())
        assertTrue(manager.isAdAvailable(false))
        assertFalse(manager.isShowingAd)
        assertFalse(latestDialogShowing())
        // An overlay-style resume has no process ON_START. It must not replay the rejected show.
        controller.resume().visible()
        main.idle()
        assertTrue(ad.hosts.isEmpty())
        manager.showAdIfAvailable(false)
        assertEquals(listOf(host), ad.hosts)
        assertEquals(1, requests.size)
    }

    @Test
    fun `release inside dialog attachment cannot restore or show the released fill`() {
        val ad = load()
        ResumePresentationDialogShadow.afterShow = { manager.releaseCachedAds() }
        manager.showAdIfAvailable(false)
        assertTrue(ad.hosts.isEmpty())
        assertFalse(manager.isAdAvailable(false))
        assertFalse(manager.isShowingAd)
        assertFalse(latestDialogShowing())
    }

    @Test
    fun `initialization disabled inside dialog rejects without consuming a valid fill`() {
        val ad = load()
        ResumePresentationDialogShadow.afterShow = { manager.setInitialized(false) }
        manager.showAdIfAvailable(false)
        assertTrue(ad.hosts.isEmpty())
        assertTrue(manager.isAdAvailable(false))
        assertFalse(manager.isShowingAd)
        manager.setInitialized(true)
        manager.showAdIfAvailable(false)
        assertEquals(listOf(host), ad.hosts)
    }

    @Test
    fun `cosmetic dialog and immersive failures do not spend a valid ad without showing it`() {
        val ad = load().apply { immersiveFailure = true }
        ResumePresentationDialogShadow.throwOnShow = true
        manager.showAdIfAvailable(false)
        assertEquals(listOf(host), ad.hosts)
        assertTrue(manager.isShowingAd)
        assertFalse(latestDialogShowing())
    }

    @Test
    fun `automatic process start defers until resumed and a blocked click return stays blocked`() {
        val ad = load()
        leaveProcess()
        manager.disableAdResumeByClickAction()
        controller.restart().start().resume().visible()
        main.idle()
        assertTrue("Clearing the return snapshot must not resurrect this skipped return", ad.hosts.isEmpty())
        assertTrue(manager.isAdAvailable(false))
        leaveProcess()
        controller.restart().start()
        assertTrue("Process ON_START alone is not a RESUMED show host", ad.hosts.isEmpty())
        controller.resume().visible()
        main.idle()
        assertEquals(listOf(host), ad.hosts)
    }

    @Test
    fun `Home before deferred automatic dispatch cancels that attempt and retains its fill`() {
        val ad = load()
        leaveProcess()
        // Stop during STARTED, before a RESUMED host or the queued show can exist.
        // ActivityController.visible() drains the queue, so it cannot model this window.
        controller.restart().start()
        controller.stop()
        main.idle()
        assertTrue(ad.hosts.isEmpty())
        assertTrue(manager.isAdAvailable(false))
        assertFalse(manager.isShowingAd)
    }

    @Test
    fun `actual shown after host pause still forwards once and synchronous vendor throw releases once`() {
        val events = Events()
        val ad = load()
        manager.setFullScreenContentCallback(events)
        manager.showAdIfAvailable(false)
        controller.pause()
        ad.content!!.onAdShowedFullScreenContent()
        ad.content!!.onAdShowedFullScreenContent()
        assertEquals(1, events.shown)
        ad.content!!.onAdDismissedFullScreenContent()
        assertEquals(1, events.closed)
        controller.resume().visible()
        main.idle()
        val failing = load().apply { showFailure = true }
        manager.showAdIfAvailable(false)
        assertEquals(1, failing.hosts.size)
        assertEquals(1, events.failed)
        assertFalse(manager.isShowingAd)
        failing.content!!.onAdFailedToShowFullScreenContent(error())
        assertEquals(1, events.failed)
        assertFalse(latestDialogShowing())
    }

    @Test
    fun `throwing host shown callback cannot fail or release a live vendor presentation`() {
        val ad = load().apply { reportShownInsideShow = true }
        var shown = 0
        var failed = 0
        var closed = 0
        manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                shown++
                throw IllegalStateException("Host shown callback failed")
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
            override fun onAdDismissedFullScreenContent() { closed++ }
        })
        val requestCount = requests.size

        manager.showAdIfAvailable(false)

        assertEquals(listOf(host), ad.hosts)
        assertEquals(1, shown)
        assertEquals("Host callback failure is not a vendor show failure", 0, failed)
        assertEquals("A live presentation must not trigger a failure refill", requestCount, requests.size)
        assertTrue(manager.isShowingAd)
        assertFalse(latestDialogShowing())

        ad.content!!.onAdDismissedFullScreenContent()
        assertEquals(1, closed)
        assertFalse(manager.isShowingAd)
        assertEquals(requestCount + 1, requests.size)
        ad.content!!.onAdDismissedFullScreenContent()
        assertEquals(1, closed)
        assertEquals(0, failed)
        assertEquals(requestCount + 1, requests.size)
    }

    private fun load(): ResumePresentationAd {
        manager.setAppResumeAdId(UNIT)
        manager.enableAppResume()
        manager.fetchAd(false)
        val ad = ResumePresentationAd(UNIT).also(ads::add)
        requests.last().callback.onAdLoaded(ad)
        assertTrue(manager.isAdAvailable(false))
        return ad
    }

    private fun leaveProcess() {
        controller.pause().stop()
        main.idleFor(800, TimeUnit.MILLISECONDS)
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    private fun latestDialogShowing() = ShadowDialog.getLatestDialog()?.isShowing == true
    private fun error() = AdError(1, "Real vendor boundary failure", "test.gma")
    private class Events : FullScreenContentCallback() {
        var shown = 0; var closed = 0; var failed = 0; var impressions = 0
        override fun onAdShowedFullScreenContent() { shown++ }
        override fun onAdDismissedFullScreenContent() { closed++ }
        override fun onAdFailedToShowFullScreenContent(error: AdError) { failed++ }
        override fun onAdImpression() { impressions++ }
    }
    private companion object { const val UNIT = "res04-app-open-unit" }
}

/** External Android attachment can synchronously transition the real Activity or throw. */
@Implements(Dialog::class)
class ResumePresentationDialogShadow : ShadowDialog() {
    @Implementation
    override fun show() {
        if (throwOnShow) throw IllegalStateException("External window attachment unavailable")
        super.show()
        val action = afterShow
        afterShow = null
        action?.invoke()
    }
    companion object {
        var afterShow: (() -> Unit)? = null
        var throwOnShow = false
    }
}

private class ResumePresentationAd(private val unit: String) : AppOpenAd() {
    val hosts = mutableListOf<Activity>()
    var content: FullScreenContentCallback? = null
    private var paid: OnPaidEventListener? = null
    private var placement = 0L
    var immersiveFailure = false
    var showFailure = false
    var reportShownInsideShow = false
    override fun show(activity: Activity) {
        hosts += activity
        if (showFailure) throw IllegalStateException("External GMA show failure")
        if (reportShownInsideShow) content!!.onAdShowedFullScreenContent()
    }
    override fun getAdUnitId() = unit
    override fun getResponseInfo(): ResponseInfo = error("No paid event is simulated")
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
    override fun getFullScreenContentCallback() = content
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
    override fun getOnPaidEventListener() = paid
    override fun setImmersiveMode(value: Boolean) { if (immersiveFailure) error("External cosmetic failure") }
    override fun getPlacementId() = placement
    override fun setPlacementId(value: Long) { placement = value }
}
