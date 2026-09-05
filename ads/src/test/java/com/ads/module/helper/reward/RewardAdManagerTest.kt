package com.ads.module.helper.reward

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.ArgumentCaptor
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the real helper, waterfall and adapter; only the external GMA boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RewardAdManagerTest {
    private lateinit var activity: Activity
    private lateinit var vendor: MockedStatic<RewardedAd>
    private val requests = mutableListOf<RewardedAdLoadCallback>()
    private var premium = false

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RewardAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean = premium
        })
        vendor = Mockito.mockStatic(RewardedAd::class.java) { invocation ->
            if (invocation.method.name == "load") {
                requests += invocation.getArgument<RewardedAdLoadCallback>(3)
            }
            null
        }
    }

    @After
    fun tearDown() {
        RewardAdManager.releaseAll()
        vendor.close()
        activity.finish()
    }

    @Test
    fun `revoked consent prevents showing an already loaded reward`() {
        RewardAdManager.load(activity, "reward", listOf("unit"))
        val ad = Mockito.mock(RewardedAd::class.java)
        requests.single().onAdLoaded(ad)
        ConsentCenter.setHostConsent(false, false)
        var failures = 0

        RewardAdManager.show(activity, "reward", object : RewardShowCallback() {
            override fun onFailedToShow(codeError: Int) { failures++ }
        })

        assertEquals(1, failures)
        Mockito.verify(ad, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
    }

    @Test
    fun `releasing load and show terminates once and ignores its late fill`() {
        var successes = 0
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable { successes++ }, onFailed = Runnable { failures++ })

        RewardAdManager.release("reward")

        assertEquals(1, failures)
        val ad = Mockito.mock(RewardedAd::class.java)
        requests.single().onAdLoaded(ad)
        assertEquals(0, successes)
        assertEquals(1, failures)
        Mockito.verify(ad, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
    }

    @Test
    fun `released cache load cannot replace a newer load with its late fill`() {
        RewardAdManager.load(activity, "reward", listOf("old-unit"))
        RewardAdManager.release("reward")
        RewardAdManager.load(activity, "reward", listOf("new-unit"))

        requests[0].onAdLoaded(Mockito.mock(RewardedAd::class.java))

        assertFalse(RewardAdManager.isReady("reward"))
        requests[1].onAdLoaded(Mockito.mock(RewardedAd::class.java))
        assertTrue(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `premium release during loading grants once without presenting the late fill`() {
        var successes = 0
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable { successes++ }, onFailed = Runnable { failures++ })
        premium = true

        RewardAdManager.releaseAll()

        assertEquals(1, successes)
        assertEquals(0, failures)
        val ad = Mockito.mock(RewardedAd::class.java)
        requests.single().onAdLoaded(ad)
        assertEquals(1, successes)
        Mockito.verify(ad, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
    }

    @Test
    fun `premium release after presentation waits for real earning and dismissal`() {
        var successes = 0
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable { successes++ }, onFailed = Runnable { failures++ })
        val ad = Mockito.mock(RewardedAd::class.java)
        requests.single().onAdLoaded(ad)
        val fullscreen = ArgumentCaptor.forClass(FullScreenContentCallback::class.java)
        val reward = ArgumentCaptor.forClass(OnUserEarnedRewardListener::class.java)
        Mockito.verify(ad).setFullScreenContentCallback(fullscreen.capture())
        Mockito.verify(ad).show(Mockito.eq(activity), reward.capture())
        premium = true

        RewardAdManager.releaseAll()

        assertEquals(0, successes)
        assertEquals(0, failures)
        reward.value.onUserEarnedReward(Mockito.mock(RewardItem::class.java))
        fullscreen.value.onAdDismissedFullScreenContent()
        fullscreen.value.onAdDismissedFullScreenContent()
        assertEquals(1, successes)
        assertEquals(0, failures)
    }

    @Test
    fun `revoked authority is checked before reusing a cached reward`() {
        RewardAdManager.load(activity, "reward", listOf("unit"))
        requests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))
        ConsentCenter.setHostConsent(false, false)
        val listener = RecordingListener()

        RewardAdManager.load(activity, "reward", listOf("unit"), listener = listener)

        assertEquals(0, listener.loaded)
        assertEquals(1, listener.failed)
        assertFalse(RewardAdManager.isReady("reward"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `first premium request still notifies the incoming listener`() {
        premium = true
        val listener = RecordingListener()

        RewardAdManager.load(activity, "reward", listOf("unit"), listener = listener)

        assertEquals(1, listener.failed)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `late unauthorized reward fill fails once instead of entering the cache`() {
        val listener = RecordingListener()
        RewardAdManager.load(activity, "reward", listOf("unit"), listener = listener)
        ConsentCenter.setHostConsent(false, false)

        requests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))

        assertEquals(0, listener.loaded)
        assertEquals(1, listener.failed)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `changed personalization rejects an old reward fill even while requests remain authorized`() {
        ConsentCenter.setHostConsent(true, true)
        val listener = RecordingListener()
        RewardAdManager.load(activity, "reward", listOf("unit"), listener = listener)
        ConsentCenter.setHostConsent(true, false)

        requests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))

        assertEquals(0, listener.loaded)
        assertEquals(1, listener.failed)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `changed personalization rejects load and show without presenting the old request`() {
        ConsentCenter.setHostConsent(true, true)
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable {}, onFailed = Runnable { failures++ })
        ConsentCenter.setHostConsent(true, false)
        val ad = Mockito.mock(RewardedAd::class.java)

        requests.single().onAdLoaded(ad)

        assertEquals(1, failures)
        Mockito.verify(ad, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
    }

    @Test
    fun `offline status alone does not block reward load or cached presentation`() {
        val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, null)
        RewardAdManager.load(activity, "reward", listOf("unit"))
        val ad = Mockito.mock(RewardedAd::class.java)
        requests.single().onAdLoaded(ad)
        assertTrue(RewardAdManager.isReady("reward"))

        RewardAdManager.show(activity, "reward", RewardShowCallback())

        Mockito.verify(ad).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener::class.java))
    }

    @Test
    fun `premium buffered presentation still earns and closes after global invalidation`() {
        RewardAdManager.load(activity, "reward", listOf("unit"))
        requests.single().onAdLoaded(Mockito.mock(RewardedAd::class.java))
        premium = true
        RewardAdManager.releaseAll()
        val events = mutableListOf<String>()

        RewardAdManager.show(activity, "reward", object : RewardShowCallback() {
            override fun onEarned(item: RewardItem?) { events += "earned" }
            override fun onClosed(earned: Boolean) { events += "closed:$earned" }
        })

        assertEquals(listOf("earned", "closed:true"), events)
    }

    private class RecordingListener : AdCallback() {
        var loaded = 0
        var failed = 0
        override fun onRewardAdLoaded(rewardedAd: RewardedAd?) { loaded++ }
        override fun onAdFailedToLoad(adError: LoadAdError?) { failed++ }
    }
}
