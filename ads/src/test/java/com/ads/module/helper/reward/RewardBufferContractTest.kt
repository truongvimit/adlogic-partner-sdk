package com.ads.module.helper.reward

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Tel02RewardedAd
import com.google.android.gms.ads.AdError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, RewardFlowLoadShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RewardBufferContractTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val requests get() = RewardFlowLoadShadow.requests
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val vendorAds = mutableListOf<Tel02RewardedAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RewardAdManager.releaseAll()
        RewardAdManager.bufferAfterClose = false
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        requests.clear()
        RewardFlowLoadShadow.requestedUnits.clear()
        RewardFlowLoadShadow.failure = null
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            facebookClientToken = "reward-buffer-contract-client-token"
        })
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        AdRemoteConfig.initializeFromJson("""{"$PLACEMENT":{"id":"$UNIT","isEnable":true}}""")
    }

    @After
    fun tearDown() {
        vendorAds.filter { it.state.hosts.isNotEmpty() }.forEach {
            it.state.callback?.onAdDismissedFullScreenContent()
        }
        RewardAdManager.releaseAll()
        RewardAdManager.bufferAfterClose = false
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        ConsentCenter.setHostConsent(false, false)
        controller.pause().stop().destroy()
        mainLooper.idleFor(31, TimeUnit.SECONDS)
        AppOpenManager.getInstance().setInterstitialShowing(false)
        requests.clear()
        RewardFlowLoadShadow.requestedUnits.clear()
        RewardFlowLoadShadow.failure = null
    }

    @Test
    fun `declared placement with the flag off on host and remote loads nothing after close`() {
        AdBehavior.document.acceptSuccessfulFetch(REMOTE_OFF)
        val ad = loadAndFill()
        val show = showFilled(ad)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf(false), show.closed)
        assertEquals("Buffering off must leave the closed placement unloaded", 1, requests.size)
    }

    @Test
    fun `remote flag off outranks a host flag left on for a declared placement`() {
        RewardAdManager.bufferAfterClose = true
        AdBehavior.document.acceptSuccessfulFetch(REMOTE_OFF)
        val ad = loadAndFill()
        val show = showFilled(ad)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        mainLooper.idle()
        assertEquals(listOf(false), show.closed)
        assertEquals("Remote off must override host on and skip the buffer load", 1, requests.size)
    }

    @Test
    fun `failed show never buffers even when a declared placement opted in`() {
        RewardAdManager.bufferAfterClose = true
        val ad = loadAndFill()
        val show = showFilled(ad)
        ad.state.callback!!.onAdFailedToShowFullScreenContent(AdError(1, "show failed", "test"))
        mainLooper.idle()
        assertEquals(listOf(1), show.failures)
        assertEquals("Only a close may buffer the next ad, never a failed show", 1, requests.size)
    }

    private fun loadAndFill(): Tel02RewardedAd {
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT))
        val ad = Tel02RewardedAd(UNIT).also { vendorAds += it }
        requests.last().onAdLoaded(ad)
        assertEquals(1, requests.size)
        return ad
    }

    private fun showFilled(ad: Tel02RewardedAd): RecordingShow {
        val show = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, show)
        assertEquals(listOf(activity), ad.state.hosts)
        return show
    }

    private class RecordingShow : RewardShowCallback() {
        val failures = mutableListOf<Int>()
        val closed = mutableListOf<Boolean>()
        override fun onClosed(earned: Boolean) { closed += earned }
        override fun onFailedToShow(codeError: Int) { failures += codeError }
    }

    companion object {
        private const val PLACEMENT = "reward-buffer-contract"
        private const val UNIT = "reward-buffer-contract-unit"
        private const val REMOTE_OFF = """{"rewarded":{"buffer":{"after_close":false}}}"""
    }
}
