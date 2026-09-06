package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterLoadOptions
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import io.trackkit.TrackSink
import io.trackkit.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Run alone in a fresh instrumentation process on an awake, unlocked, online device.
 * Real ERain -> waterfall -> GMA test interstitial. No vendor callback is replaced or invoked.
 *
 * After INT02_DEVICE AD_ACTIVITY_VISIBLE, close the displayed test ad with its real Close UI.
 * The test waits 90 seconds by default; -e int02_close_timeout_ms can shorten that manual phase.
 * The legacy onShowed callback is only a commit marker; the screen assertion uses Android's
 * RESUMED AdActivity, visible decor and window focus instead.
 */
@RunWith(AndroidJUnit4::class)
class InterstitialRealGmaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun homeInsidePreparationKeepsTheSameRealFillForExplicitRetry() {
        val shownEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        val sink = object : TrackSink {
            override val id = "int02-tel02-device"
            override fun onEvent(name: String, params: Map<String, Any?>) {
                if (name == "ad_show" && params["placement"] == PLACEMENT) {
                    shownEvents += params.toMap()
                    Log.i(TAG, "TEL02_VENDOR_SHOW count=${shownEvents.size}")
                }
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val adResumes = AtomicInteger()
        val hostPausedAt = AtomicLong()
        val lifecycleObserver = ActivityLifecycleCallback { activity, stage ->
            if (activity is InterstitialRealGmaDeviceActivity && stage == Stage.PAUSED) {
                hostPausedAt.compareAndSet(0, SystemClock.elapsedRealtime())
            }
            if (activity is AdActivity && stage == Stage.RESUMED) adResumes.incrementAndGet()
        }
        val initialized = CountDownLatch(1)
        instrumentation.runOnMainSync {
            monitor.addLifecycleCallback(lifecycleObserver)
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                // Existing sample placeholders satisfy ERain's unconditional Facebook bootstrap.
                // Test manifest disables Facebook auto-init, automatic events and ID collection.
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            ERainAd.getInstance().setIntervalInterstitialAd(0)
            ERainAd.getInstance().setMaxClickAdsPerDay(0)
            ERainAd.getInstance().setCountClickToShowAds(1, 0)
            ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
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
                scenario.onActivity { host = it }
                eventually("Host and process must be resumed before loading") {
                    onMain {
                        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                val firstLoad = RecordingRealLoad()
                instrumentation.runOnMainSync {
                    InterstitialAdManager.load(host, PLACEMENT, listOf(TEST_UNIT),
                        InterLoadOptions(tierTimeoutMs = 30_000), firstLoad)
                }
                assertTrue("Real test interstitial must settle", firstLoad.finished.await(40, TimeUnit.SECONDS))
                assertEquals("A real fill is required; GMA error: ${firstLoad.errors}", 1, firstLoad.ads.size)
                val original = firstLoad.ads.single()
                val raw = onMain { checkNotNull(original.interstitialAd) }
                assertTrue(onMain { InterstitialAdManager.isReady(PLACEMENT) })
                Log.i(TAG, "FILLED wrapper=${System.identityHashCode(original)} raw=${System.identityHashCode(raw)}")

                val rejected = RecordingRealShow()
                val showStartedAt = AtomicLong()
                instrumentation.runOnMainSync {
                    hostPausedAt.set(0)
                    showStartedAt.set(SystemClock.elapsedRealtime())
                    InterstitialAdManager.show(host, PLACEMENT, rejected,
                        nextAction = InterNextAction.AfterDismiss)
                }
                // Return immediately after show schedules its existing 800ms preparation.
                assertTrue("Android must accept Home", instrumentation.uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME,
                ))
                assertTrue("The background attempt must complete", rejected.finished.await(10, TimeUnit.SECONDS))
                val timeToPause = hostPausedAt.get() - showStartedAt.get()
                assertTrue("Fixture must pause before 800ms; actual ${timeToPause}ms",
                    hostPausedAt.get() > 0 && timeToPause in 0L until 800L)
                assertEquals(listOf("show_in_background"), rejected.skips.toList())
                assertEquals(1, rejected.completed.get())
                assertEquals(0, rejected.closed.get())
                assertEquals("Early onShowed is not a vendor presentation", 0, adResumes.get())
                assertEquals("Background rejection must never count as shown", 0, shownEvents.size)
                assertTrue("Unshown fill must still be ready", onMain { InterstitialAdManager.isReady(PLACEMENT) })
                assertSame(raw, onMain { checkNotNull(original.interstitialAd) })

                val cacheHit = RecordingRealLoad()
                instrumentation.runOnMainSync {
                    // This public load synchronously returns the restored cache entry.
                    InterstitialAdManager.load(host, PLACEMENT, listOf(TEST_UNIT), listener = cacheHit)
                }
                assertEquals("Cache hit returns without another asynchronous vendor fill", 0L, cacheHit.finished.count)
                assertEquals(1, cacheHit.ads.size)
                assertTrue(cacheHit.errors.isEmpty())
                assertSame(original, cacheHit.ads.single())
                assertSame(raw, onMain { checkNotNull(cacheHit.ads.single().interstitialAd) })
                Log.i(TAG, "BACKGROUND_REJECTED same_wrapper=true same_raw=true pause_ms=$timeToPause complete=1")

                instrumentation.runOnMainSync {
                    app.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == host.taskId }
                        .moveToFront()
                }
                eventually("Return the existing task to the foreground") {
                    onMain {
                        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                scenario.onActivity { assertSame("Return keeps the original host", host, it) }
                // More than the old preparation delay: return alone must never replay the show.
                SystemClock.sleep(1_000)
                assertEquals("No automatic show after return", 0, adResumes.get())
                assertTrue(onMain { InterstitialAdManager.isReady(PLACEMENT) })
                val retried = RecordingRealShow()
                instrumentation.runOnMainSync {
                    assertSame(raw, original.interstitialAd)
                    InterstitialAdManager.show(host, PLACEMENT, retried,
                        nextAction = InterNextAction.AfterDismiss)
                }
                eventually("Explicit retry must open a visible, focused real GMA AdActivity", 15_000) {
                    onMain {
                        monitor.getActivitiesInStage(Stage.RESUMED).any {
                            it is AdActivity && it.window.decorView.isShown && it.hasWindowFocus()
                        }
                    }
                }
                assertTrue("Physical AdActivity resume must be observed", adResumes.get() >= 1)
                eventually("The real vendor display must report one ad_show") { shownEvents.size == 1 }
                assertEquals("interstitial", shownEvents.single()["ad_format"])
                assertEquals("AfterDismiss waits for the real close", 0, retried.completed.get())
                assertTrue(retried.skips.isEmpty())
                Log.i(TAG, "AD_ACTIVITY_VISIBLE same_fill=true; close the displayed test ad using its real Close UI")
                val closeTimeoutMs = InstrumentationRegistry.getArguments()
                    .getString("int02_close_timeout_ms")?.toLongOrNull()?.coerceIn(5_000, 120_000) ?: 90_000L
                assertTrue("Close the real GMA test ad before the manual phase expires",
                    retried.finished.await(closeTimeoutMs, TimeUnit.MILLISECONDS))
                eventually("The original host resumes after real dismissal") {
                    onMain { host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                }
                SystemClock.sleep(250)
                assertEquals(1, retried.closed.get())
                assertEquals(1, retried.completed.get())
                assertEquals("Shown and impression callbacks must not double-count", 1, shownEvents.size)
                assertTrue(retried.skips.isEmpty())
                assertFalse(onMain { InterstitialAdManager.isReady(PLACEMENT) })
                assertFalse(onMain { original.isReady })
                assertEquals("The rejected attempt cannot complete again", 1, rejected.completed.get())
                Log.i(TAG, "CLOSED real_callback=true complete=1 cache_empty=true")
            }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(lifecycleObserver)
                Tracker.removeSink(sink)
                InterstitialAdManager.release(PLACEMENT)
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

    private class RecordingRealLoad : AdCallback() {
        val ads = CopyOnWriteArrayList<ApInterstitialAd>()
        val errors = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        override fun onApInterstitialLoad(ad: ApInterstitialAd?) {
            if (ad == null) errors += "Null real fill" else ads += ad
            finished.countDown()
        }
        override fun onAdFailedToLoad(error: LoadAdError?) {
            errors += error?.toString() ?: "Manager rejected load before GMA dispatch"
            finished.countDown()
        }
    }

    private class RecordingRealShow : InterShowCallback() {
        val closed = AtomicInteger()
        val completed = AtomicInteger()
        val skips = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        override fun onClosed() { closed.incrementAndGet() }
        override fun onSkipped(reason: AdSkipReason) { skips += reason.key }
        override fun onComplete() {
            completed.incrementAndGet()
            finished.countDown()
        }
    }

    companion object {
        private const val TAG = "INT02_DEVICE"
        private const val PLACEMENT = "int02_device_real_interstitial"
        // Google's official Android interstitial test unit, never a monetized partner unit.
        private const val TEST_UNIT = "ca-app-pub-3940256099942544/1033173712"
    }
}

/** A test-APK host using the same public AppCompatActivity contract as a partner Activity. */
class InterstitialRealGmaDeviceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = "INT-02 real GMA host\nThe test opens Home, returns this task, then retries the same test ad."
        })
    }
}
