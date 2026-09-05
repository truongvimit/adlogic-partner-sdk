package io.onboardkit.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterShowOptions
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

/** Real public flow/provider/manager chain; only external GMA and Android lifecycle are faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = InterstitialOptionsApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ObInterstitialOptionsTest {
    private lateinit var controller: ActivityController<ObPresentationActivity>
    private lateinit var activity: ObPresentationActivity
    private lateinit var provider: ERainAdProvider
    private lateinit var vendor: MockedStatic<InterstitialAd>
    private lateinit var googleAd: InterstitialAd
    private var fullscreen: FullScreenContentCallback? = null
    private val requests = mutableListOf<InterstitialAdLoadCallback>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val order = mutableListOf<String>()
    private val vendorHosts = mutableListOf<Activity>()
    private val extraProviders = mutableListOf<ERainAdProvider>()
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

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
        // The separate Application configuration isolates the installed immutable provider from
        // other flow fixtures. Keep the test-owned reference; do not reflect into SDK state.
        provider = installedProvider ?: ERainAdProvider(
            interstitialShowOptions = InterShowOptions(showLoading = false, preShowDelayMs = CUSTOM_DELAY_MS),
        ).also { installed ->
            OnboardingSdk.install(app) { adProvider = installed }
            installedProvider = installed
        }
        provider.releaseAll()
        OnboardingSdk.setCanRequestAds(true)
        OnboardingSdk.configure(onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(enabled = true, splashInterstitial = InterstitialAdUnit(listOf(UNIT)))
        }.getOrThrow()).getOrThrow()
        vendor = Mockito.mockStatic(InterstitialAd::class.java) { invocation ->
            if (invocation.method.name == "load") requests += invocation.getArgument<InterstitialAdLoadCallback>(3)
            null
        }
        googleAd = Mockito.mock(InterstitialAd::class.java)
        Mockito.`when`(googleAd.adUnitId).thenReturn(UNIT)
        Mockito.doAnswer { fullscreen = it.getArgument(0); null }.`when`(googleAd)
            .setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
        Mockito.doAnswer {
            vendorHosts += it.getArgument<Activity>(0)
            order += "vendor.show"
            null
        }.`when`(googleAd).show(Mockito.any(Activity::class.java))
        controller = Robolectric.buildActivity(ObPresentationActivity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "onboard-interstitial-options"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(app, TrackerConfig(strictValidation = true, logLevel = 0))
        AnalyticsHub.clear()
        AnalyticsHub.addPlugin(TrackkitPlugin)
    }

    @After
    fun tearDown() {
        fullscreen?.onAdDismissedFullScreenContent()
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
            if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
        }
        ShadowDialog.getLatestDialog()?.dismiss()
        if (::provider.isInitialized) provider.releaseAll()
        extraProviders.forEach { it.releaseAll() }
        AppOpenManager.getInstance().setInterstitialShowing(false)
        AppOpenManager.getInstance().releaseCachedAds()
        if (::vendor.isInitialized) vendor.close()
        AnalyticsHub.clear()
        Tracker.resetForTesting()
    }

    @Test
    fun `configured provider reaches public flow with no dialog custom delay and actual presented telemetry`() {
        loadAndFill(provider)
        val previousDialog = ShadowDialog.getLatestDialog()
        val finished = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { order += "next" }, onFinished = {
            finished += it
            order += "finish"
        })
        assertSame(previousDialog, ShadowDialog.getLatestDialog())
        mainLooper.idleFor(CUSTOM_DELAY_MS - 1, TimeUnit.MILLISECONDS)
        assertTrue(order.isEmpty())
        assertTrue(vendorHosts.isEmpty())
        assertEquals(0, count("ad_show"))

        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf("next", "vendor.show"), order)
        assertEquals(listOf(activity), vendorHosts)
        assertSame(previousDialog, ShadowDialog.getLatestDialog())
        assertTrue(finished.isEmpty())
        assertEquals(0, count("ad_show"))
        fullscreen!!.onAdShowedFullScreenContent()
        fullscreen!!.onAdShowedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, count("ad_show"))
        fullscreen!!.onAdDismissedFullScreenContent()
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf("next", "vendor.show", "finish"), order)
        assertEquals(listOf<AdSkipReason?>(null), finished)
        assertEquals(0, count("ad_skipped"))
    }

    @Test
    fun `Home during configured flow delay completes once and retains the original fill`() {
        loadAndFill(provider)
        val reasons = mutableListOf<AdSkipReason?>()
        activity.showInterstitial(PLACEMENT, onNext = { order += "next" }, onFinished = {
            reasons += it
            order += "finish"
        })
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        controller.pause().stop()
        mainLooper.idleFor(CUSTOM_DELAY_MS, TimeUnit.MILLISECONDS)

        assertTrue(vendorHosts.isEmpty())
        assertEquals(listOf("next", "finish"), order)
        assertEquals(listOf("host_not_resumed"), reasons.map { it?.key })
        assertTrue(provider.isInterstitialReady(PLACEMENT))
        assertEquals(1, count("ad_skipped"))
        assertEquals(0, count("ad_show_failed"))
        assertEquals(0, count("ad_show"))
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, reasons.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `Java configured provider queues zero delay through the same presentation path`() {
        val zero = ERainAdProviderJavaConsumer.configured(30_000L, InterShowOptions(false, 0L))
        extraProviders += zero
        loadAndFill(zero)
        val previousDialog = ShadowDialog.getLatestDialog()
        var closed = 0
        zero.showInterstitial(activity, PLACEMENT, object : ObInterstitialCallback() {
            override fun onNextAction() { order += "next" }
            override fun onAdClosed() { closed++ }
        })
        assertTrue(vendorHosts.isEmpty())
        assertTrue(order.isEmpty())
        assertSame(previousDialog, ShadowDialog.getLatestDialog())
        mainLooper.idle()
        assertEquals(listOf("next", "vendor.show"), order)
        assertEquals(listOf(activity), vendorHosts)
        assertSame(previousDialog, ShadowDialog.getLatestDialog())
        assertEquals(0, count("ad_show"))
        fullscreen!!.onAdShowedFullScreenContent()
        fullscreen!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(1, closed)
        assertEquals(1, count("ad_show"))
    }

    @Test
    fun `old Java and Kotlin constructor calls retain loading and 800 millisecond defaults`() {
        val consumers = listOf(
            ERainAdProviderJavaConsumer.legacyDefaults(),
            ERainAdProviderJavaConsumer.legacyTimeout(1_234L),
            ERainAdProvider(),
            ERainAdProvider(tierTimeoutMs = 2_345L),
            ERainAdProviderJavaConsumer.configuredDefaults(InterShowOptions()),
        )
        extraProviders += consumers
        consumers.forEach { legacy ->
            loadAndFill(legacy)
            val showsBefore = vendorHosts.size
            var closed = 0
            legacy.showInterstitial(activity, PLACEMENT, object : ObInterstitialCallback() {
                override fun onAdClosed() { closed++ }
            })
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
            assertEquals(showsBefore, vendorHosts.size)
            mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(showsBefore + 1, vendorHosts.size)
            fullscreen!!.onAdShowedFullScreenContent()
            fullscreen!!.onAdDismissedFullScreenContent()
            mainLooper.idle()
            assertEquals(1, closed)
            assertFalse(dialog.isShowing)
            legacy.releaseAll()
        }
    }

    private fun loadAndFill(target: ERainAdProvider) {
        val before = requests.size
        target.loadInterstitial(activity, PLACEMENT, InterstitialAdUnit(listOf(UNIT)))
        assertEquals(before + 1, requests.size)
        requests.last().onAdLoaded(googleAd)
        mainLooper.idle()
        assertTrue(target.isInterstitialReady(PLACEMENT))
    }

    private fun count(name: String) = events.count { it.first == name }

    companion object {
        private var installedProvider: ERainAdProvider? = null
        private val PLACEMENT = AdPlacement.SplashInterstitial
        private const val UNIT = "onboard-options-unit"
        private const val CUSTOM_DELAY_MS = 250L
    }
}

/** Distinct Robolectric sandbox; reuses the real process-lifecycle bootstrap. */
class InterstitialOptionsApplication : ObPresentationApplication()
