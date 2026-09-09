package io.onboardkit.ads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import com.ads.module.helper.interstitial.InterstitialBufferOptions
import com.ads.module.config.ERainAdConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import com.ads.module.helper.interstitial.InterLoadAndShowOptions
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
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
 * Run this class alone on an awake, unlocked, online device in a fresh instrumentation process.
 * The 50ms UI deadline must beat a real fill; an earlier fill is an explicit fixture timing miss.
 * After INTER_CONTENT_DEVICE AD_ACTIVITY_VISIBLE, close the actual Google test ad using its real UI.
 * Exercises the opted-in content policy with the existing manifest host and real GMA.
 * The deterministic 5s clamp, lead/retry and boundary cases live in InterstitialContentPolicyTest.
 */
@RunWith(AndroidJUnit4::class)
class InterstitialContentPolicyDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun optedColdTimeoutRetainsRealFillThenReadyShowsAndResetsActionGuard() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val adResumes = AtomicInteger()
        val observer = ActivityLifecycleCallback { activity, stage ->
            if (activity is AdActivity && stage == Stage.RESUMED) adResumes.incrementAndGet()
        }
        val initialized = CountDownLatch(1)
        instrumentation.runOnMainSync {
            monitor.addLifecycleCallback(observer)
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = false
            })
            ERainAd.getInstance().init(app, ERainAdConfig(app).apply {
                setFacebookClientToken("123456789")
                setIdAdResume("")
            })
            ERainAd.getInstance().setIntervalInterstitialAd(0)
            ERainAd.getInstance().setMaxClickAdsPerDay(0)
            ERainAd.getInstance().setCountClickToShowAds(1, 0)
            ERainAd.getInstance().setOpenActivityAfterShowInterAds(false)
            AppOpenManager.getInstance().disableAppResume()
            MobileAds.initialize(app) { initialized.countDown() }
        }
        try {
            assertTrue("Real GMA initialization must finish", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<InterstitialRealGmaDeviceActivity>(
                Intent(app, InterstitialRealGmaDeviceActivity::class.java),
            ).use { scenario ->
                lateinit var host: InterstitialRealGmaDeviceActivity
                scenario.onActivity { host = it }
                eventually("Host and process must be resumed") {
                    onMain {
                        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
                val expired = RecordingShow()
                val realFill = RecordingLoad()
                val triggerAt = AtomicLong()
                instrumentation.runOnMainSync {
                    assertFalse("New placement starts without a fill", InterstitialAdManager.isReady(PLACEMENT))
                    AdRemoteConfig.initializeFromJson("""{"inter_all":{"id":"$TEST_UNIT","isEnable":true}}""")
                    InterstitialAutoBuffer.configure(InterstitialBufferOptions(
                        independentIntervalPlacements = setOf(PLACEMENT),
                        placements = listOf(PLACEMENT),
                        tapThresholds = mapOf(PLACEMENT to 2),
                        intervalMsByPlacement = mapOf(PLACEMENT to 0L),
                    ))
                    InterstitialAutoBuffer.start(host)
                    val firstAction = RecordingShow()
                    InterstitialAdManager.loadAndShow(host, PLACEMENT, listOf(TEST_UNIT), firstAction,
                        InterLoadAndShowOptions(allowWaitForAutoBuffer = true))
                    assertEquals(listOf("capped_by_module"), firstAction.skips.toList())
                    assertFalse(InterstitialAdManager.isLoading(PLACEMENT))
                    triggerAt.set(SystemClock.elapsedRealtime())
                    InterstitialAdManager.loadAndShow(host, PLACEMENT, listOf(TEST_UNIT), expired,
                        InterLoadAndShowOptions(allowWaitForAutoBuffer = true, timeoutMs = 50))
                    // Public load joins the request just started above and observes its real result.
                    // It must not replace the temporary UI waiter or start a second request.
                    assertTrue(InterstitialAdManager.isLoading(PLACEMENT))
                    InterstitialAdManager.load(host, PLACEMENT, listOf(TEST_UNIT), listener = realFill)
                }
                assertTrue("The 50ms UI wait must finish before a real ad is presented; an earlier fill misses this fixture window",
                    expired.finished.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("not_ready"), expired.skips.toList())
                assertEquals(1, expired.completed.get())
                assertEquals(0, expired.committed.get())
                assertTrue("The request must still be pending at UI timeout, not already no-fill",
                    expired.loadingAtCompletion)
                assertTrue(expired.completedAt.get() - triggerAt.get() >= 50)
                assertEquals(0, adResumes.get())
                Log.i(TAG, "UI_TIMEOUT complete=1 network_still_pending=true elapsed_ms=${expired.completedAt.get() - triggerAt.get()}")

                assertTrue("The real outstanding request must settle", realFill.finished.await(40, TimeUnit.SECONDS))
                assertEquals("This real late-fill case needs a successful GMA fill: ${realFill.errors}", 1, realFill.ads.size)
                assertTrue("Physical fill must arrive after the UI deadline", realFill.loadedAt.get() > expired.completedAt.get())
                val original = realFill.ads.single()
                val raw = onMain { checkNotNull(original.interstitialAd) }
                SystemClock.sleep(1_000)
                assertTrue(onMain { InterstitialAdManager.isReady(PLACEMENT) })
                assertEquals("Late fill cannot replay the expired trigger", 0, adResumes.get())
                assertEquals(0, expired.committed.get())
                assertEquals(1, expired.completed.get())
                assertSame(raw, onMain { checkNotNull(original.interstitialAd) })
                Log.i(TAG, "LATE_FILL_BUFFERED no_auto_show=true wrapper=${System.identityHashCode(original)} raw=${System.identityHashCode(raw)}")

                val retry = RecordingShow()
                instrumentation.runOnMainSync {
                    assertSame(raw, original.interstitialAd)
                    // Default options use the ready fast path and the existing 800ms presentation.
                    InterstitialAdManager.loadAndShow(host, PLACEMENT, listOf(TEST_UNIT), retry,
                        InterLoadAndShowOptions(allowWaitForAutoBuffer = true))
                }
                eventually("Explicit ready trigger must open a focused, visible real AdActivity", 15_000) {
                    onMain {
                        monitor.getActivitiesInStage(Stage.RESUMED).any {
                            it is AdActivity && it.window.decorView.isShown && it.hasWindowFocus()
                        }
                    }
                }
                assertEquals("Default AfterDismiss waits for the real close", 0, retry.completed.get())
                assertTrue(retry.skips.isEmpty())
                assertEquals(1, expired.completed.get())
                Log.i(TAG, "AD_ACTIVITY_VISIBLE same_late_fill=true; close the real test ad using its Close UI")
                val closeTimeout = InstrumentationRegistry.getArguments()
                    .getString("int01_close_timeout_ms")?.toLongOrNull()?.coerceIn(5_000, 120_000) ?: 90_000L
                assertTrue("Close the real test interstitial before the manual phase expires",
                    retry.finished.await(closeTimeout, TimeUnit.MILLISECONDS))
                eventually("Real dismissal returns the original host") {
                    onMain { host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                }
                SystemClock.sleep(250)
                assertEquals(1, retry.closed.get())
                assertEquals(1, retry.completed.get())
                assertTrue(retry.skips.isEmpty())
                assertEquals(1, expired.completed.get())
                assertFalse(onMain { InterstitialAdManager.isReady(PLACEMENT) })
                assertFalse(onMain { original.isReady })
                val afterShow = RecordingShow()
                instrumentation.runOnMainSync {
                    InterstitialAdManager.loadAndShow(host, PLACEMENT, listOf(TEST_UNIT), afterShow,
                        InterLoadAndShowOptions(allowWaitForAutoBuffer = true))
                }
                assertEquals(listOf("capped_by_module"), afterShow.skips.toList())
                assertEquals(1, afterShow.completed.get())
                assertEquals(1, adResumes.get())
                Log.i(TAG, "CLOSED real_callback=true complete_once=true first_action_after_show_capped=true")
            }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(observer)
                InterstitialAutoBuffer.stop()
                InterstitialAutoBuffer.configure(InterstitialBufferOptions())
                InterstitialAdManager.release(PLACEMENT)
                AdRemoteConfig.reset()
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
        val ads = CopyOnWriteArrayList<ApInterstitialAd>()
        val errors = CopyOnWriteArrayList<String>()
        val loadedAt = AtomicLong()
        val finished = CountDownLatch(1)
        override fun onApInterstitialLoad(ad: ApInterstitialAd?) {
            loadedAt.set(SystemClock.elapsedRealtime())
            if (ad == null) errors += "Null fill" else ads += ad
            finished.countDown()
        }
        override fun onAdFailedToLoad(error: LoadAdError?) {
            errors += error?.toString() ?: "Request failed without a GMA error"
            finished.countDown()
        }
    }

    private class RecordingShow : InterShowCallback() {
        val committed = AtomicInteger()
        val closed = AtomicInteger()
        val completed = AtomicInteger()
        val completedAt = AtomicLong()
        val skips = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        @Volatile var loadingAtCompletion = false
        override fun onShowed() { committed.incrementAndGet() }
        override fun onClosed() { closed.incrementAndGet() }
        override fun onSkipped(reason: AdSkipReason) { skips += reason.key }
        override fun onComplete() {
            loadingAtCompletion = InterstitialAdManager.isLoading(PLACEMENT)
            completedAt.set(SystemClock.elapsedRealtime())
            completed.incrementAndGet()
            finished.countDown()
        }
    }

    companion object {
        private const val TAG = "INTER_CONTENT_DEVICE"
        private const val PLACEMENT = "inter_all"
        private const val TEST_UNIT = "ca-app-pub-3940256099942544/1033173712"
    }
}
