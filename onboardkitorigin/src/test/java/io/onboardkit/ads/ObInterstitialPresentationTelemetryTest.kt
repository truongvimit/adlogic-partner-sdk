package io.onboardkit.ads

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
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.analytics.AnalyticsHub
import io.onboardkit.core.analytics.TrackkitPlugin
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackerConfig
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

/** Public onboarding flow through its real provider/SDK, with only external vendor boundaries faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ObPresentationApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ObInterstitialPresentationTelemetryTest {
    private lateinit var app: Application
    private lateinit var controller: ActivityController<ObPresentationActivity>
    private lateinit var activity: ObPresentationActivity
    private lateinit var provider: ERainAdProvider
    private lateinit var vendor: MockedStatic<InterstitialAd>
    private lateinit var googleAd: InterstitialAd
    private var fullscreen: FullScreenContentCallback? = null
    private val requests = mutableListOf<InterstitialAdLoadCallback>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val vendorHosts = mutableListOf<Activity>()
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
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        Mockito.mockStatic(MobileAds::class.java).use {
            Mockito.mockStatic(Class.forName("com.facebook.FacebookSdk")).use {
                ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                    setFacebookClientToken("test-client-token")
                })
            }
        }
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        provider = (OnboardingSdk.provider() as? ERainAdProvider) ?: ERainAdProvider().also { installed ->
            OnboardingSdk.install(app) { adProvider = installed }
        }
        assertSame(provider, OnboardingSdk.provider())
        provider.releaseAll()
        OnboardingSdk.setCanRequestAds(true)
        configureAds()
        vendor = Mockito.mockStatic(InterstitialAd::class.java) { invocation ->
            if (invocation.method.name == "load") requests += invocation.getArgument<InterstitialAdLoadCallback>(3)
            null
        }
        googleAd = Mockito.mock(InterstitialAd::class.java)
        Mockito.`when`(googleAd.adUnitId).thenReturn(UNIT)
        Mockito.doAnswer { fullscreen = it.getArgument(0); null }.`when`(googleAd)
            .setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
        Mockito.doAnswer { vendorHosts += it.getArgument<Activity>(0); null }.`when`(googleAd)
            .show(Mockito.any(Activity::class.java))
        controller = Robolectric.buildActivity(ObPresentationActivity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "onboard-interstitial-presentation"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
        AnalyticsHub.clear()
        AnalyticsHub.addPlugin(TrackkitPlugin)
    }

    @After
    fun tearDown() {
        // Deliver the external terminal even after a failed assertion, releasing the real flow owner.
        fullscreen?.onAdDismissedFullScreenContent()
        ShadowDialog.getLatestDialog()?.dismiss()
        if (::provider.isInitialized) provider.releaseAll()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        AppOpenManager.getInstance().releaseCachedAds()
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        if (::vendor.isInitialized) vendor.close()
        AnalyticsHub.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `vendor failure is one canonical show failure while public flow finishes once`() {
        loadAndFill()
        val order = mutableListOf<String>()
        val reasons = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { order += "next" }, onFinished = {
            reasons += it
            order += "finish"
        })
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        assertEquals(listOf("next"), order)
        assertEquals(0, params("ad_show").size)

        val failure = AdError(42, "vendor failure", "test-vendor")
        fullscreen!!.onAdFailedToShowFullScreenContent(failure)
        fullscreen!!.onAdFailedToShowFullScreenContent(failure)
        mainLooper.idle()

        assertEquals(listOf("next", "finish"), order)
        assertEquals(listOf(AdSkipReason.FAILED_TO_SHOW), reasons)
        assertFalse(provider.isInterstitialReady(PLACEMENT))
        assertEquals(1, params("ad_show_failed").size)
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_skipped").size)
    }

    @Test
    fun `Home rejection reports one canonical skip and preserves next before finish`() {
        loadAndFill()
        val order = mutableListOf<String>()
        val reasons = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { order += "next" }, onFinished = {
            reasons += it
            order += "finish"
        })
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertTrue(vendorHosts.isEmpty())
        assertEquals(listOf("next", "finish"), order)
        assertEquals(listOf("host_not_resumed"), reasons.map { it?.key })
        assertTrue(provider.isInterstitialReady(PLACEMENT))
        assertEquals(0, params("ad_show").size)
        assertEquals(0, params("ad_show_failed").size)
        assertEquals(1, params("ad_skipped").size)
        assertEquals("host_not_resumed", params("ad_skipped").single()["reason"])
    }

    @Test
    fun `navigation commit alone cannot arm the host return presentation fallback`() {
        loadAndFill()
        var nextCalls = 0
        val finished = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { nextCalls++ }, onFinished = finished::add)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, nextCalls)
        assertEquals(listOf(activity), vendorHosts)
        assertTrue(finished.isEmpty())
        assertEquals(0, params("ad_show").size)

        // No vendor presented callback yet: this pause/resume proves no ad presentation.
        controller.pause().resume()
        mainLooper.idle()
        assertTrue("Commit must not arm a presented-ad fallback", finished.isEmpty())

        fullscreen!!.onAdShowedFullScreenContent()
        fullscreen!!.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, params("ad_show").size)
        controller.pause().resume()
        mainLooper.idle()
        assertEquals(listOf<AdSkipReason?>(null), finished)
        assertEquals(1, nextCalls)
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, finished.size)
    }

    @Test
    fun `a local flow guard still owns its one skipped event without reaching the provider`() {
        configureAds(enabled = false)
        val order = mutableListOf<String>()
        activity.showInterstitial(PLACEMENT, onNext = { order += "next" }, onFinished = {
            assertEquals(AdSkipReason.ADS_OFF_IN_CONFIG, it)
            order += "finish"
        })

        assertEquals(listOf("next", "finish"), order)
        assertTrue(requests.isEmpty())
        assertTrue(vendorHosts.isEmpty())
        assertEquals(1, params("ad_skipped").size)
        assertEquals("ads_off_config", params("ad_skipped").single()["reason"])
    }

    @Test
    fun `presented callback after host pause still arms the real return fallback`() {
        loadAndFill()
        var nextCalls = 0
        val finished = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { nextCalls++ }, onFinished = finished::add)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        controller.pause()

        fullscreen!!.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertTrue(finished.isEmpty())
        assertEquals(1, params("ad_show").size)
        controller.resume()
        mainLooper.idle()

        assertEquals(1, nextCalls)
        assertEquals(listOf<AdSkipReason?>(null), finished)
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, finished.size)
        assertEquals(0, params("ad_skipped").size)
    }

    @Test
    fun `old one argument provider callback still receives the reported terminal once`() {
        loadAndFill()
        var nextCalls = 0
        val skipped = mutableListOf<AdSkipReason>()
        provider.showInterstitial(activity, PLACEMENT, object : ObInterstitialCallback() {
            override fun onNextAction() { nextCalls++ }
            override fun onAdSkipped(reason: AdSkipReason) { skipped += reason }
        })
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, nextCalls)
        assertEquals(listOf(activity), vendorHosts)

        val failure = AdError(42, "vendor failure", "test-vendor")
        fullscreen!!.onAdFailedToShowFullScreenContent(failure)
        fullscreen!!.onAdFailedToShowFullScreenContent(failure)
        mainLooper.idle()

        assertEquals(1, nextCalls)
        assertEquals(listOf(AdSkipReason.FAILED_TO_SHOW), skipped)
        assertEquals(1, params("ad_show_failed").size)
        assertEquals(0, params("ad_skipped").size)
    }

    @Test
    fun `vendor presented after host destruction cannot register a return observer`() {
        loadAndFill()
        val finished = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onFinished = finished::add)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), vendorHosts)
        controller.pause().stop().destroy()
        mainLooper.idle()
        assertEquals(Lifecycle.State.DESTROYED, activity.lifecycle.currentState)
        val lifecycle = activity.lifecycle as LifecycleRegistry
        val observersAfterDestroy = lifecycle.observerCount

        fullscreen!!.onAdShowedFullScreenContent()
        mainLooper.idle()

        assertEquals("A destroyed host must not gain a presentation-return observer",
            observersAfterDestroy, lifecycle.observerCount)
        assertTrue(finished.isEmpty())
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf<AdSkipReason?>(null), finished)
    }

    @Test
    fun `late vendor presented after terminal cannot add an observer or finish twice`() {
        loadAndFill()
        var nextCalls = 0
        val finished = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { nextCalls++ }, onFinished = finished::add)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, nextCalls)
        assertEquals(listOf<AdSkipReason?>(null), finished)
        val lifecycle = activity.lifecycle as LifecycleRegistry
        val observersAfterTerminal = lifecycle.observerCount

        fullscreen!!.onAdShowedFullScreenContent()
        mainLooper.idle()

        assertEquals(observersAfterTerminal, lifecycle.observerCount)
        assertEquals(1, finished.size)
        assertEquals(1, nextCalls)
        assertEquals(0, params("ad_show").size)
    }

    private fun configureAds(enabled: Boolean = true) {
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(enabled = enabled, splashInterstitial = InterstitialAdUnit(listOf(UNIT)))
        }.getOrThrow()).getOrThrow()
    }

    private fun loadAndFill() {
        provider.loadInterstitial(activity, PLACEMENT, InterstitialAdUnit(listOf(UNIT)))
        assertEquals(1, requests.size)
        requests.single().onAdLoaded(googleAd)
        mainLooper.idle()
        assertTrue(provider.isInterstitialReady(PLACEMENT))
    }

    private fun params(name: String) = events.filter { it.first == name }.map { it.second }

    companion object {
        private val PLACEMENT = AdPlacement.SplashInterstitial
        private const val UNIT = "onboard-presentation-unit"
    }
}

class ObPresentationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.ads.module.R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}

/** Real AndroidX process lifecycle bootstrap for a library unit-test manifest. */
class ObPresentationApplication : Application() {
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
                packageName = this@ObPresentationApplication.packageName
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
