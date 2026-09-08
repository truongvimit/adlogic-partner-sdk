package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.interstitial.*
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.MobileAds
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real GMA, real process lifecycle, real closes. Run alone; close only at BUFFER_CLOSE_ALLOWED. */
@RunWith(AndroidJUnit4::class)
class InterstitialAutoBufferDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val requests = CopyOnWriteArrayList<Pair<String, Long>>()
    private val sink = object : TrackSink {
        override val id = "buffer-device-requests"
        override fun onEvent(name: String, params: Map<String, Any?>) {
            if (name == "ad_request" && params["ad_format"] == "interstitial") {
                requests += params["placement"].toString() to SystemClock.elapsedRealtime()
            }
        }
    }

    @Test
    fun contentActivationCacheAndSharedGateWithUnscopedOnboardingAd() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val initialized = CountDownLatch(1)
        onMain {
            Tracker.install(app)
            Tracker.addSink(sink)
            ConsentCenter.setHostConsent(true, false)
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            AppOpenManager.getInstance().disableAppResume()
            ERainAd.getInstance().setIntervalInterstitialAd(5)
            ERainAd.getInstance().setMaxClickAdsPerDay(0)
            ERainAd.getInstance().setCountClickToShowAds(1, 0)
            ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
            AdRemoteConfig.initializeFromJson("""{
                "inter_all":{"id":"$UNIT","isEnable":true},
                "inter_back":{"id":"$UNIT","isEnable":true}
            }""")
            InterstitialAutoBuffer.configure(InterstitialBufferOptions(placements = listOf(ALL, BACK)))
            MobileAds.initialize(app) { initialized.countDown() }
        }
        try {
            assertTrue(initialized.await(45, TimeUnit.SECONDS))
            assertTrue("Configuring during startup must not dispatch", requests.isEmpty())
            ActivityScenario.launch<InterstitialRealGmaDeviceActivity>(
                Intent(app, InterstitialRealGmaDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var host: InterstitialRealGmaDeviceActivity
                scenario.onActivity { host = it }
                eventually("Content host resumed") { onMain { host.lifecycle.currentState == Lifecycle.State.RESUMED } }
                val activatedAt = onMain {
                    InterstitialAutoBuffer.start(host)
                    SystemClock.elapsedRealtime()
                }
                onMain {
                    repeat(3) { InterstitialAutoBuffer.start(host); InterstitialAutoBuffer.topUpNow() }
                    // Outside the configured group: may load before its initial gate expires.
                    InterstitialAdManager.load(host, OB, listOf(UNIT))
                }
                SystemClock.sleep(1_000)
                assertFalse(requests.any { it.first == ALL || it.first == BACK })
                assertTrue("Onboarding is outside the initial group gate", requests.any { it.first == OB })
                eventually("Real fills for all three placements", 120_000) {
                    onMain { listOf(ALL, BACK, OB).all { InterstitialAdManager.isReady(it) } }
                }
                assertTrue(requests.filter { it.first in listOf(ALL, BACK) }.all { it.second - activatedAt >= 4_990 })
                val countWithCache = requests.size
                val allRequestsWithCache = requests.count { it.first == ALL }
                val backRequestsWithCache = requests.count { it.first == BACK }
                onMain { repeat(5) { InterstitialAutoBuffer.topUpNow() } }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                eventually("Process really backgrounded") {
                    onMain { ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED }
                }
                onMain { InterstitialAutoBuffer.topUpNow() }
                SystemClock.sleep(6_000)
                assertEquals("Background/cache must not buy replacements", countWithCache, requests.size)
                onMain { app.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == host.taskId }.moveToFront() }
                eventually("Same content returned") { onMain { host.lifecycle.currentState == Lifecycle.State.RESUMED } }
                SystemClock.sleep(1_000)
                assertEquals(countWithCache, requests.size)
                assertTrue(onMain { listOf(ALL, BACK, OB).all { InterstitialAdManager.isReady(it) } })
                Log.i(TAG, "CACHE_AND_FOREGROUND_VERIFIED requests=$countWithCache")

                onMain { ERainAd.getInstance().setIntervalInterstitialAd(30) }
                eventually("Remote interval permits the first group show", 40_000) {
                    onMain { InterstitialAdManager.canShow(host, ALL) }
                }
                val all = Outcome()
                onMain { InterstitialAdManager.show(host, ALL, all, nextAction = InterNextAction.AfterDismiss) }
                waitForAdAndClose(ALL, all)
                val groupClosedAt = all.closedAt.get()
                val denied = Outcome()
                onMain {
                    InterstitialAutoBuffer.topUpNow()
                    InterstitialAdManager.show(host, BACK, denied)
                }
                assertTrue(denied.completed.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(AdSkipReason.CAPPED_BY_MODULE), denied.skips.toList())
                assertTrue(onMain { InterstitialAdManager.isReady(BACK) })
                assertEquals("Close must not immediately refill", countWithCache, requests.size)
                val onboarding = Outcome()
                onMain { InterstitialAdManager.show(host, OB, onboarding, nextAction = InterNextAction.AfterDismiss) }
                waitForAdAndClose(OB, onboarding)
                assertTrue("Unscoped ad must actually show during the group interval", onboarding.visibleAt.get() - groupClosedAt < 30_000)
                val remaining = groupClosedAt + 31_000 - SystemClock.elapsedRealtime()
                if (remaining > 0) SystemClock.sleep(remaining)
                assertTrue("Fixture must observe before a hypothetical OB-based interval expires",
                    SystemClock.elapsedRealtime() < onboarding.closedAt.get() + 30_000)
                assertTrue("OB close must not restart the all/back clock", onMain { InterstitialAdManager.canShow(host, BACK) })
                eventually("Consumed ALL is replenished after the shared interval", 10_000) {
                    requests.count { it.first == ALL } > allRequestsWithCache
                }
                assertTrue("Refill cannot precede the 30-second dismissal gate",
                    // The public callback follows the internal gate stamp by a few milliseconds.
                    requests.filter { it.first == ALL }.drop(allRequestsWithCache).all { it.second - groupClosedAt >= 29_950 })
                assertTrue("Unused back ad stays cached", onMain { InterstitialAdManager.isReady(BACK) })
                assertEquals("Back cache is never replaced", backRequestsWithCache, requests.count { it.first == BACK })
                Log.i(TAG, "COMPLETE sharedGate=30s onboardingExcluded=true cachedBack=true")
            }
        } finally {
            onMain {
                InterstitialAutoBuffer.stop()
                InterstitialAutoBuffer.configure(InterstitialBufferOptions())
                InterstitialAdManager.releaseAll()
                Tracker.removeSink(sink)
                AppOpenManager.getInstance().disableAppResume()
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun waitForAdAndClose(placement: String, outcome: Outcome) {
        eventually("A real GMA ad must be visible for $placement", 15_000) {
            onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AdActivity && it.hasWindowFocus() } }
        }
        outcome.visibleAt.set(SystemClock.elapsedRealtime())
        // Leave enough separation to distinguish the group-close clock from an OB-close clock.
        if (placement == OB) SystemClock.sleep(3_000)
        Log.i(TAG, "BUFFER_CLOSE_ALLOWED placement=$placement")
        assertTrue("Close the real test ad through its UI", outcome.completed.await(90, TimeUnit.SECONDS))
        assertTrue(outcome.skips.isEmpty())
        assertTrue(outcome.closedAt.get() > 0)
    }
    private class Outcome : InterShowCallback() {
        val completed = CountDownLatch(1)
        val closedAt = AtomicLong()
        val visibleAt = AtomicLong()
        val skips = CopyOnWriteArrayList<AdSkipReason>()
        override fun onClosed() { closedAt.set(SystemClock.elapsedRealtime()) }
        override fun onSkipped(reason: AdSkipReason) { skips += reason }
        override fun onComplete() { completed.countDown() }
    }
    private fun <T> onMain(block: () -> T): T {
        val value = java.util.concurrent.atomic.AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun eventually(message: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && !predicate()) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
    private companion object {
        const val TAG = "BUFFER_DEVICE"
        const val ALL = "inter_all"
        const val BACK = "inter_back"
        const val OB = "inter_after_ob3"
        const val UNIT = "ca-app-pub-3940256099942544/1033173712"
    }
}
