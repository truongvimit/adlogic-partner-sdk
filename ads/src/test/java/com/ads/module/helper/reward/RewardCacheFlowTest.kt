package com.ads.module.helper.reward

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.Admob
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.Int02Activity
import com.ads.module.helper.interstitial.Int02Application
import com.ads.module.helper.interstitial.Int02FacebookShadow
import com.ads.module.helper.interstitial.Int02MobileAdsShadow
import com.ads.module.helper.interstitial.Tel02RewardedAd
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Exercise manager -> waterfall -> ERain -> Admob; replace only the external GMA objects. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, RewardFlowLoadShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RewardCacheFlowTest {
    private lateinit var controller: ActivityController<Int02Activity>
    private lateinit var activity: Int02Activity
    private val requests get() = RewardFlowLoadShadow.requests
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val vendorAds = mutableListOf<Tel02RewardedAd>()
    private var premium = false

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
            override fun isPremium(context: Context): Boolean = premium
        })
        ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
            facebookClientToken = "reward-cache-test-client-token"
        })
        AppOpenManager.getInstance().disableAppResume()
        AppOpenManager.getInstance().setInterstitialShowing(false)
        controller = Robolectric.buildActivity(Int02Activity::class.java).setup()
        activity = controller.get()
        mainLooper.idle()
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
    fun `preload and load share one request and notify every subscriber once`() {
        val first = RecordingLoad()
        val second = RecordingLoad()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = first)
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = second)
        assertEquals(1, requests.size)

        val ad = newAd()
        requests.single().onAdLoaded(ad)
        requests.single().onAdLoaded(newAd())
        requests.single().onAdFailedToLoad(loadError())
        assertEquals(listOf(ad), first.loaded)
        assertEquals(listOf(ad), second.loaded)
        assertEquals(0, first.failed + second.failed)

        val cached = RecordingLoad()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = cached)
        assertEquals(listOf(ad), cached.loaded)
        assertEquals(1, requests.size)
    }

    @Test
    fun `one failed request settles all load subscribers and permits retry`() {
        val first = RecordingLoad()
        val second = RecordingLoad()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = first)
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = second)
        requests.single().onAdFailedToLoad(loadError())
        requests.single().onAdFailedToLoad(loadError())
        assertEquals(1, first.failed)
        assertEquals(1, second.failed)
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT))
        assertEquals(2, requests.size)
    }

    @Test
    fun `show is cache only and does not refill on shown or dismissal`() {
        val empty = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, empty)
        assertEquals(listOf(0), empty.failures)
        assertTrue(requests.isEmpty())

        val ad = loadAndFill()
        val shown = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, shown)
        assertEquals(listOf(activity), ad.state.hosts)
        assertFalse(RewardAdManager.isReady(PLACEMENT))
        ad.state.callback!!.onAdShowedFullScreenContent()
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(listOf(false), shown.closed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `host opt-in buffers the next rewarded ad once this one closes`() {
        AdRemoteConfig.initializeFromJson("""{"$PLACEMENT":{"id":"$UNIT","isEnable":true}}""")
        RewardAdManager.bufferAfterClose = true
        val ad = loadAndFill()
        RewardAdManager.show(activity, PLACEMENT, RecordingShow())
        ad.state.callback!!.onAdShowedFullScreenContent()
        assertEquals("Showing alone must not load anything", 1, requests.size)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals("Closing must load the replacement", 2, requests.size)
    }

    @Test
    fun `remote config opt-in outranks a host flag left off`() {
        AdRemoteConfig.initializeFromJson("""{"$PLACEMENT":{"id":"$UNIT","isEnable":true}}""")
        AdBehavior.document.acceptSuccessfulFetch("""{"rewarded":{"buffer":{"after_close":true}}}""")
        val ad = loadAndFill()
        RewardAdManager.show(activity, PLACEMENT, RecordingShow())
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(2, requests.size)
    }

    @Test
    fun `opt-in leaves a placement the config never declares to its caller`() {
        RewardAdManager.bufferAfterClose = true
        val ad = loadAndFill()
        RewardAdManager.show(activity, PLACEMENT, RecordingShow())
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals("An explicitly loaded key stays the caller's to reload", 1, requests.size)
    }

    @Test
    fun `loadAndShow spends existing cache and waits for actual earned and closed callbacks`() {
        val ad = loadAndFill()
        val outcome = Outcome()
        loadAndShow(outcome)
        assertEquals(listOf(activity), ad.state.hosts)
        assertEquals(1, requests.size)
        ad.state.callback!!.onAdShowedFullScreenContent()
        mainLooper.idleFor(2, TimeUnit.MINUTES)
        assertEquals(0, outcome.completed)
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        assertEquals(0, outcome.completed)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        ad.state.callback!!.onAdDismissedFullScreenContent()
        ad.state.callback!!.onAdFailedToShowFullScreenContent(showError())
        assertEquals(1, outcome.success)
        assertEquals(0, outcome.failed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `loadAndShow joins preload and rejects duplicate without losing either callback`() {
        val preload = RecordingLoad()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = preload)
        val first = Outcome()
        val duplicate = Outcome()
        loadAndShow(first)
        loadAndShow(duplicate)
        assertEquals(1, duplicate.failed)
        assertEquals(1, requests.size)
        val ad = newAd()
        requests.single().onAdLoaded(ad)
        assertEquals(listOf(ad), preload.loaded)
        assertEquals(listOf(activity), ad.state.hosts)
        val duringShow = Outcome()
        loadAndShow(duringShow)
        assertEquals(1, duringShow.failed)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, first.failed)
        assertEquals(1, duplicate.completed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `cold loadAndShow shares its load with a later preload and propagates load failure`() {
        val outcome = Outcome()
        val load = RecordingLoad()
        loadAndShow(outcome)
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = load)
        assertEquals(1, requests.size)
        requests.single().onAdFailedToLoad(loadError())
        assertEquals(1, outcome.failed)
        assertEquals(1, load.failed)
        assertFalse(RewardAdManager.isReady(PLACEMENT))
    }

    @Test
    fun `release settles waiters and old fills cannot replace a new request`() {
        val oldLoad = RecordingLoad()
        val waiting = Outcome()
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = oldLoad)
        loadAndShow(waiting)
        val oldRequest = requests.single()
        RewardAdManager.release(PLACEMENT)
        assertEquals(1, oldLoad.failed)
        assertEquals(1, waiting.failed)

        val newLoad = RecordingLoad()
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = newLoad)
        oldRequest.onAdLoaded(newAd())
        assertFalse(RewardAdManager.isReady(PLACEMENT))
        assertTrue(newLoad.loaded.isEmpty())
        val replacement = newAd()
        requests.last().onAdLoaded(replacement)
        assertEquals(listOf(replacement), newLoad.loaded)
        assertEquals(1, waiting.completed)
    }

    @Test
    fun `releaseAll detaches old requests before reentrant callbacks can load again`() {
        val replacement = RecordingLoad()
        var cancelled = 0
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError?) {
                cancelled++
                RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = replacement)
            }
        })
        val oldRequest = requests.single()
        RewardAdManager.releaseAll()
        assertEquals(1, cancelled)
        assertEquals(2, requests.size)
        oldRequest.onAdLoaded(newAd())
        val ad = newAd()
        requests.last().onAdLoaded(ad)
        assertEquals(listOf(ad), replacement.loaded)
        assertTrue(RewardAdManager.isReady(PLACEMENT))
    }

    @Test
    fun `release during a presentation waits for real vendor outcome`() {
        val ad = loadAndFill()
        val outcome = Outcome()
        loadAndShow(outcome)
        RewardAdManager.release(PLACEMENT)
        RewardAdManager.releaseAll()
        mainLooper.idleFor(2, TimeUnit.MINUTES)
        assertEquals(0, outcome.completed)
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, outcome.success)
    }

    @Test
    fun `reentrant show can consume a fill only once while loadAndShow is pending`() {
        val explicit = RecordingShow()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                RewardAdManager.show(activity, PLACEMENT, explicit)
            }
        })
        val waiting = Outcome()
        loadAndShow(waiting)
        val ad = newAd()
        requests.single().onAdLoaded(ad)
        assertEquals(listOf(activity), ad.state.hosts)
        assertEquals(1, waiting.failed)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(listOf(false), explicit.closed)
        assertEquals(1, requests.size)
    }

    @Test
    fun `old loadAndShow callback cannot spend a replacement fill loaded by an earlier subscriber`() {
        val first = newAd()
        val replacement = newAd()
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                RewardAdManager.show(activity, PLACEMENT, RecordingShow())
                RewardAdManager.load(activity, PLACEMENT, listOf(UNIT))
                requests.last().onAdLoaded(replacement)
            }
        })
        val waiting = Outcome()
        loadAndShow(waiting)
        requests.first().onAdLoaded(first)
        assertEquals(1, waiting.failed)
        assertEquals(listOf(activity), first.state.hosts)
        assertTrue(replacement.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        first.state.callback!!.onAdDismissedFullScreenContent()
        RewardAdManager.show(activity, PLACEMENT, RecordingShow())
        assertEquals(listOf(activity), replacement.state.hosts)
        assertEquals(2, requests.size)
    }

    @Test
    fun `synchronous GMA load exception completes once and a new call can retry`() {
        RewardFlowLoadShadow.failure = IllegalStateException("load failed synchronously")
        val failed = Outcome()
        loadAndShow(failed)
        assertEquals(1, failed.failed)
        assertFalse(RewardAdManager.isReady(PLACEMENT))
        RewardFlowLoadShadow.failure = null
        val retry = Outcome()
        loadAndShow(retry)
        val ad = newAd()
        requests.single().onAdLoaded(ad)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        mainLooper.idleFor(31, TimeUnit.SECONDS)
        assertEquals(1, failed.completed)
        assertEquals(1, retry.failed)
    }

    @Test
    fun `synchronous tier exception cancels its timeout before fallback succeeds`() {
        RewardFlowLoadShadow.failure = IllegalStateException("first tier dispatch failed")
        val load = RecordingLoad()
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT, FALLBACK_UNIT), listener = load)
        assertEquals(listOf(UNIT, FALLBACK_UNIT), RewardFlowLoadShadow.requestedUnits)
        assertEquals(1, requests.size)
        val fallback = Tel02RewardedAd(FALLBACK_UNIT).also { vendorAds += it }
        requests.single().onAdLoaded(fallback)
        mainLooper.idleFor(31, TimeUnit.SECONDS)
        assertEquals(listOf(UNIT, FALLBACK_UNIT), RewardFlowLoadShadow.requestedUnits)
        assertEquals(listOf(fallback), load.loaded)
        assertEquals(0, load.failed)
        val cached = RecordingLoad()
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = cached)
        assertEquals(listOf(fallback), cached.loaded)
        assertEquals(1, requests.size)
    }

    @Test
    fun `synchronous GMA show exception completes once and releases presentation ownership`() {
        val throwingAd = mock(RewardedAd::class.java) { invocation ->
            when (invocation.method.name) {
                "getAdUnitId" -> UNIT
                "show" -> throw IllegalStateException("show failed synchronously")
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val first = Outcome()
        loadAndShow(first)
        requests.single().onAdLoaded(throwingAd)
        assertEquals(1, first.failed)
        assertFalse(RewardAdManager.isReady(PLACEMENT))
        val retry = Outcome()
        loadAndShow(retry)
        assertEquals(2, requests.size)
        val ad = newAd()
        requests.last().onAdLoaded(ad)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, retry.failed)
        assertEquals(1, first.completed)
    }

    @Test
    fun `throwing subscriber cannot prevent another load callback or pending presentation`() {
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                throw IllegalStateException("host callback failed")
            }
        })
        val load = RecordingLoad()
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT), listener = load)
        val outcome = Outcome()
        loadAndShow(outcome)
        val ad = newAd()
        requests.single().onAdLoaded(ad)
        assertEquals(listOf(ad), load.loaded)
        assertEquals(listOf(activity), ad.state.hosts)
        ad.state.callback!!.onAdFailedToShowFullScreenContent(showError())
        assertEquals(1, outcome.failed)
    }

    @Test
    fun `show forwards vendor events in order and ignores events after terminal`() {
        val ad = loadAndFill()
        val result = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, result)
        mainLooper.idleFor(2, TimeUnit.MINUTES)
        assertTrue(result.events.isEmpty())
        ad.state.callback!!.onAdShowedFullScreenContent()
        ad.state.callback!!.onAdImpression()
        ad.state.callback!!.onAdClicked()
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        ad.state.callback!!.onAdFailedToShowFullScreenContent(showError())
        ad.state.callback!!.onAdClicked()
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        assertEquals(listOf("shown", "impression", "clicked", "earned", "closed:true"), result.events)
    }

    @Test
    fun `mediation earned event after close is forwarded without revising the close snapshot`() {
        val ad = loadAndFill()
        val result = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, result)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        assertEquals(listOf("closed:false", "earned"), result.events)
        assertEquals(listOf(false), result.closed)

        val second = loadAndFill()
        val outcome = Outcome()
        loadAndShow(outcome)
        second.state.callback!!.onAdDismissedFullScreenContent()
        second.state.earnedListener!!.onUserEarnedReward(second.state.item)
        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.success)
    }

    @Test
    fun `default tier timeout remains thirty seconds and only settles a load`() {
        val outcome = Outcome()
        loadAndShow(outcome)
        mainLooper.idleFor(29, TimeUnit.SECONDS)
        assertEquals(0, outcome.completed)
        mainLooper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1, outcome.failed)
        assertEquals(1, requests.size)
        requests.single().onAdLoaded(newAd())
        assertFalse(RewardAdManager.isReady(PLACEMENT))
        assertEquals(1, outcome.completed)
    }

    @Test
    fun `baseline enabled consent and premium gates still reject loadAndShow without spending cache`() {
        loadAndFill()
        val disabled = Outcome()
        loadAndShow(disabled, enabled = false)
        assertEquals(1, disabled.failed)
        ConsentCenter.setHostConsent(false, false)
        val noConsent = Outcome()
        loadAndShow(noConsent)
        assertEquals(1, noConsent.failed)
        ConsentCenter.setHostConsent(true, false)
        premium = true
        val purchased = Outcome()
        loadAndShow(purchased)
        assertEquals(1, purchased.failed)
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        assertEquals(1, requests.size)
    }

    @Test
    fun `declared purchased show clears cache while explicit undeclared show retains premium shortcut`() {
        loadAndFill()
        AdRemoteConfig.update(AdRemoteConfig(mapOf(PLACEMENT to AdUnitConfig(id = UNIT, isEnable = true))))
        premium = true
        val declared = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, declared)
        assertEquals(listOf(0), declared.failures)
        assertFalse(RewardAdManager.isReady(PLACEMENT))

        premium = false
        AdRemoteConfig.reset()
        val ad = loadAndFill()
        premium = true
        val explicit = RecordingShow()
        RewardAdManager.show(activity, PLACEMENT, explicit)
        assertEquals(listOf("earned", "closed:true"), explicit.events)
        assertTrue(ad.state.hosts.isEmpty())
    }

    private fun newAd() = Tel02RewardedAd(UNIT).also { vendorAds += it }

    private fun loadAndFill(): Tel02RewardedAd {
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT))
        val ad = newAd()
        requests.last().onAdLoaded(ad)
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        return ad
    }

    private fun loadAndShow(outcome: Outcome, enabled: Boolean = true) = RewardAdManager.loadAndShow(
        activity, PLACEMENT, listOf(UNIT), enabled,
        onSuccess = Runnable { outcome.success++ },
        onFailed = Runnable { outcome.failed++ },
    )

    private class Outcome {
        var success = 0
        var failed = 0
        val completed get() = success + failed
    }

    private class RecordingLoad : AdCallback() {
        val loaded = mutableListOf<RewardedAd?>()
        var failed = 0
        override fun onRewardAdLoaded(rewardedAd: RewardedAd?) { loaded += rewardedAd }
        override fun onAdFailedToLoad(adError: LoadAdError?) { failed++ }
    }

    private class RecordingShow : RewardShowCallback() {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Int>()
        val closed = mutableListOf<Boolean>()
        override fun onShown() { events += "shown" }
        override fun onImpression() { events += "impression" }
        override fun onEarned(item: RewardItem?) { events += "earned" }
        override fun onClosed(earned: Boolean) { events += "closed:$earned"; closed += earned }
        override fun onFailedToShow(codeError: Int) { events += "failed:$codeError"; failures += codeError }
        override fun onClicked() { events += "clicked" }
    }

    companion object {
        private const val PLACEMENT = "reward-cache-flow"
        private const val UNIT = "reward-cache-unit"
        private const val FALLBACK_UNIT = "reward-cache-fallback"
        private fun loadError() = LoadAdError(3, "no fill", "test", null, null)
        private fun showError() = AdError(1, "show failed", "test")
    }
}

@Implements(value = RewardedAd::class, isInAndroidSdk = false)
class RewardFlowLoadShadow {
    companion object {
        val requests = mutableListOf<RewardedAdLoadCallback>()
        val requestedUnits = mutableListOf<String>()
        var failure: RuntimeException? = null

        @JvmStatic
        @Implementation
        fun load(context: Context, adUnitId: String, request: AdRequest, callback: RewardedAdLoadCallback) {
            requestedUnits += adUnitId
            failure?.let {
                failure = null
                throw it
            }
            requests += callback
        }
    }
}
