package com.ads.module.helper.interstitial

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.Admob
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.dialog.PrepareLoadingAdsDialog
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit

/** Remote ad_behavior_config values that reach the vendor show through the manager. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialPlacementBehaviorTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Int02InterstitialShadow.requests
    private val vendorAds = mutableListOf<Int02VendorAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AdBehavior.document.acceptSuccessfulFetch(null)
        InterstitialAdManager.releaseAll()
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
            NetworkCapabilities().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            setFacebookClientToken("placement-behavior-test")
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
    }

    @After
    fun tearDown() {
        AdBehavior.document.acceptSuccessfulFetch(null)
        vendorAds.filter { it.hosts.isNotEmpty() }.forEach { it.callback.onAdDismissedFullScreenContent() }
        InterstitialAdManager.releaseAll()
        ShadowDialog.getLatestDialog()?.dismiss()
        ConsentCenter.setHostConsent(false, false)
        if (::controller.isInitialized && activity.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        AppOpenManager.getInstance().setInterstitialShowing(false)
        requests.clear()
    }

    @Test
    fun `placement override turns off the pre-show loading dialog but still shows the ad`() {
        AdBehavior.document.acceptSuccessfulFetch(
            """{"placement_overrides":{"$PLACEMENT":{"interstitial":{"presentation":{"loading_enabled":false}}}}}""")
        val raw = loadAndFill()
        val result = RecordingShow()

        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(0, shownLoadingDialogs())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), raw.hosts)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `placement override turns on the pre-show loading dialog the format turns off`() {
        AdBehavior.document.acceptSuccessfulFetch("""{
            "interstitial":{"presentation":{"loading_enabled":false}},
            "placement_overrides":{"$PLACEMENT":{"interstitial":{"presentation":{"loading_enabled":true}}}}
        }""")
        val raw = loadAndFill()

        InterstitialAdManager.show(activity, PLACEMENT, RecordingShow())
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(1, shownLoadingDialogs())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), raw.hosts)
    }

    @Test
    fun `caller behavior decides the pre-show loading dialog of a show`() {
        AdBehavior.document.acceptSuccessfulFetch(
            """{"placement_overrides":{"$CONFIG_KEY":{"interstitial":{"presentation":{"loading_enabled":false}}}}}""")
        val raw = loadAndFill()

        InterstitialAdManager.show(activity, PLACEMENT, RecordingShow(), true, InterNextAction.AfterDismiss,
            AdBehavior.values("interstitial", CONFIG_KEY))
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(0, shownLoadingDialogs())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), raw.hosts)
    }

    @Test
    fun `ready loadAndShow honors the placement loading dialog override`() {
        AdBehavior.document.acceptSuccessfulFetch(
            """{"placement_overrides":{"$PLACEMENT":{"interstitial":{"presentation":{"loading_enabled":false}}}}}""")
        val raw = loadAndFill()

        InterstitialAdManager.loadAndShow(activity, PLACEMENT, listOf(UNIT), RecordingShow())
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(0, shownLoadingDialogs())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(activity), raw.hosts)
        assertEquals(1, requests.size)
    }

    @Test
    fun `remote click cap is the cap the suppression log reports`() {
        val raw = loadAndFill()
        AdBehavior.document.acceptSuccessfulFetch("""{"interstitial":{"frequency":{"max_clicks_per_24h":3}}}""")
        repeat(3) { Admob.getInstance().recordAdClick(activity, UNIT) }
        val result = RecordingShow()

        InterstitialAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), result.skipped)
        assertEquals(0, raw.hosts.size)
        val messages = ShadowLog.getLogsForTag("ERainStudio").map { it.msg }
        assertTrue(messages.toString(), messages.any { it.startsWith("Interstitial click cap enabled: 3 ") })
        assertTrue(messages.toString(), messages.any { it.contains("daily click cap (3/3)") })
    }

    private fun shownLoadingDialogs(): Int =
        ShadowDialog.getShownDialogs().count { it is PrepareLoadingAdsDialog && it.isShowing }

    private fun loadAndFill(): Int02VendorAd {
        val raw = Int02VendorAd(UNIT).also { vendorAds += it }
        var loaded: ApInterstitialAd? = null
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
        })
        requests.last().onAdLoaded(raw)
        mainLooper.idle()
        assertTrue(loaded != null && InterstitialAdManager.isReady(PLACEMENT))
        return raw
    }

    private class RecordingShow : InterShowCallback() {
        val skipped = mutableListOf<AdSkipReason>()
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
    }

    companion object {
        private const val PLACEMENT = "int02-placement-behavior"
        private const val CONFIG_KEY = "inter_configured"
        private const val UNIT = "int02-placement-behavior-unit"
    }
}
