package com.ads.module.admob

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import java.time.Duration
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock

/** Public manager calls, with only the vendor network boundary replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpenManagerTest {
    private lateinit var app: Application
    private lateinit var vendor: FakeLoader
    private lateinit var manager: AppOpenManager
    private var premium = false
    private val queriedContexts = mutableListOf<Context>()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        vendor = FakeLoader()
        manager = AppOpenManager(vendor)
        premium = false
        queriedContexts.clear()
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean {
                queriedContexts += context
                return premium
            }
        })
        ConsentCenter.setHostConsent(true, false)
        networkAvailable(true)
        // Old test instances have application-lifetime observers; settle their install notification
        // before recording which context this newly initialized manager uses.
        mainLooper.idle()
        queriedContexts.clear()
    }

    @After
    fun tearDown() {
        manager.disableAppResume()
        app.unregisterActivityLifecycleCallbacks(manager)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(manager)
    }

    @Test
    fun `repeated resume requests share one vendor load until it finishes`() {
        manager.init(app, "resume-unit")

        manager.fetchAd(false)
        manager.fetchAd(false)

        assertEquals(1, vendor.requests.size)
    }

    @Test
    fun `same unit and repeated initialization preserve the active request and buffer`() {
        manager.init(app, "resume-unit")
        manager.init(app, "resume-unit")
        manager.setAppResumeAdId("resume-unit")
        assertEquals(1, vendor.requests.size)

        vendor.fill(0)
        manager.setAppResumeAdId("resume-unit")
        manager.fetchAd(false)
        assertTrue(manager.isAdAvailable(false))
        assertEquals(1, vendor.requests.size)
    }

    @Test
    fun `disabled resume neither requests nor accepts an older fill`() {
        manager.disableAppResume()
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(0, vendor.requests.size)

        manager.enableAppResume()
        assertEquals(1, vendor.requests.size)
        manager.disableAppResume()
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        manager.fetchAd(false)
        assertEquals(1, vendor.requests.size)
    }

    @Test
    fun `changing the unit rejects the previous request and keeps only the new fill`() {
        manager.init(app, "old-unit")
        manager.setAppResumeAdId("new-unit")
        assertEquals(listOf("old-unit", "new-unit"), vendor.requests.map { it.unitId })

        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        vendor.fill(1)
        assertTrue(manager.isAdAvailable(false))
        vendor.fill(0)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `blank unit invalidates ready and pending ads without calling the vendor`() {
        manager.init(app, "resume-unit")
        manager.setAppResumeAdId(" ")
        vendor.fill(0)
        manager.fetchAd(false)
        assertFalse(manager.isAdAvailable(false))
        assertEquals(1, vendor.requests.size)
    }

    @Test
    fun `releasing the cache invalidates pending fills as well`() {
        manager.init(app, "resume-unit")
        manager.releaseCachedAds()
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))

        manager.fetchAd(false)
        vendor.fill(1)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `load timeout releases ownership and its late callback cannot fill a newer request`() {
        manager.init(app, "resume-unit")
        mainLooper.idleFor(30, TimeUnit.SECONDS)
        manager.fetchAd(false)
        assertEquals(1, vendor.requests.size)

        mainLooper.idleFor(5, TimeUnit.SECONDS)
        manager.fetchAd(false)
        assertEquals(2, vendor.requests.size)
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        vendor.fill(1)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `a fill after deep sleep cannot outrun the elapsed load deadline`() {
        manager.init(app, "resume-unit")
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(31))
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        // idleFor uses setCurrentTimeMillis in Robolectric 4.13, which erases the simulated sleep
        // offset. The shadow's sleep advances uptime and elapsed time together without rewinding.
        SystemClock.sleep(5_000L)
        manager.fetchAd(false)
        assertEquals(2, vendor.requests.size)
    }

    @Test
    fun `failed loads back off to a bounded cooldown and do not retry without a trigger`() {
        manager.init(app, "resume-unit")
        listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L).forEachIndexed { index, delay ->
            vendor.fail(index)
            manager.fetchAd(false)
            mainLooper.idleFor(delay - 1, TimeUnit.MILLISECONDS)
            manager.fetchAd(false)
            assertEquals(index + 1, vendor.requests.size)
            mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(index + 1, vendor.requests.size)
            manager.fetchAd(false)
            assertEquals(index + 2, vendor.requests.size)
        }
    }

    @Test
    fun `a synchronous vendor fill is accepted and prevents a duplicate request`() {
        vendor.synchronousFill = true
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(1, vendor.requests.size)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `dispatch exceptions settle the request and allow a later retry`() {
        vendor.throwOnLoad = true
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(1, vendor.requests.size)
        mainLooper.idleFor(5, TimeUnit.SECONDS)
        vendor.throwOnLoad = false
        manager.fetchAd(false)
        vendor.fill(1)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `consent reopening preloads once without showing and revocation rejects late fill`() {
        ConsentCenter.setHostConsent(false, false)
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(0, vendor.requests.size)

        ConsentCenter.setHostConsent(true, false)
        mainLooper.idle()
        assertEquals(1, vendor.requests.size)
        ConsentCenter.setHostConsent(false, false)
        mainLooper.idle()
        val late = vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        assertEquals(0, late.showCount)
    }

    @Test
    fun `personalization change rejects a pending fill even while requests remain authorized`() {
        ConsentCenter.setHostConsent(true, true)
        manager.init(app, "resume-unit")
        ConsentCenter.setHostConsent(true, false)
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
        mainLooper.idle()
        assertEquals(2, vendor.requests.size)
        assertEquals("1", vendor.requests[1].request.getNetworkExtrasBundle(AdMobAdapter::class.java)?.getString("npa"))
        vendor.fill(1)
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    fun `personalization change invalidates a ready ad and preloads the new choice`() {
        ConsentCenter.setHostConsent(true, true)
        manager.init(app, "resume-unit")
        vendor.fill(0)
        assertTrue(manager.isAdAvailable(false))
        ConsentCenter.setHostConsent(true, false)
        mainLooper.idle()
        assertFalse(manager.isAdAvailable(false))
        assertEquals(2, vendor.requests.size)
    }

    @Test
    fun `premium is checked with application context even before there is an activity`() {
        premium = true
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(0, vendor.requests.size)
        assertTrue(queriedContexts.isNotEmpty())
        assertTrue(queriedContexts.all { it === app.applicationContext })

        premium = false
        Entitlement.notifyChanged()
        mainLooper.idle()
        assertEquals(1, vendor.requests.size)
        vendor.fill(0)
        assertTrue(manager.isAdAvailable(false))
        premium = true
        Entitlement.notifyChanged()
        mainLooper.idle()
        assertFalse(manager.isAdAvailable(false))
    }

    @Test
    fun `fill rechecks premium even before an entitlement notification is delivered`() {
        manager.init(app, "resume-unit")
        premium = true
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
    }

    @Test
    fun `offline request and a fill delivered after disconnection are both gated`() {
        networkAvailable(false)
        manager.init(app, "resume-unit")
        manager.fetchAd(false)
        assertEquals(0, vendor.requests.size)
        networkAvailable(true)
        manager.fetchAd(false)
        assertEquals(1, vendor.requests.size)
        networkAvailable(false)
        vendor.fill(0)
        assertFalse(manager.isAdAvailable(false))
    }

    @Test
    fun `worker calls and vendor callbacks are serialized onto the main thread`() {
        Thread { manager.init(app, "resume-unit"); manager.fetchAd(false) }.apply { start(); join() }
        assertEquals(0, vendor.requests.size)
        mainLooper.idle()
        assertEquals(1, vendor.requests.size)
        assertSame(Looper.getMainLooper(), vendor.requests.single().looper)

        Thread { vendor.fill(0) }.apply { start(); join() }
        assertFalse(manager.isAdAvailable(false))
        mainLooper.idle()
        assertTrue(manager.isAdAvailable(false))
    }

    @Test
    @Config(application = ResumeLifecycleApplication::class)
    fun `closing ad A does not erase ad B that filled during its presentation`() {
        manager.init(app, "resume-unit")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            assertTrue(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            val first = vendor.fill(0)
            manager.showAdIfAvailable(false)
            assertEquals(1, first.showCount)
            manager.fetchAd(false)
            val second = vendor.fill(1)
            assertTrue(manager.isAdAvailable(false))
            first.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
            assertTrue(manager.isAdAvailable(false))
            first.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
            manager.showAdIfAvailable(false)
            assertEquals(1, second.showCount)
            second.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
        } finally {
            manager.disableAppResume()
            activity.pause().stop().destroy()
            mainLooper.idleFor(1, TimeUnit.SECONDS)
        }
    }

    @Test
    @Config(application = ResumeLifecycleApplication::class, shadows = [LegacyAppOpenVendorShadow::class])
    fun `closing a legacy splash leaves an independent resume request able to fill`() {
        LegacyAppOpenVendorShadow.callbacks.clear()
        manager.init(app, "resume-unit")
        manager.setSplashActivity(null, "splash-unit", 5_000)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            manager.fetchAd(true)
            assertEquals(1, LegacyAppOpenVendorShadow.callbacks.size)
            val splash = FakeAd("splash-unit")
            LegacyAppOpenVendorShadow.callbacks.single().onAdLoaded(splash)
            manager.showAdIfAvailable(true)
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
            assertEquals(1, splash.showCount)
            assertEquals(1, vendor.requests.size)

            splash.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
            vendor.fill(0)

            assertTrue(manager.isAdAvailable(false))
            assertEquals(1, vendor.requests.size)
        } finally {
            manager.disableAppResume()
            ShadowDialog.getLatestDialog()?.dismiss()
            activity.pause().stop().destroy()
            mainLooper.idleFor(1, TimeUnit.SECONDS)
        }
    }

    @Test
    @Config(application = ResumeLifecycleApplication::class)
    fun `premium acquired after fill prevents the public show path without waiting for notification`() {
        manager.init(app, "resume-unit")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            assertTrue(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            val ad = vendor.fill(0)
            assertTrue(manager.isAdAvailable(false))
            premium = true
            manager.showAdIfAvailable(false)
            assertEquals(0, ad.showCount)
        } finally {
            manager.disableAppResume()
            activity.pause().stop().destroy()
            mainLooper.idleFor(1, TimeUnit.SECONDS)
        }
    }

    private fun networkAvailable(available: Boolean) {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(connectivity)
        if (!available) {
            // Robolectric 4.13 dereferences a null NetworkInfo inside getActiveNetwork().
            // A network without capabilities exercises the same real request gate safely.
            shadow.setNetworkCapabilities(connectivity.activeNetwork, null)
            return
        }
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadow.setNetworkCapabilities(
            connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) },
        )
    }

    private class FakeLoader : AppResumeAdLoader {
        data class Request(
            val unitId: String,
            val request: AdRequest,
            val callback: AppOpenAd.AppOpenAdLoadCallback,
            val looper: Looper?,
        )

        val requests = mutableListOf<Request>()
        var synchronousFill = false
        var throwOnLoad = false

        override fun load(
            context: Context,
            unitId: String,
            request: AdRequest,
            callback: AppOpenAd.AppOpenAdLoadCallback,
        ) {
            requests += Request(unitId, request, callback, Looper.myLooper())
            if (throwOnLoad) throw IllegalStateException("Vendor dispatch failed")
            if (synchronousFill) fill(requests.lastIndex)
        }

        fun fill(index: Int): FakeAd = FakeAd(requests[index].unitId).also {
            requests[index].callback.onAdLoaded(it)
        }

        fun fail(index: Int) {
            requests[index].callback.onAdFailedToLoad(LoadAdError(3, "No fill", "test", null, null))
        }
    }

    private class FakeAd(private val unitId: String) : AppOpenAd() {
        var showCount = 0
        private var contentCallback: FullScreenContentCallback? = null
        private var paidListener: OnPaidEventListener? = null
        private var placement = 0L

        override fun show(activity: Activity) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            showCount++
            contentCallback?.onAdShowedFullScreenContent()
        }

        override fun getAdUnitId(): String = unitId
        override fun getResponseInfo(): ResponseInfo = error("No mediation response in the vendor fake")
        override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { contentCallback = callback }
        override fun getFullScreenContentCallback(): FullScreenContentCallback? = contentCallback
        override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paidListener = listener }
        override fun getOnPaidEventListener(): OnPaidEventListener? = paidListener
        override fun setImmersiveMode(immersiveMode: Boolean) = Unit
        override fun getPlacementId(): Long = placement
        override fun setPlacementId(placementId: Long) { placement = placementId }
    }
}

/** Only the legacy GMA network boundary is replaced; public manager load/show callbacks stay real. */
@Implements(value = AppOpenAd::class, isInAndroidSdk = false)
class LegacyAppOpenVendorShadow {
    companion object {
        val callbacks = mutableListOf<AppOpenAd.AppOpenAdLoadCallback>()

        @JvmStatic
        @Implementation
        fun load(context: Context, unitId: String, request: AdRequest, callback: AppOpenAd.AppOpenAdLoadCallback) {
            callbacks += callback
        }
    }
}

/** The library test manifest omits dependency providers; bootstrap the real AndroidX startup. */
class ResumeLifecycleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppInitializer.getInstance(this).isEagerlyInitialized(ProcessLifecycleInitializer::class.java)) {
            // Robolectric reuses AndroidX statics while creating a fresh Application for each test.
            // LifecycleDispatcher's one-time registration belongs to the previous Application;
            // reproduce that framework registration before attaching the process observer here.
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
                packageName = this@ResumeLifecycleApplication.packageName
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
