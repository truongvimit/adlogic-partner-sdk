package com.ads.module.helper.reward

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.ads.module.admob.Admob
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.RewardCallback
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.AdSkipReason
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import io.trackkit.AdImpression
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit

/** Real public reward adapters and cache; only the external GMA load/ad boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RewardPresentationOwnershipTest {
    private lateinit var activity: Activity
    private lateinit var vendor: MockedStatic<RewardedAd>
    private val requests = mutableListOf<Pair<String, RewardedAdLoadCallback>>()
    private val ads = mutableListOf<VendorReward>()
    private var premium = false
    private val otherFullscreen = mutableListOf<() -> FullScreenContentCallback?>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        RewardAdManager.releaseAll()
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        vendor = Mockito.mockStatic(RewardedAd::class.java) { invocation ->
            if (invocation.method.name == "load") {
                requests += invocation.getArgument<String>(1) to
                    invocation.getArgument<RewardedAdLoadCallback>(3)
            }
            null
        }
    }

    @After
    fun tearDown() {
        // Finish through the actual vendor callback contract, never by resetting the owner.
        ads.toList().forEach { it.fullscreen?.onAdDismissedFullScreenContent() }
        otherFullscreen.forEach { it()?.onAdDismissedFullScreenContent() }
        RewardAdManager.releaseAll()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        vendor.close()
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
        Tracker.resetForTesting()
    }

    @Test
    fun `busy presentation leaves the original manager reward available without another load`() {
        val first = reward("first-unit")
        ERainAd.getInstance().showRewardAds(activity, first.ad, RecordingReward())
        assertEquals(1, first.shows)
        RewardAdManager.load(activity, "reward", listOf("buffered-unit"))
        val buffered = reward("buffered-unit")
        requests.single().second.onAdLoaded(buffered.ad)
        val requestCount = requests.size
        var failures = 0

        RewardAdManager.show(activity, "reward", object : RewardShowCallback() {
            override fun onFailedToShow(codeError: Int) { failures++ }
        })

        assertEquals("A dispatched vendor ad owns fullscreen before its shown callback", 0, buffered.shows)
        assertEquals(1, failures)
        assertTrue(RewardAdManager.isReady("reward"))
        assertEquals(requestCount, requests.size)
        first.fullscreen!!.onAdDismissedFullScreenContent()

        RewardAdManager.show(activity, "reward", RewardShowCallback())

        assertEquals(1, buffered.shows)
        assertEquals(requestCount, requests.size)
    }

    @Test
    fun `callback attachment failure rejects without consuming the manager buffer`() {
        val buffered = loadManagerReward()
        buffered.onAttach = { throw IllegalStateException("callback setter failed") }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(listOf("preparation_failed"), result.rejected.map { it.key })
        assertEquals(0, buffered.shows)
        assertEquals(1, result.failed)
        assertTrue(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `reservation elapsed inside callback setup never invokes the vendor`() {
        val buffered = loadManagerReward()
        buffered.onAttach = { SystemClock.sleep(90_001) }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, buffered.shows)
        assertEquals(listOf(AdSkipReason.EXPIRED), result.rejected)
        assertEquals(1, result.failed)
        assertTrue(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `consent changed during callback setup prevents show and cache restoration`() {
        val buffered = loadManagerReward()
        buffered.onAttach = { ConsentCenter.setHostConsent(false, false) }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, buffered.shows)
        assertEquals(listOf(AdSkipReason.CONSENT_NOT_GRANTED), result.rejected)
        assertEquals(1, result.failed)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `premium acquired during callback setup keeps manager automatic earn and close`() {
        val buffered = loadManagerReward()
        buffered.onAttach = { premium = true }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, buffered.shows)
        assertEquals(1, result.earned)
        assertEquals(listOf(true), result.closed)
        assertEquals(0, result.failed)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `finishing host during callback setup does not consume a fresh reward`() {
        val buffered = loadManagerReward()
        buffered.onAttach = { activity.finish() }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, buffered.shows)
        assertEquals(listOf(AdSkipReason.INVALID_HOST), result.rejected)
        assertTrue(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `all raw reward overloads reject busy without clearing the field or requesting a refill`() {
        val first = reward("first-unit")
        ERainAd.getInstance().showRewardAds(activity, first.ad, RecordingReward())
        ERainAd.getInstance().initRewardAds(activity, "field-unit")
        val field = reward("field-unit")
        requests.single().second.onAdLoaded(field.ad)
        val direct = reward("direct-unit")
        val interstitial = Mockito.mock(RewardedInterstitialAd::class.java)
        Mockito.`when`(interstitial.adUnitId).thenReturn("reward-interstitial-unit")
        var interstitialShows = 0
        Mockito.doAnswer { interstitialShows++; null }.`when`(interstitial)
            .show(Mockito.any(Activity::class.java), Mockito.any(OnUserEarnedRewardListener::class.java))
        val fieldCallback = RecordingReward()
        val directCallback = RecordingReward()
        val interstitialCallback = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, fieldCallback)
        ERainAd.getInstance().showRewardAds(activity, direct.ad, directCallback)
        ERainAd.getInstance().showRewardInterstitial(activity, interstitial, interstitialCallback)

        assertEquals(listOf(0, 0, 0), listOf(field.shows, direct.shows, interstitialShows))
        assertEquals(listOf(1, 1, 1), listOf(fieldCallback.failed, directCallback.failed, interstitialCallback.failed))
        assertSame(field.ad, Admob.getInstance().rewardedAd)
        assertEquals(1, requests.size)
        first.fullscreen!!.onAdDismissedFullScreenContent()
        ERainAd.getInstance().showRewardAds(activity, fieldCallback)
        assertEquals(1, field.shows)
        assertEquals(1, requests.size)
    }

    @Test
    fun `reentrant close starts the next reward and stale callbacks cannot unlock it`() {
        val first = reward("first-unit")
        val second = reward("second-unit")
        val blocked = reward("blocked-unit")
        val firstCallback = object : RecordingReward() {
            override fun onRewardedAdClosed() {
                super.onRewardedAdClosed()
                ERainAd.getInstance().showRewardAds(activity, second.ad, RecordingReward())
            }
        }
        ERainAd.getInstance().showRewardAds(activity, first.ad, firstCallback)

        first.fullscreen!!.onAdDismissedFullScreenContent()
        first.fullscreen!!.onAdShowedFullScreenContent()
        first.fullscreen!!.onAdFailedToShowFullScreenContent(AdError(7, "late", "vendor"))
        first.fullscreen!!.onAdDismissedFullScreenContent()
        val blockedCallback = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, blocked.ad, blockedCallback)

        assertEquals(1, second.shows)
        assertEquals(0, blocked.shows)
        assertEquals(1, blockedCallback.failed)
        assertEquals(1, firstCallback.closed)
        assertEquals(0, firstCallback.failed)
        assertTrue("An obsolete shown callback must not trigger the explicit overload's refill", requests.isEmpty())
    }

    @Test
    fun `late duplicate earning belongs to the closed reward while the next reward stays exclusive`() {
        val first = reward("first-unit")
        val second = reward("second-unit")
        val firstCallback = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, first.ad, firstCallback)
        first.fullscreen!!.onAdDismissedFullScreenContent()
        ERainAd.getInstance().showRewardAds(activity, second.ad, RecordingReward())
        val item = Mockito.mock(RewardItem::class.java)

        first.earn!!.onUserEarnedReward(item)
        first.earn!!.onUserEarnedReward(item)
        val blocked = reward("blocked-unit")
        ERainAd.getInstance().showRewardAds(activity, blocked.ad, RecordingReward())

        assertEquals(1, firstCallback.earned)
        assertEquals(1, firstCallback.closed)
        assertEquals(1, second.shows)
        assertEquals(0, blocked.shows)
    }

    @Test
    fun `field reward click and paid callbacks retain the ad after shown clears its cache`() {
        val revenues = mutableListOf<AdImpression>()
        val clicks = mutableListOf<Map<String, Any?>>()
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "reward-ownership"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (name == "ad_click") clicks += params
            }
            override fun onAdRevenue(impression: AdImpression) { revenues += impression }
        })
        Tracker.install(activity.application, TrackerConfig(strictValidation = true, logLevel = 0))
        ERainAd.getInstance().initRewardAds(activity, "field-unit")
        val field = reward("field-unit")
        requests.single().second.onAdLoaded(field.ad)
        val result = RecordingReward()
        ERainAd.getInstance().showRewardAds(activity, result)
        field.fullscreen!!.onAdShowedFullScreenContent()

        field.fullscreen!!.onAdClicked()
        val value = Mockito.mock(AdValue::class.java)
        Mockito.`when`(value.precisionType).thenReturn(3)
        Mockito.`when`(value.currencyCode).thenReturn("USD")
        Mockito.`when`(value.valueMicros).thenReturn(17_000L)
        field.paid!!.onPaidEvent(value)

        assertEquals(1, result.clicked)
        assertEquals(listOf("field-unit"), clicks.map { it["ad_unit_id"] })
        assertEquals(1, revenues.size)
        assertEquals("field-unit", revenues.single().adUnitId)
        assertEquals("adapter-field-unit", revenues.single().network)
        assertEquals(17_000L, revenues.single().valueMicros)
    }

    @Test
    fun `synchronous vendor failure consumes the ad and lets the failure callback start another`() {
        val first = loadManagerReward()
        first.onShow = { throw IllegalStateException("vendor show failed") }
        val second = reward("second-unit")
        var failures = 0

        RewardAdManager.show(activity, "reward", object : RewardShowCallback() {
            override fun onFailedToShow(codeError: Int) {
                failures++
                ERainAd.getInstance().showRewardAds(activity, second.ad, RecordingReward())
            }
        })
        first.fullscreen!!.onAdFailedToShowFullScreenContent(AdError(7, "late", "vendor"))

        assertEquals(1, failures)
        assertEquals(1, first.shows)
        assertEquals(1, second.shows)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `release and replacement during setup prevent the old ad from showing or replacing the new cache`() {
        val first = loadManagerReward()
        val replacement = reward("replacement-unit")
        first.onAttach = {
            RewardAdManager.release("reward")
            RewardAdManager.load(activity, "reward", listOf("replacement-unit"))
            requests.last().second.onAdLoaded(replacement.ad)
        }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, first.shows)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.rejected)
        assertTrue(RewardAdManager.isReady("reward"))
        RewardAdManager.show(activity, "reward", RewardShowCallback())
        assertEquals(1, replacement.shows)
    }

    @Test
    fun `load and show released during setup completes once without vendor invocation`() {
        val ad = reward("unit")
        ad.onAttach = { RewardAdManager.release("reward") }
        var successes = 0
        var failures = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable { successes++ }, onFailed = Runnable { failures++ })

        requests.single().second.onAdLoaded(ad.ad)

        assertEquals(0, ad.shows)
        assertEquals(0, successes)
        assertEquals(1, failures)
        ad.fullscreen!!.onAdDismissedFullScreenContent()
        assertEquals(1, failures)
    }

    @Test
    fun `manager personalization changed during setup rejects the old fill without restoration`() {
        ConsentCenter.setHostConsent(true, true)
        val ad = loadManagerReward()
        ad.onAttach = { ConsentCenter.setHostConsent(true, false) }
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, ad.shows)
        assertEquals(listOf(AdSkipReason.NOT_READY), result.rejected)
        assertEquals(1, result.failed)
        assertFalse(RewardAdManager.isReady("reward"))
    }

    @Test
    fun `raw reward personalization changed during setup also prevents the old presentation`() {
        ConsentCenter.setHostConsent(true, true)
        val ad = reward("unit")
        ad.onAttach = { ConsentCenter.setHostConsent(true, false) }
        val result = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, ad.ad, result)

        assertEquals(0, ad.shows)
        assertEquals(1, result.failed)
        assertEquals(0, result.earned)
    }

    @Test
    @Config(instrumentedPackages = [
        "com.ads.module.helper.CachedAd",
        "com.ads.module.helper.reward.RewardTestWallClock",
    ])
    fun `busy rejection retains the original cache expiry while a shown reward stays exclusive past ninety seconds`() {
        val clock = RewardTestWallClock()
        val filledAt = clock.currentTimeMillis()
        assertEquals(ShadowSystem.currentTimeMillis(), filledAt)
        val buffered = loadManagerReward()
        assertEquals(filledAt, clock.currentTimeMillis())
        val active = reward("active-unit")
        ERainAd.getInstance().showRewardAds(activity, active.ad, RecordingReward())
        active.fullscreen!!.onAdShowedFullScreenContent()
        ShadowSystemClock.advanceBy(59, TimeUnit.MINUTES)
        val result = RecordingManagerReward()

        RewardAdManager.show(activity, "reward", result)

        assertEquals(0, buffered.shows)
        assertEquals(listOf(AdSkipReason.PRESENTATION_BUSY), result.rejected)
        assertTrue(RewardAdManager.isReady("reward"))
        val remaining = filledAt + TimeUnit.HOURS.toMillis(1) - clock.currentTimeMillis()
        assertTrue(remaining in 1..TimeUnit.MINUTES.toMillis(1))
        ShadowSystemClock.advanceBy(remaining - 1, TimeUnit.MILLISECONDS)
        assertTrue(RewardAdManager.isReady("reward"))
        ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS)
        assertEquals(filledAt + TimeUnit.HOURS.toMillis(1), clock.currentTimeMillis())
        assertEquals(ShadowSystem.currentTimeMillis(), clock.currentTimeMillis())
        assertFalse("Rejecting after 59 minutes must not grant the fill a new hour", RewardAdManager.isReady("reward"))
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
    }

    @Test
    fun `rewarded interstitial owns fullscreen and preserves click close and late earn independently`() {
        val ad = Mockito.mock(RewardedInterstitialAd::class.java)
        Mockito.`when`(ad.adUnitId).thenReturn("reward-interstitial-unit")
        var fullscreen: FullScreenContentCallback? = null
        var earn: OnUserEarnedRewardListener? = null
        var shows = 0
        Mockito.doAnswer { invocation -> fullscreen = invocation.getArgument(0); null }
            .`when`(ad).setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
        Mockito.doAnswer { invocation -> shows++; earn = invocation.getArgument(1); null }
            .`when`(ad).show(Mockito.any(Activity::class.java), Mockito.any(OnUserEarnedRewardListener::class.java))
        otherFullscreen += { fullscreen }
        val result = RecordingReward()

        ERainAd.getInstance().showRewardInterstitial(activity, ad, result)
        fullscreen!!.onAdShowedFullScreenContent()
        fullscreen!!.onAdShowedFullScreenContent()
        fullscreen!!.onAdClicked()
        val second = reward("second-unit")
        ERainAd.getInstance().showRewardAds(activity, second.ad, RecordingReward())

        assertEquals(1, shows)
        assertEquals(0, second.shows)
        assertEquals(1, result.presented)
        assertEquals(1, result.clicked)
        fullscreen!!.onAdDismissedFullScreenContent()
        ERainAd.getInstance().showRewardAds(activity, second.ad, RecordingReward())
        val item = Mockito.mock(RewardItem::class.java)
        earn!!.onUserEarnedReward(item)
        earn!!.onUserEarnedReward(item)
        fullscreen!!.onAdDismissedFullScreenContent()
        assertEquals(1, second.shows)
        assertEquals(1, result.closed)
        assertEquals(1, result.earned)
        assertTrue(AppOpenManager.getInstance().isInterstitialShowing)
    }

    @Test
    fun `raw premium acquired during setup preserves earn without adding a close callback`() {
        val ad = reward("unit")
        ad.onAttach = { premium = true }
        val result = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, ad.ad, result)

        assertEquals(0, ad.shows)
        assertEquals(1, result.earned)
        assertEquals(0, result.closed)
        assertEquals(0, result.failed)
    }

    @Test
    fun `synchronous load and show cannot reinsert a consumed reward into the raw cache`() {
        var failed = 0
        RewardAdManager.loadAndShow(activity, "reward", listOf("unit"),
            onSuccess = Runnable {}, onFailed = Runnable { failed++ })
        val ad = reward("unit")
        requests.single().second.onAdLoaded(ad.ad)
        assertEquals(1, ad.shows)
        ad.fullscreen!!.onAdFailedToShowFullScreenContent(AdError(7, "failed", "vendor"))
        assertEquals(1, failed)
        val raw = RecordingReward()

        ERainAd.getInstance().showRewardAds(activity, raw)

        assertEquals("An already invoked physical ad cannot return through the raw field", 1, ad.shows)
        assertEquals(1, raw.failed)
        assertEquals(2, requests.size) // Existing null-cache fallback may preload a replacement.
    }

    @Test
    fun `raw load callback can synchronously install a newer fill without the first callback overwriting it`() {
        val first = reward("first-unit")
        val second = reward("second-unit")
        ERainAd.getInstance().initRewardAds(activity, "first-unit", object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                ERainAd.getInstance().initRewardAds(activity, "second-unit")
                requests.last().second.onAdLoaded(second.ad)
            }
        })

        requests.first().second.onAdLoaded(first.ad)
        ERainAd.getInstance().showRewardAds(activity, RecordingReward())

        assertEquals(0, first.shows)
        assertEquals(1, second.shows)
    }

    @Test
    fun `late raw load failure cannot clear a newer filled reward`() {
        ERainAd.getInstance().initRewardAds(activity, "old-unit")
        ERainAd.getInstance().initRewardAds(activity, "new-unit")
        val newest = reward("new-unit")
        requests.last().second.onAdLoaded(newest.ad)

        requests.first().second.onAdFailedToLoad(LoadAdError(3, "old failure", "vendor", null, null))
        ERainAd.getInstance().showRewardAds(activity, RecordingReward())

        assertEquals(1, newest.shows)
        assertEquals(2, requests.size)
    }

    @Test
    fun `duplicate old raw fill cannot replace a newer completed generation`() {
        val first = reward("old-unit")
        val newest = reward("new-unit")
        ERainAd.getInstance().initRewardAds(activity, "old-unit")
        requests.first().second.onAdLoaded(first.ad)
        ERainAd.getInstance().initRewardAds(activity, "new-unit")
        requests.last().second.onAdLoaded(newest.ad)

        requests.first().second.onAdLoaded(first.ad)
        ERainAd.getInstance().showRewardAds(activity, RecordingReward())

        assertEquals(0, first.shows)
        assertEquals(1, newest.shows)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a checked admission exception rejects before consumption and releases its reservation`() {
        val ad = reward("unit")
        var rejected: AdSkipReason? = null
        val result = object : RecordingReward() {
            override fun getAdShowSkipReason(): AdSkipReason? = throw Exception("checked host failure")
            override fun onAdShowRejected(reason: AdSkipReason) {
                rejected = reason
                super.onAdShowRejected(reason)
            }
        }
        try {
            ERainAd.getInstance().showRewardAds(activity, ad.ad, result)

            assertEquals(0, ad.shows)
            assertEquals(AdSkipReason.PREPARATION_FAILED, rejected)
            assertEquals(1, result.failed)
            val next = reward("next-unit")
            ERainAd.getInstance().showRewardAds(activity, next.ad, RecordingReward())
            assertEquals(1, next.shows)
        } finally {
            // On RED the hook escapes with a reservation; expire it through the real clock,
            // without resetting the owner or allowing it to contaminate the following case.
            shadowOf(Looper.getMainLooper()).idleFor(91, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `explicit reward refills its captured unit once only after actual presentation`() {
        ERainAd.getInstance().initRewardAds(activity, "unrelated-unit")
        val ad = reward("presented-unit")
        ERainAd.getInstance().showRewardAds(activity, ad.ad, RecordingReward())
        assertEquals(listOf("unrelated-unit"), requests.map { it.first })

        ad.fullscreen!!.onAdShowedFullScreenContent()
        ad.fullscreen!!.onAdShowedFullScreenContent()

        assertEquals(listOf("unrelated-unit", "presented-unit"), requests.map { it.first })
        assertEquals(1, ad.shows)
    }

    private fun loadManagerReward(): VendorReward {
        RewardAdManager.load(activity, "reward", listOf("buffered-unit"))
        val ad = reward("buffered-unit")
        requests.last().second.onAdLoaded(ad.ad)
        return ad
    }

    private fun reward(unit: String): VendorReward = VendorReward(unit).also { ads += it }

    private class VendorReward(unit: String) {
        val ad = Mockito.mock(RewardedAd::class.java)
        var fullscreen: FullScreenContentCallback? = null
        var earn: OnUserEarnedRewardListener? = null
        var paid: OnPaidEventListener? = null
        var shows = 0
        var onAttach: () -> Unit = {}
        var onShow: () -> Unit = {}

        init {
            val response = Mockito.mock(ResponseInfo::class.java)
            Mockito.`when`(response.mediationAdapterClassName).thenReturn("adapter-$unit")
            Mockito.`when`(ad.adUnitId).thenReturn(unit)
            Mockito.`when`(ad.responseInfo).thenReturn(response)
            Mockito.doAnswer { invocation ->
                fullscreen = invocation.getArgument(0)
                onAttach()
                null
            }.`when`(ad).setFullScreenContentCallback(Mockito.any(FullScreenContentCallback::class.java))
            Mockito.doAnswer { invocation ->
                paid = invocation.getArgument(0)
                null
            }.`when`(ad).setOnPaidEventListener(Mockito.any(OnPaidEventListener::class.java))
            Mockito.doAnswer { invocation ->
                shows++
                earn = invocation.getArgument(1)
                onShow()
                null
            }.`when`(ad).show(Mockito.any(Activity::class.java), Mockito.any(OnUserEarnedRewardListener::class.java))
        }
    }

    private open class RecordingReward : RewardCallback {
        var earned = 0
        var closed = 0
        var failed = 0
        var clicked = 0
        var presented = 0
        override fun onUserEarnedReward(item: RewardItem?) { earned++ }
        override fun onRewardedAdClosed() { closed++ }
        override fun onRewardedAdFailedToShow(codeError: Int) { failed++ }
        override fun onAdClicked() { clicked++ }
        override fun onAdPresented() { presented++ }
    }

    private class RecordingManagerReward : RewardShowCallback() {
        var earned = 0
        val closed = mutableListOf<Boolean>()
        var failed = 0
        val rejected = mutableListOf<AdSkipReason>()
        override fun onEarned(item: RewardItem?) { earned++ }
        override fun onClosed(earned: Boolean) { closed += earned }
        override fun onFailedToShow(codeError: Int) { failed++ }
        override fun onRejected(reason: AdSkipReason) {
            rejected += reason
            super.onRejected(reason)
        }
    }
}

/** Same external Java wall-clock call as CachedAd, rewritten by Robolectric for its TTL case. */
class RewardTestWallClock {
    fun currentTimeMillis(): Long = System.currentTimeMillis()
}
