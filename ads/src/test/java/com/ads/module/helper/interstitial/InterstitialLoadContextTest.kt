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
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.AdRequest
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
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** The shared load outlives its host; only vendor presentation needs an Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = Int02Application::class,
    shadows = [InterstitialContextLoadShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialLoadContextTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private lateinit var app: Application
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = InterstitialContextLoadShadow.requests

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InterstitialAdManager.releaseAll()
        InterstitialAutoBuffer.stop()
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        AdRemoteConfig.reset()
        AdBehavior.document.acceptSuccessfulFetch(null)
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
            NetworkCapabilities().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        ERainAd.init(app, ERainAdConfig(app).apply { facebookClientToken = "interstitial-context-test" })
        ERainAd.setIntervalInterstitialAd(0)
        ERainAd.setMaxClickAdsPerDay(0)
        InterstitialAdManager.defaultNextAction = InterNextAction.AfterDismiss
        AppOpenManager.disableAppResume()
        AppOpenManager.setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        main.idle()
    }

    @After
    fun tearDown() {
        destroyHost()
        InterstitialAdManager.releaseAll()
        AppOpenManager.setInterstitialShowing(false)
        ConsentCenter.clearHostConsent()
        AdRemoteConfig.reset()
        AdBehavior.document.acceptSuccessfulFetch(null)
        requests.clear()
    }

    @Test
    fun `activity load uses application context while late fill and cached callbacks stay intact`() {
        val loaded = mutableListOf<ApInterstitialAd?>()
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) { loaded += apInterstitialAd }
        })
        assertEquals(1, requests.size)
        assertSame("The vendor request and its paid listener must not retain the screen", app, requests.single().context)
        destroyHost()
        val ad = Int02VendorAd(UNIT)
        requests.single().callback.onAdLoaded(ad)

        assertEquals(1, loaded.size)
        assertSame(ad, loaded.single()!!.interstitialAd)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        val cached = mutableListOf<ApInterstitialAd?>()
        InterstitialAdManager.load(app, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) { cached += apInterstitialAd }
        })
        assertEquals("A cache hit reports only to the current subscriber", 1, loaded.size)
        assertEquals(1, cached.size)
        assertSame(loaded.single(), cached.single())
        assertEquals("A cache hit must not buy another vendor request", 1, requests.size)
    }

    @Test
    fun `load and show uses application context for loading and original host for presentation`() {
        var closed = 0
        var completed = 0
        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), object : InterShowCallback() {
            override fun onClosed() { closed++ }
            override fun onComplete() { completed++ }
        })
        assertEquals(1, requests.size)
        assertSame(app, requests.single().context)
        val ad = Int02VendorAd(UNIT)
        requests.single().callback.onAdLoaded(ad)
        main.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), ad.hosts)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        assertEquals(0, completed)
        ad.callback.onAdShowedFullScreenContent()
        ad.callback.onAdDismissedFullScreenContent()
        assertEquals(1, closed)
        assertEquals(1, completed)
    }

    private fun destroyHost() {
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        controller.destroy()
        main.idleFor(800, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val PLACEMENT = "interstitial-context"
        const val UNIT = "interstitial-context-test-unit"
    }
}

@Implements(value = InterstitialAd::class, isInAndroidSdk = false)
class InterstitialContextLoadShadow {
    data class Request(val context: Context, val callback: InterstitialAdLoadCallback)

    companion object {
        val requests = mutableListOf<Request>()

        @JvmStatic
        @Implementation
        fun load(context: Context, adUnitId: String, request: AdRequest, callback: InterstitialAdLoadCallback) {
            requests += Request(context, callback)
        }
    }
}
