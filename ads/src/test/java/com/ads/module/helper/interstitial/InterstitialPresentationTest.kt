package com.ads.module.helper.interstitial

import com.google.android.gms.ads.AdError
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.ads.module.R
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApInterstitialPriorityAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.TrackingAdCallback
import com.facebook.FacebookSdk
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import io.trackkit.TrackSink
import io.trackkit.AdFormat
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * Real manager -> waterfall -> ERain -> Admob -> external GMA boundary, with real Activity,
 * AndroidX process lifecycle, loading dialog and Tracker sink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = InterstitialPresentationApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialPresentationTest {
    private lateinit var app: Application
    private lateinit var controller: ActivityController<InterstitialPresentationActivity>
    private lateinit var activity: InterstitialPresentationActivity
    private lateinit var vendor: MockedStatic<InterstitialAd>
    private lateinit var googleAd: InterstitialAd
    private lateinit var fullscreenCallback: FullScreenContentCallback
    private val requests = mutableListOf<InterstitialAdLoadCallback>()
    private val requestUnits = mutableListOf<String>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val vendorHosts = mutableListOf<Activity>()
    private var premium = false
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InterstitialAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            })

        // Public SDK initialization supplies the real config/context needed by forceShow.
        // Only external initialization is stubbed; SDK singletons and adapters stay real.
        Mockito.mockStatic(MobileAds::class.java).use {
            Mockito.mockStatic(FacebookSdk::class.java).use {
                ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                    setFacebookClientToken("test-client-token")
                })
            }
        }
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)

        vendor = Mockito.mockStatic(InterstitialAd::class.java) { invocation ->
            if (invocation.method.name == "load") {
                requests += invocation.getArgument<InterstitialAdLoadCallback>(3)
                requestUnits += invocation.getArgument<String>(1)
            }
            null
        }
        googleAd = Mockito.mock(InterstitialAd::class.java)
        Mockito.`when`(googleAd.adUnitId).thenReturn(UNIT)
        Mockito.doAnswer { invocation ->
            fullscreenCallback = invocation.getArgument(0)
            null
        }.`when`(googleAd).setFullScreenContentCallback(
            Mockito.any(FullScreenContentCallback::class.java),
        )
        Mockito.doAnswer { invocation ->
            vendorHosts += invocation.getArgument<Activity>(0)
            null
        }.`when`(googleAd).show(Mockito.any(Activity::class.java))

        controller = Robolectric.buildActivity(InterstitialPresentationActivity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "interstitial-presentation-recording"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                events += name to params
            }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        InterstitialAdManager.releaseAll()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        AppOpenManager.getInstance().releaseCachedAds()
        if (::controller.isInitialized) {
            val host = controller.get()
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            // Let ProcessLifecycleOwner finish the framework's 700 ms background debounce.
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        // External vendor fakes must finish an invoked ad even when a test assertion throws.
        if (::fullscreenCallback.isInitialized && vendorHosts.isNotEmpty()) {
            fullscreenCallback.onAdDismissedFullScreenContent()
        }
        if (::vendor.isInitialized) vendor.close()
        Tracker.resetForTesting()
    }

    @Test
    fun `Home during preparation retains the same fill and only vendor presentation reports show`() {
        var loaded: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT),
            listener = object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
            })
        assertEquals(1, requests.size)
        requests.single().onAdLoaded(googleAd)
        mainLooper.idle()
        val originalWrapper = requireNotNull(loaded)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertSame(googleAd, originalWrapper.interstitialAd)
        assertEquals(1, params("ad_request").size)
        assertEquals(1, params("ad_loaded").size)
        assertEquals(0, params("ad_show").size)

        val rejected = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, rejected,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue("Real preparation dialog must be visible before Home",
            ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(0, vendorHosts.size)

        // Home moves the real host out of RESUMED during the unchanged 800 ms preparation.
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertFalse(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, rejected.shown) // Legacy preparation callback remains compatible.
        assertEquals(0, rejected.presented)
        assertEquals(0, rejected.closed)
        assertEquals(1, rejected.completed)
        assertEquals(listOf("host_not_resumed"), rejected.skipped.map { it.key })
        assertEquals(1, params("ad_skipped").size)
        assertEquals("host_not_resumed", params("ad_skipped").single()["reason"])
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertTrue(originalWrapper.isReady)
        assertSame(googleAd, originalWrapper.interstitialAd)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)

        // Public cache reuse proves rejection kept this wrapper, rather than requesting again.
        controller.restart().start().resume().visible()
        mainLooper.idle()
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        var reused: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT),
            listener = object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { reused = ad }
            })
        assertSame(originalWrapper, reused)
        assertEquals(1, requests.size)

        val presented = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, presented,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(1, presented.shown)
        assertEquals(0, presented.presented)
        assertEquals(0, presented.completed)

        fullscreenCallback.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, params("ad_show").size)
        assertEquals(PLACEMENT, params("ad_show").single()["placement"])
        assertEquals(UNIT, params("ad_show").single()["ad_unit_id"])
        assertEquals(1, presented.shown)
        assertEquals(1, presented.presented)
        assertEquals(0, presented.completed)
        assertNotNull(originalWrapper.interstitialAd)

        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, presented.closed)
        assertEquals(1, presented.completed)
        assertTrue(presented.skipped.isEmpty())
        assertFalse(originalWrapper.isReady)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, params("ad_skipped").size)
        assertEquals(1, rejected.completed)
    }

    @Test
    fun `a different placement cannot present during preparation and keeps its fill for retry`() {
        loadAndFill()
        val first = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, first, nextAction = InterNextAction.AfterDismiss)
        val firstCallback = fullscreenCallback
        val firstDialog = ShadowDialog.getLatestDialog()

        val secondPlacement = "second-fullscreen-slot"
        val secondAd = replacementVendor()
        var secondWrapper: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, secondPlacement, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { secondWrapper = ad }
        })
        requests.last().onAdLoaded(secondAd)
        mainLooper.idle()
        val second = RecordingShowCallback()
        InterstitialAdManager.show(activity, secondPlacement, second, nextAction = InterNextAction.AfterDismiss)

        assertEquals(listOf(AdSkipReason.PRESENTATION_BUSY), second.skipped)
        assertEquals(1, second.completed)
        assertTrue(InterstitialAdManager.isReady(secondPlacement))
        assertSame(secondAd, requireNotNull(secondWrapper).interstitialAd)
        assertSame(firstDialog, ShadowDialog.getLatestDialog())
        assertTrue(firstDialog.isShowing)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, vendorHosts.size)
        firstCallback.onAdShowedFullScreenContent()
        firstCallback.onAdDismissedFullScreenContent()

        val retry = RecordingShowCallback()
        InterstitialAdManager.show(activity, secondPlacement, retry, nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(2, vendorHosts.size)
        fullscreenCallback.onAdShowedFullScreenContent()
        fullscreenCallback.onAdDismissedFullScreenContent()
        assertEquals(1, retry.closed)
        assertEquals(1, retry.completed)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a throwing host presented callback cannot unlock a synchronously shown vendor ad`() {
        val callback = object : AdCallback() {
            // Kotlin callers may throw checked exceptions through the Java callback interface.
            override fun onAdPresented() { throw Exception("host callback failed") }
        }
        val wrapper = loadDirect(callback)
        Mockito.doAnswer { invocation ->
            vendorHosts += invocation.getArgument<Activity>(0)
            fullscreenCallback.onAdShowedFullScreenContent()
            null
        }.`when`(googleAd).show(Mockito.any(Activity::class.java))

        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, callback, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(1, vendorHosts.size)
        assertEquals(1, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
        assertTrue(wrapper.isReady)
        fullscreenCallback.onAdDismissedFullScreenContent()
        assertFalse(AppOpenManager.getInstance().isInterstitialShowing)
        assertFalse(wrapper.isReady)
    }

    @Test
    fun `timed public interstitial captures navigation mode before the scheduling delay`() {
        val sdk = com.ads.module.admob.Admob.getInstance()
        sdk.setOpenActivityAfterShowInterAds(false)
        var next = 0
        sdk.showInterstitialAdByTimes(activity, googleAd, object : AdCallback() {
            override fun onNextAction() { next++ }
        }, 100L)
        sdk.setOpenActivityAfterShowInterAds(true)

        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        // Real dialog creation can advance the virtual clock inside the first scheduled task.
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(1, vendorHosts.size)
        assertEquals(0, next)
        fullscreenCallback.onAdDismissedFullScreenContent()
        assertEquals(1, next)
    }

    private fun loadAndFill(raw: InterstitialAd = googleAd): ApInterstitialAd {
        val before = requests.size
        var loaded: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT),
            listener = object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
            })
        assertEquals(before + 1, requests.size)
        requests.last().onAdLoaded(raw)
        mainLooper.idle()
        return requireNotNull(loaded)
    }

    private fun cachedThroughPublicLoad(): ApInterstitialAd {
        val before = requests.size
        var loaded: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT),
            listener = object : AdCallback() {
                override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
            })
        assertEquals(before, requests.size)
        return requireNotNull(loaded)
    }

    private fun loadDirect(callback: AdCallback, raw: InterstitialAd = googleAd): ApInterstitialAd {
        val before = requests.size
        val wrapper = ERainAd.getInstance().getInterstitialAds(activity, UNIT, callback)
        assertEquals(before + 1, requests.size)
        requests.last().onAdLoaded(raw)
        mainLooper.idle()
        assertSame(raw, wrapper.interstitialAd)
        return wrapper
    }

    private fun replacementVendor(): InterstitialAd = Mockito.mock(InterstitialAd::class.java).also { raw ->
        Mockito.`when`(raw.adUnitId).thenReturn(UNIT)
        Mockito.doAnswer { invocation ->
            fullscreenCallback = invocation.getArgument(0)
            null
        }.`when`(raw).setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
        Mockito.doAnswer { invocation ->
            vendorHosts += invocation.getArgument<Activity>(0)
            null
        }.`when`(raw).show(Mockito.any(Activity::class.java))
    }

    private fun returnFromHome() {
        controller.restart().start().resume().visible()
        mainLooper.idle()
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    @Test
    fun `replacement B remains buffered when pending A is rejected before vendor show`() {
        val wrapperA = loadAndFill()
        val rejected = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, rejected,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        val googleB = Mockito.mock(InterstitialAd::class.java)
        Mockito.`when`(googleB.adUnitId).thenReturn(UNIT)
        val wrapperB = loadAndFill(googleB)
        assertTrue(wrapperA !== wrapperB)
        assertSame(googleB, wrapperB.interstitialAd)
        assertEquals(2, requests.size)

        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, rejected.completed)
        assertEquals(listOf("host_not_resumed"), rejected.skipped.map { it.key })
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        returnFromHome()
        assertSame(wrapperB, cachedThroughPublicLoad())
        assertSame(googleB, wrapperB.interstitialAd)
        assertTrue(wrapperB.isReady)
        assertEquals(2, requests.size)
        assertEquals(1, params("ad_skipped").size)
    }

    @Test
    @Config(instrumentedPackages = [
        "com.ads.module.helper.CachedAd",
        "com.ads.module.helper.interstitial.InterstitialTestWallClock",
    ])
    fun `restored ad expires at its original fill deadline instead of receiving a new hour`() {
        val clock = InterstitialTestWallClock()
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        val filledAt = clock.currentTimeMillis()
        val original = loadAndFill()
        assertEquals(filledAt, clock.currentTimeMillis())
        val ttlMs = TimeUnit.HOURS.toMillis(1)

        ShadowSystemClock.advanceBy(ttlMs - 1_600, TimeUnit.MILLISECONDS)
        mainLooper.idle()
        assertEquals(filledAt + ttlMs - 1_600, clock.currentTimeMillis())
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))

        val rejected = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, rejected,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        // Framework dialog work can advance the virtual clock by a millisecond.
        val remaining = filledAt + ttlMs - clock.currentTimeMillis()
        assertTrue(remaining in 1..700)
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        assertEquals(1, rejected.completed)
        assertEquals(listOf("host_not_resumed"), rejected.skipped.map { it.key })
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertSame(googleAd, original.interstitialAd)
        assertEquals(0, vendorHosts.size)

        ShadowSystemClock.advanceBy(remaining - 1, TimeUnit.MILLISECONDS)
        assertEquals(filledAt + ttlMs - 1, clock.currentTimeMillis())
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS)
        assertEquals(filledAt + ttlMs, clock.currentTimeMillis())
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(1, requests.size)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
    }

    @Test
    fun `actual vendor show failure consumes the fill and duplicate failure completes only once`() {
        val original = loadAndFill()
        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, result.completed)

        val failure = AdError(42, "vendor presentation failed", "test-vendor")
        fullscreenCallback.onAdFailedToShowFullScreenContent(failure)
        mainLooper.idle()
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertFalse(original.isReady)
        assertEquals(1, result.completed)
        assertEquals(1, result.shown)
        assertEquals(0, result.presented)
        assertEquals(0, result.closed)
        assertEquals(listOf(AdSkipReason.FAILED_TO_SHOW), result.skipped)
        assertEquals(1, params("ad_show_failed").size)
        assertEquals(42, (params("ad_show_failed").single()["error_code"] as Number).toInt())

        fullscreenCallback.onAdFailedToShowFullScreenContent(failure)
        mainLooper.idle()
        assertEquals(1, result.completed)
        assertEquals(1, result.skipped.size)
        assertEquals(1, params("ad_show_failed").size)
        assertEquals(0, params("ad_show").size)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
        assertTrue(InterstitialAdManager.isLoading(PLACEMENT))
    }

    @Test
    fun `UnderAd completes immediately before vendor show while shown waits for the vendor callback`() {
        loadAndFill()
        val order = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            vendorHosts += invocation.getArgument<Activity>(0)
            order += "vendor.show"
            null
        }.`when`(googleAd).show(Mockito.any(Activity::class.java))
        val callback = object : InterShowCallback() {
            override fun onComplete() { order += "complete" }
            override fun onPresented() { order += "shown" }
            override fun onClosed() { order += "closed" }
            override fun onSkipped(reason: AdSkipReason) { order += "skipped:${reason.key}" }
        }
        InterstitialAdManager.show(activity, PLACEMENT, callback,
            nextAction = InterNextAction.UnderAd)

        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertTrue(order.isEmpty())
        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf("complete", "vendor.show"), order)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, params("ad_show").size)

        fullscreenCallback.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf("complete", "vendor.show", "shown"), order)
        assertEquals(1, params("ad_show").size)
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf("complete", "vendor.show", "shown", "closed"), order)
        assertEquals(1, order.count { it == "complete" })
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(0, params("ad_skipped").size)
    }

    @Test
    fun `closing A can synchronously start B without old callbacks touching B preparation`() {
        loadAndFill()
        val googleB = Mockito.mock(InterstitialAd::class.java)
        Mockito.`when`(googleB.adUnitId).thenReturn(UNIT)
        lateinit var callbackB: FullScreenContentCallback
        lateinit var dialogB: Dialog
        val vendorBHosts = mutableListOf<Activity>()
        Mockito.doAnswer { invocation ->
            callbackB = invocation.getArgument(0)
            null
        }.`when`(googleB).setFullScreenContentCallback(
            Mockito.any(FullScreenContentCallback::class.java),
        )
        Mockito.doAnswer { invocation ->
            vendorBHosts += invocation.getArgument<Activity>(0)
            null
        }.`when`(googleB).show(Mockito.any(Activity::class.java))
        val resultB = RecordingShowCallback()
        var closedA = 0
        var completedA = 0
        var presentedA = 0
        val resultA = object : InterShowCallback() {
            override fun onPresented() { presentedA++ }
            override fun onComplete() { completedA++ }
            override fun onClosed() {
                closedA++
                val wrapperB = loadAndFill(googleB)
                assertSame(googleB, wrapperB.interstitialAd)
                InterstitialAdManager.show(activity, PLACEMENT, resultB,
                    nextAction = InterNextAction.AfterDismiss)
                dialogB = requireNotNull(ShadowDialog.getLatestDialog())
                assertTrue("B must create its own real preparation dialog", dialogB.isShowing)
            }
        }
        InterstitialAdManager.show(activity, PLACEMENT, resultA,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        val callbackA = fullscreenCallback
        callbackA.onAdShowedFullScreenContent()
        assertEquals(1, presentedA)

        // User code starts a distinct B before A's dismissal callback stack has returned.
        callbackA.onAdDismissedFullScreenContent()
        mainLooper.idle()

        assertEquals(1, closedA)
        assertEquals(1, completedA)
        assertEquals(2, requests.size)
        assertSame(dialogB, ShadowDialog.getLatestDialog())
        assertTrue("A cleanup must not dismiss the dialog created by B in onClosed", dialogB.isShowing)
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(0, vendorBHosts.size)
        assertEquals(0, resultB.presented)
        assertEquals(0, resultB.closed)
        assertEquals(0, resultB.completed)

        callbackA.onAdFailedToShowFullScreenContent(AdError(42, "late A failure", "test-vendor"))
        assertTrue("Late A failure cannot dismiss B", dialogB.isShowing)
        assertTrue("Late A failure cannot clear B's showing flag",
            AppOpenManager.getInstance().isInterstitialShowing)
        callbackA.onAdDismissedFullScreenContent()
        assertTrue("Duplicate A dismissal cannot dismiss B", dialogB.isShowing)
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
        callbackA.onAdShowedFullScreenContent()
        assertTrue(dialogB.isShowing)
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(1, closedA)
        assertEquals(1, completedA)
        assertEquals(1, presentedA)
        assertEquals(0, resultB.presented)
        assertEquals(0, resultB.closed)
        assertEquals(0, resultB.completed)
        assertTrue(resultB.skipped.isEmpty())
        assertEquals(1, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)

        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorBHosts)
        assertEquals(0, resultB.presented)
        callbackB.onAdShowedFullScreenContent()
        assertEquals(1, resultB.presented)
        assertEquals(2, params("ad_show").size)
        callbackB.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, resultB.closed)
        assertEquals(1, resultB.completed)
        assertFalse(dialogB.isShowing)
        assertFalse(AppOpenManager.getInstance().isInterstitialShowing)

        // A cannot claim ownership again after both presentations have finished.
        callbackA.onAdShowedFullScreenContent()
        callbackA.onAdDismissedFullScreenContent()
        callbackA.onAdFailedToShowFullScreenContent(AdError(42, "late A again", "test-vendor"))
        assertFalse(AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(1, resultB.presented)
        assertEquals(1, resultB.closed)
        assertEquals(1, resultB.completed)
        assertEquals(2, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
    }

    @Test
    @Config(instrumentedPackages = [
        "com.ads.module.helper.CachedAd",
        "com.ads.module.helper.interstitial.InterstitialTestWallClock",
    ])
    fun `ad expiring inside preparation is rejected without vendor invocation or restoration`() {
        val clock = InterstitialTestWallClock()
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        val filledAt = clock.currentTimeMillis()
        loadAndFill()
        val ttlMs = TimeUnit.HOURS.toMillis(1)
        ShadowSystemClock.advanceBy(ttlMs - 400, TimeUnit.MILLISECONDS)
        mainLooper.idle()
        assertEquals(filledAt + ttlMs - 400, clock.currentTimeMillis())
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))

        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertTrue(clock.currentTimeMillis() >= filledAt + ttlMs)
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        assertEquals(0, vendorHosts.size)
        assertEquals(0, result.presented)
        assertEquals(1, result.completed)
        assertEquals(listOf(AdSkipReason.EXPIRED), result.skipped)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals("expired", params("ad_skipped").single()["reason"])
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
    }

    @Test
    fun `release during preparation prevents vendor show and cannot restore the released fill`() {
        loadAndFill()
        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        InterstitialAdManager.release(PLACEMENT)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)

        assertEquals(0, vendorHosts.size)
        assertEquals(0, result.presented)
        assertEquals(1, result.completed)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.skipped)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, params("ad_skipped").size)
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
    }

    @Test
    fun `purchase during preparation prevents vendor show and the prior fill cannot return`() {
        loadAndFill()
        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        premium = true
        Entitlement.notifyChanged()
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)

        assertEquals(0, vendorHosts.size)
        assertEquals(0, result.presented)
        assertEquals(1, result.completed)
        assertEquals(listOf(AdSkipReason.PURCHASED), result.skipped)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals("purchased", params("ad_skipped").single()["reason"])
        premium = false
        Entitlement.notifyChanged()
        mainLooper.idle()
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
    }

    @Test
    fun `consent revocation during preparation prevents vendor show and discards the prior fill`() {
        loadAndFill()
        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)

        ConsentCenter.setHostConsent(false, false)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)

        assertEquals(0, vendorHosts.size)
        assertEquals(0, result.presented)
        assertEquals(1, result.completed)
        assertEquals(listOf(AdSkipReason.CONSENT_NOT_GRANTED), result.skipped)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals("consent_not_granted", params("ad_skipped").single()["reason"])
        ConsentCenter.setHostConsent(true, false)
        mainLooper.idle()
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
    }

    @Test
    fun `direct ERain can retry the same ad and tracked callback after a preparation rejection`() {
        var presented = 0
        var closed = 0
        val rejected = mutableListOf<AdSkipReason>()
        val tracked = TrackingAdCallback(PLACEMENT, AdFormat.INTERSTITIAL, UNIT,
            object : AdCallback() {
                override fun onAdPresented() { presented++ }
                override fun onAdClosed() { closed++ }
                override fun onAdShowRejected(reason: AdSkipReason) { rejected += reason }
            })
        val wrapper = loadDirect(tracked)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, tracked, false, false)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(AdSkipReason.HOST_NOT_RESUMED), rejected)
        assertEquals(0, vendorHosts.size)
        assertEquals(0, presented)
        assertEquals(0, params("ad_show").size)
        assertTrue(wrapper.isReady)
        assertEquals(1, params("ad_skipped").size)
        assertEquals("host_not_resumed", params("ad_skipped").single()["reason"])
        assertEquals(PLACEMENT, params("ad_skipped").single()["placement"])

        returnFromHome()
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, tracked, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, presented)
        assertEquals(0, params("ad_show").size)
        fullscreenCallback.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, presented)
        assertEquals(1, params("ad_show").size)
        assertEquals(PLACEMENT, params("ad_show").single()["placement"])
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, closed)
        assertEquals(1, params("ad_closed").size)
        assertEquals(1, rejected.size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, requests.size)
        assertFalse(wrapper.isReady)
        assertEquals(1, params("ad_skipped").size)
    }

    @Test
    fun `plain legacy callback continues exactly once when Home rejects direct ERain preparation`() {
        var nextActions = 0
        val callback = object : AdCallback() {
            override fun onNextAction() { nextActions++ }
        }
        val wrapper = loadDirect(callback)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, callback, false, false)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(1, nextActions)
        assertEquals(0, vendorHosts.size)
        assertTrue(wrapper.isReady)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, params("ad_skipped").size)
        assertEquals("host_not_resumed", params("ad_skipped").single()["reason"])
        mainLooper.idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(1, nextActions)
        assertEquals(1, params("ad_skipped").size)
    }

    @Test
    fun `direct ERain applies an interval enabled during preparation without consuming B`() {
        val callbackA = AdCallback()
        val wrapperA = loadDirect(callbackA)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapperA, callbackA, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        fullscreenCallback.onAdShowedFullScreenContent()
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertFalse(wrapperA.isReady)
        assertEquals(1, params("ad_show").size)

        var nextActions = 0
        val rejected = mutableListOf<AdSkipReason>()
        val callbackB = object : AdCallback() {
            override fun onNextAction() { nextActions++ }
            override fun onAdShowRejected(reason: AdSkipReason) {
                rejected += reason
                super.onAdShowRejected(reason)
            }
        }
        val googleB = replacementVendor()
        val wrapperB = loadDirect(callbackB, googleB)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapperB, callbackB, false, false)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(1, vendorHosts.size)

        ERainAd.getInstance().setIntervalInterstitialAd(60)
        assertTrue("The real A dismissal must establish the interval used by B",
            InterstitialFrequency.remainingMs(activity) > 0)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)

        assertEquals("Only A may reach vendor.show", 1, vendorHosts.size)
        assertEquals(listOf(AdSkipReason.INTERVAL), rejected)
        assertEquals(1, nextActions)
        assertTrue(wrapperB.isReady)
        assertSame(googleB, wrapperB.interstitialAd)
        assertEquals(1, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals("interval", params("ad_skipped").single()["reason"])
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(2, requests.size)
    }

    @Test
    fun `direct ERain applies a lowered click cap during preparation without consuming B`() {
        ERainAd.getInstance().setMaxClickAdsPerDay(2)
        val callbackA = AdCallback()
        val wrapperA = loadDirect(callbackA)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapperA, callbackA, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        fullscreenCallback.onAdShowedFullScreenContent()
        // Count an actual click through the vendor callback while A is displayed.
        fullscreenCallback.onAdClicked()
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertFalse(wrapperA.isReady)
        assertEquals(1, params("ad_click").size)

        var nextActions = 0
        val rejected = mutableListOf<AdSkipReason>()
        val callbackB = object : AdCallback() {
            override fun onNextAction() { nextActions++ }
            override fun onAdShowRejected(reason: AdSkipReason) {
                rejected += reason
                super.onAdShowRejected(reason)
            }
        }
        val googleB = replacementVendor()
        val wrapperB = loadDirect(callbackB, googleB)
        // B loads and enters preparation while one prior click is still below the cap of two.
        ERainAd.getInstance().forceShowInterstitial(activity, wrapperB, callbackB, false, false)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(1, vendorHosts.size)

        ERainAd.getInstance().setMaxClickAdsPerDay(1)
        mainLooper.idleFor(700, TimeUnit.MILLISECONDS)

        assertEquals("Only A may reach vendor.show", 1, vendorHosts.size)
        assertEquals(listOf(AdSkipReason.CLICK_CAP), rejected)
        assertEquals(1, nextActions)
        assertTrue(wrapperB.isReady)
        assertSame(googleB, wrapperB.interstitialAd)
        assertEquals(1, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals("click_cap", params("ad_skipped").single()["reason"])
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(2, requests.size)
    }

    @Test
    fun `direct ERain forwards actual vendor impression without a second canonical show`() {
        var impressions = 0
        var presented = 0
        val callback = object : AdCallback() {
            override fun onAdImpression() { impressions++ }
            override fun onAdPresented() { presented++ }
        }
        val wrapper = loadDirect(callback)
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, callback, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, impressions)
        assertEquals(0, params("ad_show").size)

        fullscreenCallback.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, presented)
        assertEquals(1, params("ad_show").size)
        assertEquals(0, impressions)
        fullscreenCallback.onAdImpression()
        mainLooper.idle()
        assertEquals(1, impressions)
        assertEquals(1, params("ad_show").size)
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertFalse(wrapper.isReady)
    }

    @Test
    fun `immersive mode failure is cosmetic and still allows the real vendor show`() {
        loadAndFill()
        Mockito.doThrow(IllegalStateException("vendor immersive mode unavailable"))
            .`when`(googleAd).setImmersiveMode(true)
        val result = RecordingShowCallback()
        InterstitialAdManager.show(activity, PLACEMENT, result,
            nextAction = InterNextAction.AfterDismiss)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, result.completed)
        assertEquals(0, result.presented)
        assertTrue(result.skipped.isEmpty())
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        fullscreenCallback.onAdShowedFullScreenContent()
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, result.presented)
        assertEquals(1, result.completed)
        assertEquals(1, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
    }

    @Test
    fun `public priority show reuses a tracked callback without early or duplicate canonical shows`() {
        var presented = 0
        var closed = 0
        val tracked = TrackingAdCallback(PLACEMENT, AdFormat.INTERSTITIAL, UNIT,
            object : AdCallback() {
                override fun onAdPresented() { presented++ }
                override fun onAdClosed() { closed++ }
            })
        val priority = ApInterstitialPriorityAd(UNIT, "", "", "")
        ERainAd.getInstance().loadPriorityInterstitialAds(activity, priority, tracked)
        assertEquals(1, requests.size)
        requests.single().onAdLoaded(googleAd)
        mainLooper.idle()
        assertSame(googleAd, priority.high1PriorityInterstitialAd.interstitialAd)
        assertEquals(0, params("ad_show").size)

        ERainAd.getInstance().forceShowInterstitialPriority(activity, priority, tracked, false)
        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(0, presented)
        assertEquals(0, params("ad_show").size)
        fullscreenCallback.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, presented)
        assertEquals(1, params("ad_show").size)
        assertEquals(PLACEMENT, params("ad_show").single()["placement"])
        fullscreenCallback.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, closed)
        assertEquals(1, params("ad_closed").size)
        assertEquals(0, params("ad_show_failed").size)
    }

    @Test
    fun `empty priority without reload reports one structured skip and default next`() {
        assertEmptyPriorityRejected(reload = false)
    }

    @Test
    fun `empty priority with reload reports one structured skip and requests the configured tier once`() {
        assertEmptyPriorityRejected(reload = true)
    }

    private fun assertEmptyPriorityRejected(reload: Boolean) {
        var nextActions = 0
        val rejected = mutableListOf<AdSkipReason>()
        val explicitPlacement = "empty-priority-placement"
        val tracked = TrackingAdCallback(explicitPlacement, AdFormat.INTERSTITIAL, UNIT,
            object : AdCallback() {
                override fun onNextAction() { nextActions++ }
                override fun onAdShowRejected(reason: AdSkipReason) {
                    rejected += reason
                    // Keep the public base class's legacy navigation behavior in the test.
                    super.onAdShowRejected(reason)
                }
            })
        // The first configured tier is high2; high1 and both later tiers are deliberately absent.
        val priority = ApInterstitialPriorityAd("", UNIT, "", "")
        ERainAd.getInstance().forceShowInterstitialPriority(activity, priority, tracked, reload)
        mainLooper.idle()

        assertEquals(1, nextActions)
        assertEquals(listOf(AdSkipReason.NOT_READY), rejected)
        assertEquals(0, vendorHosts.size)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, params("ad_skipped").size)
        assertEquals("not_ready", params("ad_skipped").single()["reason"])
        assertEquals(explicitPlacement, params("ad_skipped").single()["placement"])
        assertEquals(if (reload) listOf(UNIT) else emptyList<String>(), requestUnits)
        assertEquals(if (reload) 1 else 0, requests.size)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, nextActions)
        assertEquals(1, rejected.size)
        assertEquals(1, params("ad_skipped").size)
        assertEquals(0, vendorHosts.size)
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    private class RecordingShowCallback : InterShowCallback() {
        var shown = 0
        var presented = 0
        var closed = 0
        var completed = 0
        val skipped = mutableListOf<AdSkipReason>()
        override fun onShowed() { shown++ }
        override fun onPresented() { presented++ }
        override fun onClosed() { closed++ }
        override fun onComplete() { completed++ }
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
    }

    companion object {
        private const val PLACEMENT = "home-return-interstitial"
        private const val UNIT = "home-return-interstitial-unit"
    }
}

class InterstitialPresentationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}

/** Bootstrap real AndroidX startup because the library test manifest omits dependency providers. */
class InterstitialPresentationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppInitializer.getInstance(this).isEagerlyInitialized(ProcessLifecycleInitializer::class.java)) {
            // Robolectric retains AndroidX statics but creates a fresh Application per test.
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    androidx.lifecycle.ReportFragment.injectIfNeededIn(activity)
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            ProcessLifecycleInitializer().create(this)
        } else {
            val info = ProviderInfo().apply {
                name = InitializationProvider::class.java.name
                packageName = this@InterstitialPresentationApplication.packageName
                authority = "$packageName.androidx-startup"
                metaData = Bundle().apply {
                    putString(ProcessLifecycleInitializer::class.java.name, "androidx.startup")
                }
            }
            shadowOf(packageManager).addOrUpdateProvider(info)
            InitializationProvider().attachInfo(this, info)
        }
    }
}

/** Same external Java clock call as CachedAd, rewritten by Robolectric for the TTL test only. */
class InterstitialTestWallClock {
    fun currentTimeMillis(): Long = System.currentTimeMillis()
}
