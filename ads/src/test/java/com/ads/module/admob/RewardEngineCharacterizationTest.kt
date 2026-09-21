package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Tel02RewardState
import com.ads.module.helper.interstitial.Tel02RewardedAd
import com.ads.module.helper.interstitial.Tel02RewardedShadow
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, Tel02RewardedShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RewardEngineCharacterizationTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val requests get() = Tel02RewardedShadow.requests
    private val shown = mutableListOf<Tel02RewardState>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AdBehavior.document.acceptSuccessfulFetch(null)
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            facebookClientToken = "reward-engine-characterization-token"
        })
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
    }

    @After
    fun tearDown() {
        shown.forEach { it.callback?.onAdDismissedFullScreenContent() }
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
    fun `initRewardAds attaches the paid listener before handing the fill to onRewardAdLoaded`() {
        val raw = Tel02RewardedAd(UNIT)
        var listenerAtLoad: OnPaidEventListener? = null
        var loaded: RewardedAd? = null
        Admob.getInstance().initRewardAds(activity, UNIT, object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                loaded = rewardedAd
                listenerAtLoad = (rewardedAd as Tel02RewardedAd).state.paid
            }
        })
        assertEquals("initRewardAds must reach the vendor exactly once", 1, requests.size)

        requests.single().onAdLoaded(raw)

        assertSame("onRewardAdLoaded must receive the vendor fill", raw, loaded)
        assertNotNull("paid listener must already be on the ad when onRewardAdLoaded runs", listenerAtLoad)
    }

    @Test
    fun `host onAdImpression fires only from the vendor impression callback, not from the shown callback`() {
        val raw = showSupplied()
        val callback = RecordingRewardCallback()
        Admob.getInstance().showRewardAds(activity, raw, callback)

        raw.state.callback!!.onAdShowedFullScreenContent()
        assertEquals("onAdShowedFullScreenContent must not count as a host impression", 0, callback.impressions)
        assertEquals(1, callback.shown)

        raw.state.callback!!.onAdImpression()
        assertEquals("vendor onAdImpression must reach the host exactly once", 1, callback.impressions)
    }

    @Test
    fun `showRewardAds with no ad reports failed to show with code 0`() {
        val callback = RecordingRewardCallback()

        Admob.getInstance().showRewardAds(activity, null, callback)

        assertEquals("a null ad must fail to show with code 0", listOf(0), callback.failures)
        assertEquals(0, callback.earned)
    }

    @Test
    fun `closing a shown reward never loads the next one even after the main looper drains`() {
        var loaded: RewardedAd? = null
        Admob.getInstance().initRewardAds(activity, UNIT, object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) { loaded = rewardedAd }
        })
        requests.single().onAdLoaded(Tel02RewardedAd(UNIT))
        val raw = loaded as Tel02RewardedAd
        val callback = RecordingRewardCallback()

        Admob.getInstance().showRewardAds(activity, raw, callback)
        raw.state.callback!!.onAdShowedFullScreenContent()
        raw.state.callback!!.onAdDismissedFullScreenContent()
        mainLooper.idleFor(5, TimeUnit.SECONDS)

        assertEquals(1, callback.closed)
        assertEquals("showRewardAds must not request a refill after close", 1, requests.size)
    }

    private fun showSupplied() = Tel02RewardedAd(UNIT).also { shown += it.state }

    private class RecordingRewardCallback : RewardCallback {
        var shown = 0
        var impressions = 0
        var earned = 0
        var closed = 0
        val failures = mutableListOf<Int>()
        override fun onRewardedAdShown() { shown++ }
        override fun onAdImpression() { impressions++ }
        override fun onUserEarnedReward(var1: RewardItem?) { earned++ }
        override fun onRewardedAdClosed() { closed++ }
        override fun onRewardedAdFailedToShow(codeError: Int) { failures += codeError }
        override fun onAdClicked() = Unit
    }

    companion object {
        private const val UNIT = "reward-engine-unit"
    }
}
