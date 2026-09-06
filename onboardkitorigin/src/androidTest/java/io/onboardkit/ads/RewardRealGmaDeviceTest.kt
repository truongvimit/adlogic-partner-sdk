package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run alone in a fresh instrumentation process on an awake, unlocked, online device.
 * Reuses INT-02's real Activity/manifest/bootstrap and Google's rewarded test unit.
 * No callback is replaced or invoked by the test. When TEL02_REWARD AD_ACTIVITY_VISIBLE
 * appears, watch until the reward condition is met, then use the real Close UI.
 */
@RunWith(AndroidJUnit4::class)
class RewardRealGmaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun suppliedRealRewardReportsShownEarnedAndClosedOnce() {
        val events = CopyOnWriteArrayList<String>()
        val shown = CopyOnWriteArrayList<Map<String, Any?>>()
        val showFailures = CopyOnWriteArrayList<Map<String, Any?>>()
        val sink = object : TrackSink {
            override val id = "tel02-real-reward"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (params["placement"] != PLACEMENT) return
                when (name) {
                    "ad_show" -> {
                        shown += params.toMap()
                        events += "shown"
                        Log.i(TAG, "VENDOR_SHOW count=${shown.size}")
                    }
                    "ad_show_failed" -> showFailures += params.toMap()
                }
            }
        }
        val initialized = CountDownLatch(1)
        instrumentation.runOnMainSync {
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
            Tracker.install(app)
            Tracker.setConsent(true, true)
            Tracker.addSink(sink)
            AdTracking.registerPlacement(TEST_UNIT, PLACEMENT)
        }
        try {
            assertTrue("Real GMA initialization must finish", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<InterstitialRealGmaDeviceActivity>(
                Intent(app, InterstitialRealGmaDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var host: InterstitialRealGmaDeviceActivity
                scenario.onActivity {
                    host = it
                    it.setContentView(TextView(it).apply {
                        gravity = Gravity.CENTER
                        text = "TEL-02 real GMA rewarded ad\nWatch until the reward is granted, then use the ad's real Close UI."
                    })
                }
                eventually("Real host and process must be resumed") {
                    onMain {
                        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                val load = RecordingLoad()
                instrumentation.runOnMainSync { ERainAd.getInstance().initRewardAds(host, TEST_UNIT, load) }
                assertTrue("Real rewarded load must settle", load.finished.await(45, TimeUnit.SECONDS))
                assertTrue("Real test fill required: ${load.errors}", load.errors.isEmpty())
                assertEquals(1, load.ads.size)
                val reward = load.ads.single()
                assertEquals(TEST_UNIT, onMain { reward.adUnitId })
                assertEquals("Load success is not display", 0, shown.size)
                Log.i(TAG, "FILLED real_reward=${System.identityHashCode(reward)} shown=0")
                val callback = RecordingReward(events)
                instrumentation.runOnMainSync {
                    // Explicit supplied-ad overload; public init above supplies the same unit
                    // and exercises the existing refill path without an invented nativeId.
                    ERainAd.getInstance().showRewardAds(host, reward, callback)
                }
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                eventually("Show must reach a visible focused real GMA AdActivity; errors=${callback.failures}", 15_000) {
                    onMain {
                        monitor.getActivitiesInStage(Stage.RESUMED).any {
                            it is AdActivity && it.window.decorView.isShown && it.hasWindowFocus()
                        }
                    }
                }
                eventually("Real vendor presentation must emit one canonical Show") { shown.size == 1 }
                assertEquals("rewarded", shown.single()["ad_format"])
                assertEquals(TEST_UNIT, shown.single()["ad_unit_id"])
                assertTrue(showFailures.isEmpty())
                assertEquals(0, callback.closedCount)
                Log.i(TAG, "AD_ACTIVITY_VISIBLE shown=1; watch until reward is granted, then use real Close UI")
                val closeTimeoutMs = InstrumentationRegistry.getArguments()
                    .getString("tel02_reward_close_timeout_ms")?.toLongOrNull()?.coerceIn(10_000, 300_000) ?: 180_000L
                assertTrue("Finish watching and close the real test reward before the manual phase expires",
                    callback.finished.await(closeTimeoutMs, TimeUnit.MILLISECONDS))
                assertTrue("No vendor show failure expected: ${callback.failures}", callback.failures.isEmpty())
                eventually("Original host resumes after real close") {
                    onMain { host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                }
                SystemClock.sleep(250)
                assertEquals(1, shown.size)
                assertTrue(showFailures.isEmpty())
                assertEquals(1, callback.items.size)
                assertTrue("Reward must be a real non-premium grant", callback.items.single() != null)
                assertEquals(1, callback.closedCount)
                // Google-served rewarded ads document earned before dismissed. This test does
                // not generalize that order to every mediation adapter or to paid telemetry.
                assertEquals(listOf("shown", "earned", "closed"), events.toList())
                Log.i(TAG, "CLOSED real_callback=true shown=1 earned=1 closed=1 reward=${callback.items.single()}")
            }
        } finally {
            instrumentation.runOnMainSync {
                Tracker.removeSink(sink)
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return checkNotNull(value)
    }

    private fun eventually(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }

    private class RecordingLoad : AdCallback() {
        val ads = CopyOnWriteArrayList<RewardedAd>()
        val errors = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        override fun onRewardAdLoaded(ad: RewardedAd) {
            ads += ad
            finished.countDown()
        }
        override fun onAdFailedToLoad(error: LoadAdError?) {
            errors += error?.toString() ?: "No real reward fill"
            finished.countDown()
        }
    }

    private class RecordingReward(private val events: CopyOnWriteArrayList<String>) : RewardCallback {
        val items = CopyOnWriteArrayList<RewardItem?>()
        val failures = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val closedCount get() = events.count { it == "closed" }
        override fun onUserEarnedReward(item: RewardItem?) {
            items += item
            events += "earned"
            Log.i(TAG, "EARNED count=${items.size} type=${item?.type} amount=${item?.amount}")
        }
        override fun onRewardedAdClosed() {
            events += "closed"
            finished.countDown()
        }
        override fun onRewardedAdFailedToShow(codeError: Int) {
            failures += codeError
            finished.countDown()
        }
        override fun onAdClicked() = Unit
    }

    companion object {
        private const val TAG = "TEL02_REWARD"
        private const val PLACEMENT = "tel02_device_real_reward"
        // https://developers.google.com/admob/android/rewarded
        private const val TEST_UNIT = "ca-app-pub-3940256099942544/5224354917"
    }
}
