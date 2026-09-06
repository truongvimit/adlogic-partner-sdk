package com.ads.module.helper.interstitial

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.Admob
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.TrackingAdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.rewarded.OnAdMetadataChangedListener
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.TimeUnit

/** Actual SDK show paths and Tracker sink; only external GMA dispatch/ad objects are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02InterstitialShadow::class, Int02MobileAdsShadow::class,
        Int02FacebookShadow::class, Tel02RewardedShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class FullscreenShownTelemetryTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val sink = RecordingSink()
    private val interstitials = mutableListOf<Int02VendorAd>()
    private val rewards = mutableListOf<Tel02RewardState>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Int02InterstitialShadow.requests.clear()
        Tel02RewardedShadow.requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            setFacebookClientToken("tel02-test-client-token")
        })
        ERainAd.getInstance().setIntervalInterstitialAd(0)
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        ERainAd.getInstance().setCountClickToShowAds(1, 0)
        ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        Tracker.install(app)
        Tracker.setConsent(true, true)
        Tracker.addSink(sink)
        AdTracking.registerPlacement(UNIT, PLACEMENT)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
        sink.events.clear()
    }

    @After
    fun tearDown() {
        // Let already-scheduled preparation finish while the real host is alive, then deliver
        // vendor terminals. No reflection or resets of SDK caches, callbacks or lifecycle statics.
        mainLooper.idleFor(900, TimeUnit.MILLISECONDS)
        interstitials.filter { it.hosts.isNotEmpty() }.forEach {
            it.callback.onAdDismissedFullScreenContent()
        }
        rewards.filter { it.hosts.isNotEmpty() }.forEach {
            it.callback?.onAdDismissedFullScreenContent()
        }
        ShadowDialog.getLatestDialog()?.dismiss()
        Tracker.removeSink(sink)
        AppOpenManager.getInstance().setInterstitialShowing(false)
        ConsentCenter.setHostConsent(false, false)
        if (::controller.isInitialized) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        }
        Int02InterstitialShadow.requests.clear()
        Tel02RewardedShadow.requests.clear()
    }

    @Test
    fun `tracking navigation marker forwards without claiming display`() {
        val delegate = RecordingAdCallback()
        val tracking = TrackingAdCallback(PLACEMENT, AdFormat.INTERSTITIAL, UNIT, delegate)
        tracking.onInterstitialShow()
        assertEquals(1, delegate.committed)
        assertEquals(0, count("ad_show"))
        tracking.onAdImpression()
        tracking.onAdImpression()
        assertEquals(1, count("ad_show"))
    }

    @Test
    fun `ERain UnderAd navigation stays before vendor show but display waits for real callback`() {
        val raw = interstitial()
        val wrapper = loadThroughERain(raw)
        val callback = RecordingAdCallback()
        raw.beforeShow = { assertEquals("UnderAd next stays before vendor show", 1, callback.next) }
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, callback, false, true)
        assertEquals(1, callback.committed)
        assertEquals(0, count("ad_show"))
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf(activity), raw.hosts)
        assertEquals(0, count("ad_show"))
        raw.callback.onAdShowedFullScreenContent()
        assertEquals(1, callback.impressions)
        assertEquals(1, count("ad_show"))
        assertEquals("interstitial", shown().second["ad_format"])
        assertEquals(PLACEMENT, shown().second["placement"])
        raw.callback.onAdShowedFullScreenContent()
        assertEquals(1, count("ad_show"))
    }

    @Test
    fun `Home during preparation emits failure without display and INT02 retains the raw fill`() {
        val raw = interstitial()
        val wrapper = loadThroughERain(raw)
        val callback = RecordingAdCallback()
        ERainAd.getInstance().forceShowInterstitial(activity, wrapper, callback, false, false)
        assertEquals(1, callback.committed)
        controller.pause().stop()
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(0, raw.hosts.size)
        assertEquals(0, count("ad_show"))
        assertEquals(1, count("ad_show_failed"))
        assertEquals(1, callback.failures.size)
        assertTrue(Admob.isShowInBackgroundError(callback.failures.single()))
        assertSame(raw, wrapper.interstitialAd)
    }

    @Test
    fun `real interstitial vendor failure after show is not a display`() {
        val raw = interstitial()
        val callback = RecordingAdCallback()
        ERainAd.getInstance().forceShowInterstitial(activity, loadThroughERain(raw), callback, false, false)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, raw.hosts.size)
        val error = AdError(3, "vendor could not present", "com.google.android.gms.ads")
        raw.callback.onAdFailedToShowFullScreenContent(error)
        assertEquals(0, count("ad_show"))
        assertEquals(1, count("ad_show_failed"))
        assertSame(error, callback.failures.single())
    }

    @Test
    fun `raw splash shown and impression callbacks forward one display to tracking and delegate`() {
        val raw = interstitial()
        val delegate = RecordingAdCallback()
        Admob.getInstance().onShowSplash(activity,
            TrackingAdCallback(PLACEMENT, AdFormat.INTERSTITIAL, UNIT, delegate), raw)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, raw.hosts.size)
        assertEquals(0, count("ad_show"))
        assertEquals(0, delegate.impressions)
        raw.callback.onAdShowedFullScreenContent()
        assertEquals(1, delegate.impressions)
        assertEquals(1, count("ad_show"))
        raw.callback.onAdImpression()
        raw.callback.onAdShowedFullScreenContent()
        raw.callback.onAdImpression()
        assertEquals(1, delegate.impressions)
        assertEquals(1, count("ad_show"))
    }

    @Test
    fun `delayed raw priority checker forwards actual display through its callback wrapper`() {
        val first = interstitial()
        val normal = interstitial()
        Admob.getInstance().loadInterSplashPriority4SameTime(activity,
            UNIT, "unused-tier2", "unused-tier3", UNIT, 0, 0, AdCallback())
        val requests = Int02InterstitialShadow.requests.toList()
        assertEquals(4, requests.size)
        requests[0].onAdLoaded(first)
        requests[1].onAdFailedToLoad(LoadAdError(3, "no fill", "com.google.android.gms.ads", null, null))
        requests[2].onAdFailedToLoad(LoadAdError(3, "no fill", "com.google.android.gms.ads", null, null))
        requests[3].onAdLoaded(normal)
        mainLooper.idle()
        // Consume High1 through public API; Normal remains buffered for the delayed retry API.
        Admob.getInstance().onShowSplashPriority4(activity, AdCallback())
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, first.hosts.size)
        first.callback.onAdShowedFullScreenContent()
        first.callback.onAdDismissedFullScreenContent()
        interstitials.remove(first)
        val delegate = RecordingAdCallback()
        Admob.getInstance().onCheckShowSplashPriority4WhenFail(activity,
            TrackingAdCallback(PLACEMENT, AdFormat.INTERSTITIAL, UNIT, delegate), 20)
        // Dialog construction may advance the external test clock inside the scheduled task.
        mainLooper.idleFor(20, TimeUnit.MILLISECONDS)
        mainLooper.idleFor(800, TimeUnit.MILLISECONDS)
        assertEquals(1, normal.hosts.size)
        assertEquals(0, count("ad_show"))
        normal.callback.onAdShowedFullScreenContent()
        normal.callback.onAdImpression()
        assertEquals(1, delegate.impressions)
        assertEquals(1, count("ad_show"))
    }

    @Test
    fun `buffered reward reports actual display once with captured format and unit`() {
        val raw = Tel02RewardedAd(UNIT).also { rewards += it.state }
        ERainAd.getInstance().initRewardAds(activity, UNIT, AdCallback())
        Tel02RewardedShadow.requests.single().onAdLoaded(raw)
        ERainAd.getInstance().showRewardAds(activity, RecordingRewardCallback())
        assertRewardDisplay(raw.state, "rewarded")
    }

    @Test
    fun `supplied reward reports actual display once without changing earned or close timing`() {
        val raw = Tel02RewardedAd(UNIT).also { rewards += it.state }
        val callback = RecordingRewardCallback()
        ERainAd.getInstance().showRewardAds(activity, raw, callback)
        assertEquals(0, callback.earned)
        assertEquals(0, callback.closed)
        assertRewardDisplay(raw.state, "rewarded")
        raw.state.earnedListener!!.onUserEarnedReward(raw.getRewardItem())
        assertEquals(1, callback.earned)
        assertEquals(0, callback.closed)
        raw.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, callback.closed)
        rewards.remove(raw.state) // Terminal already delivered; avoid a second cleanup callback.
    }

    @Test
    fun `rewarded interstitial reports its actual display with its own format`() {
        val raw = Tel02RewardedInterstitialAd(UNIT).also { rewards += it.state }
        ERainAd.getInstance().showRewardInterstitial(activity, raw, RecordingRewardCallback())
        assertRewardDisplay(raw.state, "rewarded_interstitial")
    }

    @Test
    fun `actual reward vendor failure emits only show failure and preserves legacy error code`() {
        val raw = Tel02RewardedInterstitialAd(UNIT).also { rewards += it.state }
        val callback = RecordingRewardCallback()
        ERainAd.getInstance().showRewardInterstitial(activity, raw, callback)
        val error = AdError(3, "vendor could not present", "com.google.android.gms.ads")
        raw.state.callback!!.onAdFailedToShowFullScreenContent(error)
        assertEquals(listOf(3), callback.failures)
        assertEquals(0, count("ad_show"))
        assertEquals(1, count("ad_show_failed"))
        assertEquals("rewarded_interstitial", sink.events.single { it.first == "ad_show_failed" }.second["ad_format"])
        rewards.remove(raw.state)
    }

    private fun assertRewardDisplay(state: Tel02RewardState, format: String) {
        assertEquals(listOf(activity), state.hosts)
        assertEquals(0, count("ad_show"))
        state.callback!!.onAdShowedFullScreenContent()
        assertEquals(1, count("ad_show"))
        assertEquals(format, shown().second["ad_format"])
        assertEquals(UNIT, shown().second["ad_unit_id"])
        assertEquals(PLACEMENT, shown().second["placement"])
        state.callback!!.onAdShowedFullScreenContent()
        assertEquals(1, count("ad_show"))
    }

    private fun interstitial() = Int02VendorAd(UNIT).also { interstitials += it }

    private fun loadThroughERain(raw: Int02VendorAd): ApInterstitialAd {
        var loaded: ApInterstitialAd? = null
        ERainAd.getInstance().getInterstitialAds(activity, UNIT, object : AdCallback() {
            override fun onApInterstitialLoad(ad: ApInterstitialAd?) { loaded = ad }
        })
        Int02InterstitialShadow.requests.last().onAdLoaded(raw)
        return requireNotNull(loaded)
    }

    private fun count(name: String) = sink.events.count { it.first == name }
    private fun shown() = sink.events.single { it.first == "ad_show" }

    private class RecordingSink : TrackSink {
        override val id = "tel02-public-regression"
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params.toMap() }
    }

    private class RecordingAdCallback : AdCallback() {
        var committed = 0
        var next = 0
        var impressions = 0
        val failures = mutableListOf<AdError?>()
        override fun onInterstitialShow() { committed++ }
        override fun onNextAction() { next++ }
        override fun onAdImpression() { impressions++ }
        override fun onAdFailedToShow(error: AdError?) { failures += error }
    }

    private class RecordingRewardCallback : RewardCallback {
        var earned = 0
        var closed = 0
        val failures = mutableListOf<Int>()
        override fun onUserEarnedReward(item: RewardItem?) { earned++ }
        override fun onRewardedAdClosed() { closed++ }
        override fun onRewardedAdFailedToShow(codeError: Int) { failures += codeError }
        override fun onAdClicked() = Unit
    }

    companion object {
        private const val UNIT = "tel02-unit"
        private const val PLACEMENT = "tel02-placement"
    }
}

/** State belongs to fake external GMA objects, never to an SDK manager or wrapper. */
class Tel02RewardState {
    val hosts = mutableListOf<Activity>()
    var callback: FullScreenContentCallback? = null
    var paid: OnPaidEventListener? = null
    var metadata: OnAdMetadataChangedListener? = null
    var earnedListener: OnUserEarnedRewardListener? = null
    var placement = 0L
    val item = object : RewardItem {
        override fun getType(): String = "test"
        override fun getAmount(): Int = 1
    }
    fun show(activity: Activity, listener: OnUserEarnedRewardListener) {
        hosts += activity
        earnedListener = listener
    }
}

