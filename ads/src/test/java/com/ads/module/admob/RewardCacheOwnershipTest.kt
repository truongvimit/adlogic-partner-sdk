package com.ads.module.admob

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Tel02RewardedAd
import com.ads.module.helper.interstitial.Tel02RewardedShadow
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Public reward APIs with real SDK ownership and controlled external GMA callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, Tel02RewardedShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RewardCacheOwnershipTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val requests get() = Tel02RewardedShadow.requests
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        requests.clear()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = false
        })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            setFacebookClientToken("reward-ownership-test")
        })
        ERainAd.getInstance().setMaxClickAdsPerDay(0)
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        main.idle()
        clearLegacyBuffer()
    }

    @After
    fun tearDown() {
        AppOpenManager.getInstance().setInterstitialShowing(false)
        clearLegacyBuffer()
        ConsentCenter.setHostConsent(false, false)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        controller.destroy()
        main.idleFor(800, TimeUnit.MILLISECONDS)
        requests.clear()
    }

    @Test
    fun `cache only show with no ad fails without starting a request`() {
        val callback = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, null, callback, false)
        main.idleFor(5, TimeUnit.MINUTES)

        assertEquals(listOf("failed:0"), callback.events)
        assertEquals(0, requests.size)
    }

    @Test
    fun `immediate show from load callback sees paid setup and consumes the legacy alias`() {
        val ad = Tel02RewardedAd(UNIT)
        val callback = RecordingReward()
        var loaded = 0
        ERainAd.getInstance().initRewardAds(activity, UNIT, object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd) {
                loaded++
                assertSame(ad, rewardedAd)
                assertSame(ad, Admob.getInstance().rewardedAd)
                assertNotNull("Paid listener must be ready before partner code can show", ad.state.paid)
                ERainAd.getInstance().showRewardAds(activity, rewardedAd, callback, false)
                assertNull(Admob.getInstance().rewardedAd)
            }
        })

        requests.single().onAdLoaded(ad)

        assertEquals(1, loaded)
        assertEquals(listOf(activity), ad.state.hosts)
        assertNull("Returning from load callback cannot put the consumed ad back", Admob.getInstance().rewardedAd)
        ad.state.callback!!.onAdShowedFullScreenContent()
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(listOf("shown", "closed"), callback.events)
        assertEquals("Showing and closing must not refill", 1, requests.size)
        assertNull(Admob.getInstance().rewardedAd)
    }

    @Test
    fun `existing supplied ad overload retains refill when the vendor reports shown`() {
        val ad = load(Tel02RewardedAd(UNIT))

        ERainAd.getInstance().showRewardAds(activity, ad, RecordingReward())
        assertEquals(1, requests.size)
        ad.state.callback!!.onAdShowedFullScreenContent()

        assertEquals(2, requests.size)
    }

    @Test
    fun `existing null supplied ad overload retains its request and failure callback`() {
        val callback = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, null, callback)

        assertEquals(1, requests.size)
        assertEquals(listOf("failed:0"), callback.events)
    }

    @Test
    fun `presentation callbacks only follow actual vendor events even after a long wait`() {
        val ad = load(Tel02RewardedAd(UNIT))
        val callback = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, ad, callback, false)

        main.idleFor(5, TimeUnit.MINUTES)
        assertEquals(emptyList<String>(), callback.events)
        ad.state.callback!!.onAdShowedFullScreenContent()
        assertEquals(listOf("shown"), callback.events)
        ad.state.callback!!.onAdImpression()
        ad.state.callback!!.onAdClicked()
        ad.state.earnedListener!!.onUserEarnedReward(ad.rewardItem)
        assertEquals(listOf("shown", "impression", "clicked", "earned"), callback.events)
        assertSame(ad.rewardItem, callback.items.single())

        main.idleFor(5, TimeUnit.MINUTES)
        assertEquals("Elapsed time must not invent a close or failure", 4, callback.events.size)
        ad.state.callback!!.onAdDismissedFullScreenContent()

        assertEquals(listOf("shown", "impression", "clicked", "earned", "closed"), callback.events)
        assertEquals(1, requests.size)
    }

    @Test
    fun `failed cache only presentation stays consumed and forwards the vendor error`() {
        val ad = load(Tel02RewardedAd(UNIT))
        val callback = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, ad, callback, false)

        ad.state.callback!!.onAdFailedToShowFullScreenContent(AdError(7, "Cannot present", "test.gma"))
        ad.state.callback!!.onAdFailedToShowFullScreenContent(AdError(7, "Duplicate", "test.gma"))
        ad.state.callback!!.onAdDismissedFullScreenContent()
        main.idleFor(5, TimeUnit.MINUTES)

        assertEquals(listOf("failed:7"), callback.events)
        assertNull(Admob.getInstance().rewardedAd)
        assertEquals(1, requests.size)
    }

    @Test
    fun `showing one ad does not consume a different ad in the legacy buffer`() {
        val first = load(Tel02RewardedAd(UNIT))
        val second = load(Tel02RewardedAd("reward-other-unit"))

        ERainAd.getInstance().showRewardAds(activity, first, RecordingReward(), false)
        first.state.callback!!.onAdShowedFullScreenContent()

        assertSame(second, Admob.getInstance().rewardedAd)
        assertEquals(2, requests.size)
    }

    @Test
    fun `close callback can show the next ad without old cleanup clearing its presentation flag`() {
        val first = load(Tel02RewardedAd(UNIT))
        val second = load(Tel02RewardedAd("reward-other-unit"))
        val firstEvents = RecordingReward()
        val secondEvents = RecordingReward()
        val firstCallback = object : RewardCallback by firstEvents {
            // Kotlin delegation keeps Java default methods unless explicitly overridden.
            override fun onRewardedAdShown() = firstEvents.onRewardedAdShown()

            override fun onRewardedAdClosed() {
                firstEvents.onRewardedAdClosed()
                assertFalse("Old presentation must be cleared before partner callback", AppOpenManager.getInstance().isInterstitialShowing)
                ERainAd.getInstance().showRewardAds(activity, second, secondEvents, false)
                second.state.callback!!.onAdShowedFullScreenContent()
            }
        }
        ERainAd.getInstance().showRewardAds(activity, first, firstCallback, false)
        first.state.callback!!.onAdShowedFullScreenContent()

        first.state.callback!!.onAdDismissedFullScreenContent()

        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(listOf("shown", "closed"), firstEvents.events)
        assertEquals(listOf("shown"), secondEvents.events)
        first.state.callback!!.onAdDismissedFullScreenContent()
        first.state.callback!!.onAdFailedToShowFullScreenContent(AdError(7, "Late terminal", "test.gma"))
        assertTrue("Late first-ad terminal cannot clear the second presentation", AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(listOf("shown", "closed"), firstEvents.events)

        second.state.callback!!.onAdDismissedFullScreenContent()
        assertFalse(AppOpenManager.getInstance().isInterstitialShowing)
        assertEquals(listOf("shown", "closed"), secondEvents.events)
    }

    @Test
    fun `a real earned callback arriving after close is still forwarded`() {
        val ad = load(Tel02RewardedAd(UNIT))
        val callback = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, ad, callback, false)
        ad.state.callback!!.onAdShowedFullScreenContent()
        ad.state.callback!!.onAdDismissedFullScreenContent()

        main.idleFor(5, TimeUnit.MINUTES)
        assertEquals(listOf("shown", "closed"), callback.events)
        ad.state.earnedListener!!.onUserEarnedReward(ad.rewardItem)

        assertEquals(listOf("shown", "closed", "earned"), callback.events)
        assertSame(ad.rewardItem, callback.items.single())
        assertEquals(1, requests.size)
    }

    @Test
    fun `paid callback reads the loaded ad after the legacy buffer changes`() {
        val first = mock(RewardedAd::class.java)
        val firstResponse = mock(ResponseInfo::class.java)
        `when`(first.adUnitId).thenReturn(UNIT)
        `when`(first.responseInfo).thenReturn(firstResponse)
        `when`(firstResponse.mediationAdapterClassName).thenReturn("test.first.Adapter")
        ERainAd.getInstance().initRewardAds(activity, UNIT, AdCallback())
        requests.last().onAdLoaded(first)
        val paid = ArgumentCaptor.forClass(OnPaidEventListener::class.java)
        verify(first).setOnPaidEventListener(paid.capture())

        val second = mock(RewardedAd::class.java)
        `when`(second.adUnitId).thenReturn("reward-other-unit")
        ERainAd.getInstance().initRewardAds(activity, "reward-other-unit", AdCallback())
        requests.last().onAdLoaded(second)
        val value = mock(AdValue::class.java)
        `when`(value.currencyCode).thenReturn("USD")
        `when`(value.valueMicros).thenReturn(100L)
        paid.value.onPaidEvent(value)

        verify(first).responseInfo
        verify(second, never()).responseInfo
        assertSame(second, Admob.getInstance().rewardedAd)
    }

    private fun load(ad: Tel02RewardedAd): Tel02RewardedAd {
        ERainAd.getInstance().initRewardAds(activity, ad.adUnitId, AdCallback())
        requests.last().onAdLoaded(ad)
        return ad
    }

    /** Clear through an ordinary failed load; do not overwrite manager internals in the test. */
    private fun clearLegacyBuffer() {
        ERainAd.getInstance().initRewardAds(activity, UNIT, AdCallback())
        requests.last().onAdFailedToLoad(LoadAdError(3, "Fixture cleanup", "test.gma", null, null))
        requests.clear()
    }

    private class RecordingReward : RewardCallback {
        val events = mutableListOf<String>()
        val items = mutableListOf<RewardItem?>()
        override fun onRewardedAdShown() { events += "shown" }
        override fun onAdImpression() { events += "impression" }
        override fun onUserEarnedReward(item: RewardItem?) {
            items += item
            events += "earned"
        }
        override fun onRewardedAdClosed() { events += "closed" }
        override fun onRewardedAdFailedToShow(codeError: Int) { events += "failed:$codeError" }
        override fun onAdClicked() { events += "clicked" }
    }

    private companion object {
        const val UNIT = "reward-ownership-unit"
    }
}
