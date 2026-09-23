package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.engine.InterstitialEngine
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02InterstitialShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Int02VendorAd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    sdk = [28], application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class, Int02FacebookShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class InterstitialLoadingOwnershipTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private val activity get() = controller.get()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val vendorAds = mutableListOf<Int02VendorAd>()
    private val requests get() = Int02InterstitialShadow.requests

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        requests.clear()
        InterstitialAdManager.releaseAll()
        AdBehavior.document.acceptSuccessfulFetch("{}")
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
        ERainAd.init(app, ERainAdConfig(app).apply { facebookClientToken = "interstitial-owner-test" })
        ERainAd.setIntervalInterstitialAd(0)
        ERainAd.setMaxClickAdsPerDay(0)
        InterstitialAdManager.defaultNextAction = InterNextAction.AfterDismiss
        AppOpenManager.disableAppResume()
        AppOpenManager.setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        main.idle()
    }

    @After
    fun tearDown() {
        vendorAds.filter { it.hosts.isNotEmpty() }.forEach { it.callback.onAdDismissedFullScreenContent() }
        if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        main.idleFor(61, TimeUnit.SECONDS)
        ShadowDialog.getLatestDialog()?.dismiss()
        InterstitialAdManager.releaseAll()
        AppOpenManager.setInterstitialShowing(false)
        AdBehavior.document.acceptSuccessfulFetch("{}")
        ConsentCenter.clearHostConsent()
        requests.clear()
    }

    @Test
    fun `destroy during preparation cancels immediately and restores the unused fill once`() {
        delayBeforeShow(60_000)
        val raw = loadReady()
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        val dialog = checkNotNull(ShadowDialog.getLatestDialog())

        controller.pause().stop().destroy()

        assertEquals(listOf(AdSkipReason.SHOW_IN_BACKGROUND), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(dialog.isShowing)
        assertFalse(AppOpenManager.isInterstitialShowing)
        assertTrue(InterstitialAdManager.isReady(PLACEMENT))
        main.idleFor(61, TimeUnit.SECONDS)
        assertEquals(0, raw.hosts.size)
        assertEquals(1, result.completed)
    }

    @Test
    fun `old cosmetic timer cannot dismiss a later presentation loading dialog`() {
        val first = newVendor()
        InterstitialEngine.show(activity, ApInterstitialAd(first), AdCallback(), true)
        main.idleFor(800, TimeUnit.MILLISECONDS)
        first.callback.onAdShowedFullScreenContent()
        first.callback.onAdDismissedFullScreenContent()
        delayBeforeShow(60_000)
        InterstitialEngine.show(activity, ApInterstitialAd(newVendor()), AdCallback(), false)
        val secondDialog = checkNotNull(ShadowDialog.getLatestDialog())

        main.idleFor(1_501, TimeUnit.MILLISECONDS)

        assertTrue("Only the second attempt owns this loading window", secondDialog.isShowing)
    }

    @Test
    fun `dismiss callback may start another presentation without old cleanup hiding it`() {
        val first = newVendor()
        val second = newVendor()
        var secondDialog: android.app.Dialog? = null
        InterstitialEngine.show(activity, ApInterstitialAd(first), object : AdCallback() {
            override fun onAdClosed() {
                delayBeforeShow(60_000)
                InterstitialEngine.show(activity, ApInterstitialAd(second), AdCallback(), false)
                secondDialog = ShadowDialog.getLatestDialog()
            }
        }, false)
        main.idleFor(800, TimeUnit.MILLISECONDS)
        first.callback.onAdShowedFullScreenContent()

        first.callback.onAdDismissedFullScreenContent()

        assertTrue(checkNotNull(secondDialog).isShowing)
    }

    @Test
    fun `destroy after vendor dispatch only removes loading UI until vendor really dismisses`() {
        val raw = loadReady()
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        main.idleFor(800, TimeUnit.MILLISECONDS)
        raw.callback.onAdShowedFullScreenContent()
        val dialog = checkNotNull(ShadowDialog.getLatestDialog())

        controller.pause().stop().destroy()

        assertFalse(dialog.isShowing)
        assertTrue(AppOpenManager.isInterstitialShowing)
        assertEquals(0, result.completed)
        assertTrue(result.skipped.isEmpty())
        raw.callback.onAdDismissedFullScreenContent()
        assertFalse(AppOpenManager.isInterstitialShowing)
        assertEquals(1, result.completed)
    }

    @Test
    fun `synchronous vendor show exception cleans preparation and completes once`() {
        val raw = loadReady().apply { beforeShow = { throw IllegalStateException("Vendor show rejected") } }
        val result = RecordingShow()
        InterstitialAdManager.show(activity, PLACEMENT, result)
        val dialog = checkNotNull(ShadowDialog.getLatestDialog())

        main.idleFor(800, TimeUnit.MILLISECONDS)

        assertEquals(listOf(AdSkipReason.FAILED_TO_SHOW), result.skipped)
        assertEquals(1, result.completed)
        assertFalse(AppOpenManager.isInterstitialShowing)
        assertFalse(dialog.isShowing)
        assertFalse(InterstitialAdManager.isReady(PLACEMENT))
        raw.callback.onAdDismissedFullScreenContent()
        assertEquals(1, result.completed)
        assertEquals(0, result.closed)
    }

    private fun loadReady(): Int02VendorAd {
        val raw = newVendor()
        InterstitialAdManager.load(activity, PLACEMENT, listOf(UNIT))
        requests.last().onAdLoaded(raw)
        return raw
    }

    private fun newVendor() = Int02VendorAd(UNIT).also { vendorAds += it }

    private fun delayBeforeShow(delayMs: Long) {
        AdBehavior.document.acceptSuccessfulFetch(
            """{"interstitial":{"presentation":{"pre_show_delay_ms":$delayMs}}}""",
        )
    }

    private class RecordingShow : InterShowCallback() {
        val skipped = mutableListOf<AdSkipReason>()
        var completed = 0
        var closed = 0
        override fun onSkipped(reason: AdSkipReason) { skipped += reason }
        override fun onComplete() { completed++ }
        override fun onClosed() { closed++ }
    }

    private companion object {
        const val PLACEMENT = "interstitial-loading-owner"
        const val UNIT = "interstitial-loading-owner-unit"
    }
}
