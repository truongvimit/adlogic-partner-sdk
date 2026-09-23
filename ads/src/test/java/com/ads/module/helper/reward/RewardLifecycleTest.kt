package com.ads.module.helper.reward

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
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
import com.google.android.gms.ads.rewarded.RewardedAd
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
import java.util.concurrent.TimeUnit

/** Real manager/waterfall/engine; only external GMA load and ad objects are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = Int02Application::class,
    shadows = [Int02MobileAdsShadow::class, Int02FacebookShadow::class, RewardFlowLoadShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class RewardLifecycleTest {
    private lateinit var controller: ActivityController<out Activity>
    private lateinit var activity: Activity
    private var stopped = false
    private val main get() = shadowOf(Looper.getMainLooper())
    private val requests get() = RewardFlowLoadShadow.requests
    private val ads = mutableListOf<Tel02RewardedAd>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RewardAdManager.releaseAll()
        RewardAdManager.bufferAfterClose = false
        AdRemoteConfig.reset()
        AdBehavior.document.acceptSuccessfulFetch(null)
        requests.clear()
        RewardFlowLoadShadow.requestedUnits.clear()
        RewardFlowLoadShadow.failure = null
        ConsentCenter.setHostConsent(true, false)
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
        ERainAd.init(app, ERainAdConfig(app).apply { facebookClientToken = "reward-lifecycle-test" })
        AppOpenManager.disableAppResume()
        AppOpenManager.setInterstitialShowing(false)
        startHost()
    }

    @After
    fun tearDown() {
        ads.filter { it.state.hosts.isNotEmpty() }.forEach {
            it.state.callback?.onAdDismissedFullScreenContent()
        }
        if (!activity.isDestroyed) destroyHost()
        RewardAdManager.releaseAll()
        RewardAdManager.bufferAfterClose = false
        main.idleFor(31, TimeUnit.SECONDS)
        ConsentCenter.clearHostConsent()
        AppOpenManager.setInterstitialShowing(false)
        AdBehavior.document.acceptSuccessfulFetch(null)
        AdRemoteConfig.reset()
        requests.clear()
        RewardFlowLoadShadow.requestedUnits.clear()
        RewardFlowLoadShadow.failure = null
    }

    @Test
    fun `stop cancels pending autoplay and same host return requires a fresh trigger`() {
        val first = loadAndShow()
        stopHost()
        assertEquals("Stop ends this presentation once", 1, first.failed)
        resumeHost()
        val ad = newAd()
        requests.single().onAdLoaded(ad)

        assertTrue("A late fill must never autoplay on return", ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        assertEquals(1, first.completed)

        val retry = loadAndShow()
        assertEquals("Explicit retry consumes the retained fill", listOf(activity), ad.state.hosts)
        assertEquals(1, requests.size)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, retry.failed)
        assertEquals(1, first.completed)
    }

    @Test
    fun `destroy cancels pending autoplay while late fill stays available to a new host`() {
        val first = loadAndShow()
        val departed = activity
        destroyHost()
        assertEquals(1, first.failed)
        val ad = newAd()
        requests.single().onAdLoaded(ad)

        assertTrue(ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        startHost()
        val retry = loadAndShow()
        assertEquals(listOf(activity), ad.state.hosts)
        assertFalse(ad.state.hosts.contains(departed))
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, retry.failed)
        assertEquals(1, first.completed)
    }

    @Test
    fun `stopped host cannot spend an already ready reward`() {
        val ad = loadAndFill()
        stopHost()
        val rejected = loadAndShow()

        assertEquals(1, rejected.failed)
        assertTrue(ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        resumeHost()
        loadAndShow()
        assertEquals(listOf(activity), ad.state.hosts)
    }

    @Test
    fun `direct show retains ready reward for a new foreground trigger`() {
        val ad = loadAndFill()
        stopHost()
        var failures = 0
        RewardAdManager.show(activity, PLACEMENT, object : RewardShowCallback() {
            override fun onFailedToShow(codeError: Int) { failures++ }
        })

        assertEquals(1, failures)
        assertTrue(ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
        resumeHost()
        val completions = mutableListOf<Boolean>()
        RewardAdManager.show(activity, PLACEMENT) { completions += it }
        assertEquals(listOf(activity), ad.state.hosts)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(listOf(false), completions)
        assertEquals(1, failures)
    }

    @Test
    fun `finishing host cannot spend an already ready reward`() {
        val ad = loadAndFill()
        activity.finish()
        val rejected = loadAndShow()

        assertEquals(1, rejected.failed)
        assertTrue(ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
    }

    @Test
    fun `cancelling autoplay keeps shared preload and a new pending trigger on the same request`() {
        var preloadFills = 0
        RewardAdManager.preload(activity, PLACEMENT, listOf(UNIT), listener = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) { preloadFills++ }
        })
        val first = loadAndShow()
        stopHost()
        assertEquals(1, first.failed)
        resumeHost()
        val retry = loadAndShow()
        assertEquals("The shared request survives presentation cancellation", 1, requests.size)
        val ad = newAd()
        requests.single().onAdLoaded(ad)

        assertEquals(1, preloadFills)
        assertEquals(listOf(activity), ad.state.hosts)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, first.completed)
        assertEquals(1, retry.failed)
    }

    @Test
    fun `host destruction after dispatch waits for the real vendor outcome`() {
        val ad = loadAndFill()
        val outcome = loadAndShow()
        ad.state.callback!!.onAdShowedFullScreenContent()
        destroyHost()

        assertEquals(0, outcome.completed)
        assertTrue("Visible fullscreen ownership survives the host", AppOpenManager.isInterstitialShowing)
        ad.state.earnedListener!!.onUserEarnedReward(ad.state.item)
        ad.state.callback!!.onAdDismissedFullScreenContent()
        assertEquals(1, outcome.success)
        assertFalse(AppOpenManager.isInterstitialShowing)
    }

    @Test
    fun `plain Activity cancellation and ready guard do not wait for process stop debounce`() {
        destroyHost()
        startHost(plain = true)
        val first = loadAndShow()
        assertEquals(
            "Valid plain host: focus=${activity.hasWindowFocus()}, " +
                "process=${ProcessLifecycleOwner.get().lifecycle.currentState}",
            0, first.failed,
        )
        assertEquals("A valid host starts a real pending request", 1, requests.size)
        stopHost(drainProcessStop = false)
        assertEquals(Lifecycle.State.RESUMED, ProcessLifecycleOwner.get().lifecycle.currentState)
        assertEquals(1, first.failed)
        val ad = newAd()
        requests.single().onAdLoaded(ad)
        val rejected = loadAndShow()

        assertEquals(1, rejected.failed)
        assertTrue(ad.state.hosts.isEmpty())
        assertTrue(RewardAdManager.isReady(PLACEMENT))
    }

    private fun loadAndShow(): Outcome = Outcome().also { outcome ->
        RewardAdManager.loadAndShow(
            activity, PLACEMENT, listOf(UNIT),
            onSuccess = Runnable { outcome.success++ },
            onFailed = Runnable { outcome.failed++ },
        )
    }

    private fun loadAndFill(): Tel02RewardedAd {
        RewardAdManager.load(activity, PLACEMENT, listOf(UNIT))
        return newAd().also { requests.last().onAdLoaded(it) }
    }

    private fun newAd() = Tel02RewardedAd(UNIT).also { ads += it }

    private fun startHost(plain: Boolean = false) {
        controller = if (plain) Robolectric.buildActivity(Activity::class.java).setup()
            else Robolectric.buildActivity(Int02Activity::class.java).setup()
        controller.visible().windowFocusChanged(true)
        activity = controller.get()
        stopped = false
        main.idle()
    }

    private fun stopHost(drainProcessStop: Boolean = true) {
        controller.windowFocusChanged(false).pause().stop()
        stopped = true
        if (drainProcessStop) main.idleFor(800, TimeUnit.MILLISECONDS)
    }

    private fun resumeHost() {
        controller.restart().start().resume().visible().windowFocusChanged(true)
        stopped = false
        main.idle()
    }

    private fun destroyHost() {
        if (!stopped) stopHost()
        controller.destroy()
        main.idle()
    }

    private class Outcome {
        var success = 0
        var failed = 0
        val completed get() = success + failed
    }

    private companion object {
        const val PLACEMENT = "reward-lifecycle"
        const val UNIT = "reward-lifecycle-test-unit"
    }
}
