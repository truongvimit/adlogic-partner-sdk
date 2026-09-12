package com.ads.module.helper.interstitial

import android.app.Activity
import android.app.Application
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
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.facebook.FacebookSdk
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
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
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit

/** Real manager -> waterfall -> ERain -> Admob; only the external ad SDK dispatch is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialLifecycleRestoreTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private var premium = false
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        InterstitialAdManager.releaseAll()
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = premium
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
            setFacebookClientToken("int02-test-client-token")
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
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        InterstitialAdManager.releaseAll()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        ConsentCenter.setHostConsent(false, false)
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        requests.clear()
    }

    @Test
    fun `Home during 800ms returns usable fill and explicit retry needs no request`() {
        assertHomeRetainsFill(InterNextAction.AfterDismiss)
    }

    @Test
    fun `UnderAd Home rejection completes once and explicit retry preserves next-before-show`() {
        assertHomeRetainsFill(InterNextAction.UnderAd)
    }

    private fun assertHomeRetainsFill(mode: InterNextAction) {
        val raw = Int02VendorAd(UNIT)
        val original = loadAndFill(raw)
        val rejected = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, rejected, nextAction = mode)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(0, raw.hosts.size)
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), rejected.skipped.map { it.key })
        assertEquals(1, rejected.completed)
        assertEquals(1, rejected.committed) // Legacy commit stays before 800 ms; TEL-02 is separate.
        assertEquals(0, rejected.closed)
        assertEquals(0, raw.hosts.size)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertTrue("ERain must not clear the wrapper after manager restores it", original.isReady)
        assertSame(raw, original.interstitialAd)
        assertSame(original, cachedThroughPublicLoad())

        controller.restart().start().resume().visible()
        mainLooper.idle()
        assertEquals("Resume alone must not auto-show the restored fill", 0, raw.hosts.size)
        val retried = RecordingShow()
        raw.beforeShow = { assertEquals(if (mode == InterNextAction.UnderAd) 1 else 0, retried.completed) }
        InterstitialAdManager.show(activity, PLACEMENT, retried, nextAction = mode)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, requests.size)
        raw.callback.onAdShowedFullScreenContent()
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, retried.closed)
        assertEquals(1, retried.completed)
        assertTrue(retried.skipped.isEmpty())
        assertFalse(original.isReady)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
    }

    @Test
    fun `process background before preparation preserves cached fill`() {
        val raw = Int02VendorAd(UNIT)
        val original = loadAndFill(raw)
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertFalse(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val result = RecordingShow()

        InterstitialAdManager.show(activity, PLACEMENT, result)

        assertEquals(listOf("show_in_background"), result.skipped.map { it.key })
        assertEquals(1, result.completed)
        assertEquals(0, result.committed)
        assertEquals(0, raw.hosts.size)
        assertSame(original, cachedThroughPublicLoad())
        assertSame(raw, original.interstitialAd)
    }

    @Test
    fun `show with a non-Activity context keeps the fill for a later trigger`() {
        val raw = Int02VendorAd(UNIT)
        val original = loadAndFill(raw)
        val result = RecordingShow()

        InterstitialAdManager.show(
            ApplicationProvider.getApplicationContext<Application>(), PLACEMENT, result,
        )
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), result.skipped.map { it.key })
        assertEquals(1, result.completed)
        assertEquals(0, raw.hosts.size)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        assertSame(original, cachedThroughPublicLoad())
    }

    @Test
    fun `replacement B arriving before A rejection wins the cache`() {
        val rawA = Int02VendorAd(UNIT)
        loadAndFill(rawA)
        val rejectedA = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, rejectedA)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        val rawB = Int02VendorAd(UNIT)
        val replacement = loadAndFill(rawB)
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), rejectedA.skipped.map { it.key })
        assertEquals(1, rejectedA.completed)
        assertSame(replacement, cachedThroughPublicLoad())
        assertSame(rawB, replacement.interstitialAd)
        assertTrue(replacement.isReady)
        assertEquals(0, rawA.hosts.size)
        assertEquals(0, rawB.hosts.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `release during preparation forbids restoring A`() {
        assertReleasePreventsRestore { InterstitialAdManager.release(PLACEMENT) }
    }

    @Test
    fun `releaseAll during preparation forbids restoring A`() {
        assertReleasePreventsRestore { InterstitialAdManager.releaseAll() }
    }

    private fun assertReleasePreventsRestore(release: () -> Unit) {
        val raw = Int02VendorAd(UNIT)
        loadAndFill(raw)
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        release()
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), result.skipped.map { it.key })
        assertEquals(1, result.completed)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, raw.hosts.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `premium acquired during preparation cannot be undone by lifecycle restore`() {
        val raw = Int02VendorAd(UNIT)
        loadAndFill(raw)
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        premium = true
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), result.skipped.map { it.key })
        assertEquals(1, result.completed)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, raw.hosts.size)
    }

    @Test
    @Config(instrumentedPackages = [
        "com.ads.module.helper.CachedAd",
        "com.ads.module.helper.interstitial.Int02WallClock",
    ])
    fun `restored fill expires at original TTL without a new hour`() {
        val clock = Int02WallClock()
        assertEquals(ShadowSystem.currentTimeMillis(), clock.now())
        val filledAt = clock.now()
        val raw = Int02VendorAd(UNIT)
        val original = loadAndFill(raw)
        assertEquals(filledAt, clock.now())
        val ttl = TimeUnit.HOURS.toMillis(1)
        ShadowSystemClock.advanceBy(ttl - 1_600, TimeUnit.MILLISECONDS)
        mainLooper.idle()
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        leaveForeground()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf("show_in_background"), result.skipped.map { it.key })
        assertSame(raw, original.interstitialAd)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        val remaining = filledAt + ttl - clock.now()
        assertTrue("Dialog setup can advance virtual time; stay before original expiry", remaining in 1..700)
        ShadowSystemClock.advanceBy(remaining - 1, TimeUnit.MILLISECONDS)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS)
        assertEquals(filledAt + ttl, clock.now())
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(1, requests.size)
    }

    @Test
    fun `real vendor failure using same numeric code in another domain consumes fill once`() {
        val raw = Int02VendorAd(UNIT)
        val original = loadAndFill(raw)
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)

        val failure = AdError(9001, "external vendor failure", "com.google.android.gms.ads")
        raw.callback.onAdFailedToShowFullScreenContent(failure)
        raw.callback.onAdFailedToShowFullScreenContent(failure)
        raw.callback.onAdDismissedFullScreenContent()

        assertEquals(listOf("failed_to_show"), result.skipped.map { it.key })
        assertEquals(1, result.completed)
        assertEquals(0, result.closed)
        assertFalse(original.isReady)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(1, requests.size)
    }

    private fun leaveForeground() {
        controller.pause().stop()
        assertFalse(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun loadAndFill(raw: Int02VendorAd): ApInterstitialAd {
        var loaded: ApInterstitialAd? = null
        val before = requests.size
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
        })
        assertEquals(before + 1, requests.size)
        requests.last().onAdLoaded(raw)
        mainLooper.idle()
        val result = requireNotNull(loaded)
        assertSame(raw, result.interstitialAd)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        return result
    }

    private fun cachedThroughPublicLoad(): ApInterstitialAd {
        var loaded: ApInterstitialAd? = null
        val before = requests.size
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
        })
        assertEquals(before, requests.size)
        return requireNotNull(loaded)
    }

    private class RecordingShow : InterShowCallback() {
        var committed = 0
        var closed = 0
        var completed = 0
        val skipped = mutableListOf<AdSkipReason>()
        override fun onShowed() { committed++ }
        override fun onClosed() { closed++ }
        override fun onComplete() { completed++ }
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
    }

    companion object {
        private const val PLACEMENT = "int02-lifecycle"
        private const val UNIT = "int02-test-unit"
    }
}

class Int02VendorAd(private val unit: String) : InterstitialAd() {
    val hosts = mutableListOf<Activity>()
    var beforeShow: (() -> Unit)? = null
    private var storedCallback: FullScreenContentCallback? = null
    private var paidListener: OnPaidEventListener? = null
    private var placementValue = 0L
    val callback: FullScreenContentCallback get() = requireNotNull(storedCallback)
    override fun getAdUnitId(): String = unit
    override fun show(activity: Activity) { beforeShow?.invoke(); hosts += activity }
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { storedCallback = callback }
    override fun getFullScreenContentCallback(): FullScreenContentCallback = callback
    override fun setImmersiveMode(enabled: Boolean) = Unit
    override fun getResponseInfo(): ResponseInfo = throw UnsupportedOperationException("No paid event in lifecycle fixture")
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paidListener = listener }
    override fun getOnPaidEventListener(): OnPaidEventListener = requireNotNull(paidListener)
    override fun getPlacementId(): Long = placementValue
    override fun setPlacementId(value: Long) { placementValue = value }
}

@Implements(value = InterstitialAd::class, isInAndroidSdk = false)
class Int02InterstitialShadow {
    companion object {
        val requests = mutableListOf<InterstitialAdLoadCallback>()
        @JvmStatic @Implementation
        fun load(context: Context, adUnitId: String, request: AdRequest, callback: InterstitialAdLoadCallback) {
            requests += callback
        }
    }
}

@Implements(value = MobileAds::class, isInAndroidSdk = false)
class Int02MobileAdsShadow {
    companion object {
        private var configuration = RequestConfiguration.Builder().build()
        @JvmStatic @Implementation
        fun initialize(context: Context, callback: OnInitializationCompleteListener?) = Unit
        @JvmStatic @Implementation
        fun setRequestConfiguration(value: RequestConfiguration) { configuration = value }
        @JvmStatic @Implementation
        fun getRequestConfiguration(): RequestConfiguration = configuration
    }
}

@Implements(value = FacebookSdk::class, isInAndroidSdk = false)
class Int02FacebookShadow {
    companion object {
        @JvmStatic @Implementation fun setClientToken(token: String) = Unit
        @JvmStatic @Implementation fun sdkInitialize(context: Context) = Unit
    }
}

class Int02Activity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}

/** Bootstrap dependency startup through Android's real provider, including reused AndroidX statics. */
class Int02Application : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppInitializer.getInstance(this).isEagerlyInitialized(ProcessLifecycleInitializer::class.java)) {
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
                packageName = this@Int02Application.packageName
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

/** Same external clock call as CachedAd; instrumented only for the TTL behavior test. */
class Int02WallClock {
    fun now(): Long = System.currentTimeMillis()
}
