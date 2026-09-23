package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.ads.module.config.ERainAdConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.reward.RewardAdManager
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import io.trackkit.TrackSink
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real network fill and real Android lifecycle; never replaces or invokes a GMA callback. */
@RunWith(AndroidJUnit4::class)
class RewardLateFillLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun homeBeforeRealFillCancelsPresentationAndReturnDoesNotAutoplay() = verifyLateFill(finishHost = false)

    @Test
    fun finishBeforeRealFillCancelsPresentationWithoutForegroundResurrection() = verifyLateFill(finishHost = true)

    private fun verifyLateFill(finishHost: Boolean) {
        val placement = "reward_late_fill_${if (finishHost) "finish" else "home"}"
        val initialized = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failures = AtomicInteger()
        val successes = AtomicInteger()
        val shown = AtomicInteger()
        val earnedEvents = AtomicInteger()
        val lifecycle = RecordingLifecycle()
        val load = RecordingLoad()
        val sink = object : TrackSink {
            override val id = placement
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (params["placement"] != placement) return
                if (name == TrackkitEvents.AD_SHOW) shown.incrementAndGet()
                if (name == TrackkitEvents.AD_REWARD_EARNED) earnedEvents.incrementAndGet()
            }
        }
        instrumentation.runOnMainSync {
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.init(app, ERainAdConfig(app).apply {
                facebookClientToken = "123456789"
                idAdResume = ""
            })
            AppOpenManager.disableAppResume()
            RewardAdManager.releaseAll()
            AdBehavior.document.acceptSuccessfulFetch(
                """{"rewarded":{"load":{"tier_timeout_ms":45000},"buffer":{"after_close":false}}}""",
            )
            MobileAds.initialize(app) { initialized.countDown() }
            Tracker.install(app)
            Tracker.setConsent(true, true)
            Tracker.addSink(sink)
            app.registerActivityLifecycleCallbacks(lifecycle)
        }
        try {
            assertTrue("Real GMA initialization must finish", initialized.await(60, TimeUnit.SECONDS))
            ActivityScenario.launch<InterstitialRealGmaDeviceActivity>(
                Intent(app, InterstitialRealGmaDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var host: InterstitialRealGmaDeviceActivity
                scenario.onActivity { host = it; lifecycle.host = it }
                eventually("Host must have resumed with window focus before the explicit reward request") {
                    onMain {
                        host.hasWindowFocus() && host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                instrumentation.runOnMainSync {
                    assertFalse("This test requires a fresh vendor request", RewardAdManager.isReady(placement))
                    RewardAdManager.loadAndShow(
                        host, placement, listOf(TEST_UNIT), tierTimeoutMs = 45_000,
                        onSuccess = Runnable { successes.incrementAndGet() },
                        onFailed = Runnable {
                            failures.incrementAndGet()
                            failed.countDown()
                            Log.i(TAG, "$placement CANCELLED failures=${failures.get()}")
                        },
                    )
                    assertEquals("A request-time gate rejection would not exercise lifecycle cancellation", 0, failures.get())
                    // Join the same request to observe its real late fill independently of the cancelled UI intent.
                    RewardAdManager.load(app, placement, listOf(TEST_UNIT), tierTimeoutMs = 45_000, listener = load)
                    lifecycle.backgroundExpected.set(true)
                    if (finishHost) host.finish()
                }
                if (!finishHost) {
                    assertTrue("Android must accept actual Home", instrumentation.uiAutomation.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME,
                    ))
                }
                eventually("The original host must really stop before observing the late fill", 10_000) {
                    lifecycle.stoppedAt.get() > 0 && onMain {
                        !host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            (!finishHost || host.isDestroyed)
                    }
                }
                assertTrue("Leaving the pending host must fail the presentation once", failed.await(10, TimeUnit.SECONDS))
                assertEquals(1, failures.get())
                assertTrue("Real Google rewarded load must settle", load.finished.await(50, TimeUnit.SECONDS))
                assertTrue("A real fill is required; no-fill is not a pass: ${load.errors}", load.errors.isEmpty())
                assertEquals(1, load.ads.size)
                assertTrue("Fixture requires fill AFTER actual host stop: stop=${lifecycle.stoppedAt.get()} fill=${load.loadedAt.get()}",
                    load.loadedAt.get() >= lifecycle.stoppedAt.get())
                assertTrue("Cancelled presentation must leave the shared late fill cached", onMain {
                    RewardAdManager.isReady(placement)
                })
                eventually("No SDK Activity may bring this process back to the foreground") {
                    onMain { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                }
                assertNoAutoplayFor(3_000, placement, shown, earnedEvents, successes, failures, lifecycle)
                assertTrue("No Activity may resume unexpectedly after leaving the host: ${lifecycle.unexpectedResumes}",
                    lifecycle.unexpectedResumes.isEmpty())
                Log.i(TAG, "$placement LATE_FILL_CACHED stopped_before_fill=true shown=0 success=0 failures=1")

                if (!finishHost) {
                    instrumentation.runOnMainSync {
                        lifecycle.backgroundExpected.set(false)
                        app.getSystemService(ActivityManager::class.java).appTasks
                            .single { it.taskInfo.taskId == host.taskId }.moveToFront()
                    }
                    eventually("The explicit task return must resume the original host with focus") {
                        onMain { host.hasWindowFocus() && host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    }
                    assertNoAutoplayFor(3_000, placement, shown, earnedEvents, successes, failures, lifecycle)
                    Log.i(TAG, "$placement RETURN_NO_AUTOPLAY cache_ready=true shown=0 success=0 failures=1")
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                app.unregisterActivityLifecycleCallbacks(lifecycle)
                Tracker.removeSink(sink)
                RewardAdManager.release(placement)
                ConsentCenter.clearHostConsent()
                AdBehavior.document.acceptSuccessfulFetch("{}")
            }
        }
    }

    private fun assertNoAutoplayFor(
        durationMs: Long,
        placement: String,
        shown: AtomicInteger,
        earnedEvents: AtomicInteger,
        successes: AtomicInteger,
        failures: AtomicInteger,
        lifecycle: RecordingLifecycle,
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        do {
            assertEquals("No real GMA AdActivity may have resumed", 0, lifecycle.adResumes.get())
            assertEquals("No rewarded show telemetry", 0, shown.get())
            assertEquals("No reward-earned telemetry", 0, earnedEvents.get())
            assertEquals("No earned-and-closed success may arrive after cancellation", 0, successes.get())
            assertEquals("Cancellation remains terminal exactly once", 1, failures.get())
            assertTrue("Return/late callbacks must not consume the cached fill", onMain { RewardAdManager.isReady(placement) })
            assertFalse("A real GMA Activity must not be visible", onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is AdActivity }
            })
            if (lifecycle.backgroundExpected.get()) {
                assertTrue("Background app must remain background: ${lifecycle.unexpectedResumes}", lifecycle.unexpectedResumes.isEmpty())
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return checkNotNull(result)
    }

    private fun eventually(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }

    private class RecordingLoad : AdCallback() {
        val ads = CopyOnWriteArrayList<RewardedAd>()
        val errors = CopyOnWriteArrayList<String>()
        val loadedAt = AtomicLong()
        val finished = CountDownLatch(1)
        override fun onRewardAdLoaded(ad: RewardedAd?) {
            loadedAt.set(SystemClock.elapsedRealtime())
            if (ad == null) errors += "Null rewarded fill" else ads += ad
            finished.countDown()
        }
        override fun onAdFailedToLoad(error: LoadAdError?) {
            errors += error?.toString() ?: "No rewarded fill"
            finished.countDown()
        }
    }

    private class RecordingLifecycle : Application.ActivityLifecycleCallbacks {
        var host: Activity? = null
        val backgroundExpected = AtomicBoolean(false)
        val stoppedAt = AtomicLong()
        val adResumes = AtomicInteger()
        val unexpectedResumes = CopyOnWriteArrayList<String>()
        override fun onActivityResumed(activity: Activity) {
            if (activity is AdActivity) adResumes.incrementAndGet()
            if (backgroundExpected.get()) unexpectedResumes += activity.javaClass.name
        }
        override fun onActivityStopped(activity: Activity) {
            if (activity === host) stoppedAt.compareAndSet(0, SystemClock.elapsedRealtime())
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val TAG = "REWARD_LATE_FILL"
        const val TEST_UNIT = "ca-app-pub-3940256099942544/5224354917"
    }
}