class Tel02RewardedAd(private val unit: String) : RewardedAd() {
    val state = Tel02RewardState()
    override fun getAdUnitId(): String = unit
    override fun show(activity: Activity, listener: OnUserEarnedRewardListener) = state.show(activity, listener)
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { state.callback = callback }
    override fun getFullScreenContentCallback(): FullScreenContentCallback = requireNotNull(state.callback)
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { state.paid = listener }
    override fun getOnPaidEventListener(): OnPaidEventListener = requireNotNull(state.paid)
    override fun setOnAdMetadataChangedListener(listener: OnAdMetadataChangedListener?) { state.metadata = listener }
    override fun getOnAdMetadataChangedListener(): OnAdMetadataChangedListener = requireNotNull(state.metadata)
    override fun setServerSideVerificationOptions(options: ServerSideVerificationOptions?) = Unit
    override fun setImmersiveMode(enabled: Boolean) = Unit
    override fun getAdMetadata(): Bundle = Bundle()
    override fun getRewardItem(): RewardItem = state.item
    override fun getResponseInfo(): ResponseInfo = throw UnsupportedOperationException("No paid event in fixture")
    override fun getPlacementId(): Long = state.placement
    override fun setPlacementId(value: Long) { state.placement = value }
}

class Tel02RewardedInterstitialAd(private val unit: String) : RewardedInterstitialAd() {
    val state = Tel02RewardState()
    override fun getAdUnitId(): String = unit
    override fun show(activity: Activity, listener: OnUserEarnedRewardListener) = state.show(activity, listener)
    override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { state.callback = callback }
    override fun getFullScreenContentCallback(): FullScreenContentCallback = requireNotNull(state.callback)
    override fun setOnPaidEventListener(listener: OnPaidEventListener?) { state.paid = listener }
    override fun getOnPaidEventListener(): OnPaidEventListener = requireNotNull(state.paid)
    override fun setOnAdMetadataChangedListener(listener: OnAdMetadataChangedListener?) { state.metadata = listener }
    override fun getOnAdMetadataChangedListener(): OnAdMetadataChangedListener = requireNotNull(state.metadata)
    override fun setServerSideVerificationOptions(options: ServerSideVerificationOptions) = Unit
    override fun setImmersiveMode(enabled: Boolean) = Unit
    override fun getAdMetadata(): Bundle = Bundle()
    override fun getRewardItem(): RewardItem = state.item
    override fun getResponseInfo(): ResponseInfo = throw UnsupportedOperationException("No paid event in fixture")
    override fun getPlacementId(): Long = state.placement
    override fun setPlacementId(value: Long) { state.placement = value }
}

@Implements(value = RewardedAd::class, isInAndroidSdk = false)
class Tel02RewardedShadow {
    companion object {
        val requests = mutableListOf<RewardedAdLoadCallback>()
        @JvmStatic @Implementation
        fun load(context: Context, adUnitId: String, request: AdRequest, callback: RewardedAdLoadCallback) {
            requests += callback
        }
    }
}
