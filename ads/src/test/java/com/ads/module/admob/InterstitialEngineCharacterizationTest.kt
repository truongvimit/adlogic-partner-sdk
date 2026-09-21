package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.engine.InterstitialEngine
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02InterstitialShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Int02VendorAd
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialEngineCharacterizationTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val vendorAds = mutableListOf<Int02VendorAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AdBehavior.document.acceptSuccessfulFetch(null)
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
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
            facebookClientToken = "inter-engine-characterization-token"
        })
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        InterstitialAdManager.defaultNextAction = InterNextAction.AfterDismiss
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    @After
    fun tearDown() {
        vendorAds.filter { it.hosts.isNotEmpty() }.forEach { it.callback.onAdDismissedFullScreenContent() }
        ShadowDialog.getLatestDialog()?.dismiss()
        if (::controller.isInitialized && activity.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        mainLooper.idleFor(2, TimeUnit.SECONDS)
        AppOpenManager.getInstance().setInterstitialShowing(false)
        ConsentCenter.setHostConsent(false, false)
        AdBehavior.document.acceptSuccessfulFetch(null)
        requests.clear()
    }

    @Test
    fun `a held request gate answers getInterstitialAds with exactly one null load`() {
        val loads = mutableListOf<ApInterstitialAd?>()
        AdGate.holdRequests().use {
            InterstitialEngine.load(activity, UNIT, object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) { loads += apInterstitialAd }
            })
        }
        mainLooper.idle()

        assertEquals("gate decline must answer the caller exactly once", 1, loads.size)
        assertNull("gate decline must answer with no ad", loads.single())
        assertEquals("gate decline must not reach the vendor", 0, requests.size)
    }

    @Test
    fun `the bundled pre-show delay dispatches the vendor show at exactly 800 ms`() {
        val raw = newVendor()
        InterstitialEngine.show(activity, ApInterstitialAd(raw), AdCallback(), false)

        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertEquals("vendor show dispatched before the 800 ms pre-show delay", 0, raw.hosts.size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals("vendor show not dispatched at the 800 ms pre-show delay", listOf(activity), raw.hosts)
    }

    @Test
    fun `the pre-show delay is read from interstitial presentation pre_show_delay_ms`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial":{"presentation":{"pre_show_delay_ms":300}}}""")
        val raw = newVendor()
        InterstitialEngine.show(activity, ApInterstitialAd(raw), AdCallback(), false)

        mainLooper.idleFor(299, TimeUnit.MILLISECONDS)
        assertEquals("vendor show dispatched before the configured 300 ms", 0, raw.hosts.size)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals("vendor show ignored the configured 300 ms pre-show delay", listOf(activity), raw.hosts)
    }

    @Test
    fun `under-ad onNextAction waits for the pre-show tick and runs before the vendor show`() {
        val raw = newVendor()
        var next = 0
        var nextAtShow = -1
        raw.beforeShow = { nextAtShow = next }
        InterstitialEngine.show(activity, ApInterstitialAd(raw), object : AdCallback() {
            override fun onNextAction() { next++ }
        }, true)

        mainLooper.idleFor(799, TimeUnit.MILLISECONDS)
        assertEquals("under-ad onNextAction ran before the pre-show tick", 0, next)
        mainLooper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals("under-ad onNextAction must run once on the pre-show tick", 1, next)
        assertEquals("under-ad onNextAction must already have run when show() is called", 1, nextAtShow)
    }

    @Test
    fun `a vendor show failure other than show-in-background drops the relayed wrapper`() {
        val raw = newVendor()
        val wrapper = ApInterstitialAd(raw)
        val failures = mutableListOf<AdError?>()
        InterstitialEngine.show(activity, wrapper, object : AdCallback() {
            override fun onAdFailedToShow(adError: AdError?) { failures += adError }
        }, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)

        raw.callback.onAdFailedToShowFullScreenContent(AdError(3, "vendor refused", "com.google.android.gms.ads"))

        assertEquals(listOf(3), failures.map { it?.code })
        assertNull("a non-9001 show failure must clear the wrapper", wrapper.interstitialAd)
    }

    @Test
    fun `a show-in-background rejection keeps the relayed wrapper for a later trigger`() {
        val raw = newVendor()
        val wrapper = ApInterstitialAd(raw)
        val failures = mutableListOf<AdError?>()
        InterstitialEngine.show(
            ApplicationProvider.getApplicationContext<Application>(), wrapper, object : AdCallback() {
                override fun onAdFailedToShow(adError: AdError?) { failures += adError }
            }, false,
        )
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(InterstitialEngine.ERROR_CODE_SHOW_IN_BACKGROUND), failures.map { it?.code })
        assertEquals(0, raw.hosts.size)
        assertSame("a 9001 rejection must keep the wrapper's fill", raw, wrapper.interstitialAd)
    }

    private fun newVendor() = Int02VendorAd(UNIT).also { vendorAds += it }

    private companion object {
        const val UNIT = "inter-engine-characterization-unit"
    }
}
