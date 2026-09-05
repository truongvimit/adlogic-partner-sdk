package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fresh process, online/unlocked device. No SDK/vendor replacement and no fake ad callback.
 * A real automatic app-open must remain open until P0_RES_SHOW says CLOSE_ALLOWED. The test
 * performs Home/return itself; the operator uses the real ad's Close UI only after that marker.
 */
@RunWith(AndroidJUnit4::class)
class AppOpenResumePresentationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = AppOpenManager.getInstance()

    @Test
    fun automaticRealAdRemainsOwnedPast90SecondsAndRealCloseReleasesIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val initialized = CountDownLatch(1)
        val shown = AtomicInteger()
        val closed = AtomicInteger()
        val failures = CopyOnWriteArrayList<String>()
        val starts = AtomicInteger()
        val reads = CopyOnWriteArrayList<String>()
        lateinit var host: AppOpenResumeDeviceActivity
        var captureReturn = false
        var observerAttached = false
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                starts.incrementAndGet()
                if (captureReturn) reads += manager.resumeSkipReasonFor(host) ?: "eligible"
            }
        }
        val closeWaitMs = InstrumentationRegistry.getArguments()
            .getString("resumeCloseWaitMs")?.toLong() ?: 180_000L
        require(closeWaitMs in 30_000L..300_000L)
        onMain {
            assertFalse("Run this class alone in a fresh instrumentation process", manager.isInitialized)
            assertFalse("The real test host must not be premium", Entitlement.isPremium(app))
            manager.disableAppResume()
            manager.init(app, "")
            manager.setResumeSkipPolicy(null)
            manager.setInterstitialShowing(false)
            manager.setDisableAdResumeByClickAction(false)
            manager.disableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
            ConsentCenter.setHostConsent(true, false)
            manager.setEnableScreenContentCallback(true)
            manager.setFullScreenContentCallback(object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    mark("ACTUAL_SHOWN count=${shown.incrementAndGet()} busy=${manager.isShowingAd}")
                }
                override fun onAdDismissedFullScreenContent() {
                    // The callback reads production state before any test changes it.
                    assertFalse("Actual close must release ownership before the partner callback", manager.isShowingAd)
                    mark("ACTUAL_CLOSED count=${closed.incrementAndGet()} busy=${manager.isShowingAd}")
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    failures += error.toString()
                    mark("ACTUAL_SHOW_FAILED $error")
                }
            })
            MobileAds.initialize(app) { initialized.countDown() }
        }
        try {
            assertTrue("Real GMA initialization must finish", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<AppOpenResumeDeviceActivity>(
                Intent(app, AppOpenResumeDeviceActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { host = it }
                eventually("Real host and process must initially be RESUMED") {
                    scenario.state == Lifecycle.State.RESUMED && processState() == Lifecycle.State.RESUMED
                }
                onMain {
                    assertSame(host, manager.currentActivity)
                    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                    observerAttached = true
                    manager.setAppResumeAdId(TEST_UNIT)
                    manager.enableAppResume()
                    manager.fetchAd(false)
                }
                eventually("A real app-open fill is required; inspect GMA errors if unavailable", 60_000L) {
                    onMain { manager.isAdAvailable(false) }
                }
                assertEquals(0, shown.get())
                onMain { manager.enableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java) }
                mark("FILLED AUTOMATIC_HOME_BEGIN; no direct show call")
                physicalHome()
                returnTask(app, host)
                eventually("Automatic Home return must show a real focused GMA Activity", 20_000L) {
                    shown.get() == 1 && resumedAdActivity() != null
                }
                assertTrue(failures.isEmpty())
                val actualAd = resumedAdActivity()!!
                val holdUntil = SystemClock.elapsedRealtime() + 95_000L
                mark("HOLD_REAL_AD_OPEN durationMs=95000; do not close or click")
                while (SystemClock.elapsedRealtime() < holdUntil) {
                    assertEquals("This observation requires the actual ad to stay open", 0, closed.get())
                    assertTrue("The SDK must remain busy while that actual ad has not ended", onMain { manager.isShowingAd })
                    assertEquals(1, shown.get())
                    assertTrue(failures.isEmpty())
                    SystemClock.sleep(250)
                }
                mark("PAST_90_SECONDS busy=true; HOME_WITH_REAL_AD_OPEN")
                physicalHome()
                returnTask(app, host)
                eventually("Returning the existing task must restore its still-open GMA ad") {
                    resumedAdActivity() != null
                }
                assertSame("The real ad must remain the same presentation", actualAd, resumedAdActivity())
                assertEquals("Returning while a real ad owns the screen must not start a second one", 1, shown.get())
                assertEquals(0, closed.get())
                assertTrue(onMain { manager.isShowingAd })
                val beforeOverlayCloseStarts = starts.get()
                onMain {
                    // Explicit public source signal while real AdActivity is on top. This is NOT
                    // an assertion that a vendor click happened, and it never changes entry mode.
                    manager.disableAdResumeByClickAction()
                }
                mark("PUBLIC_CLICK_SIGNAL_WHILE_AD_OPEN; CLOSE_ALLOWED use actual ad Close UI now")
                eventually("Operator must close the actual ad using its real UI", closeWaitMs) {
                    closed.get() == 1 && scenario.state == Lifecycle.State.RESUMED
                }
                instrumentation.waitForIdleSync()
                assertTrue(failures.isEmpty())
                assertEquals(1, shown.get())
                assertEquals(1, closed.get())
                assertEquals("Ad overlay close must not need a new process ON_START", beforeOverlayCloseStarts, starts.get())
                onMain {
                    assertFalse(manager.isShowingAd)
                    assertSame(host, manager.currentActivity)
                    assertNull("The overlay host return must consume its public signal", manager.resumeSkipReasonFor(host))
                    // This final policy observation does not request a second real ad. Durable
                    // OPEN off still allows a host-return snapshot to be read and consumed.
                    manager.disableAppResume()
                    captureReturn = true
                }
                physicalHome()
                returnTask(app, host)
                eventually("Final policy-only host return must resume") { scenario.state == Lifecycle.State.RESUMED }
                instrumentation.waitForIdleSync()
                assertEquals(listOf("eligible"), reads.toList())
                assertEquals(1, shown.get())
                mark("COMPLETE actualShows=1 actualCloses=1 ownerReleased=true nextReturn=eligible")
            }
        } finally {
            onMain {
                if (observerAttached) ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
                manager.disableAppResume()
                manager.setEnableScreenContentCallback(false)
                manager.removeFullScreenContentCallback()
                manager.setAppResumeAdId("")
                manager.releaseCachedAds()
                manager.setDisableAdResumeByClickAction(false)
                manager.enableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun physicalHome() {
        assertTrue("Android Home action was rejected", instrumentation.uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_HOME,
        ))
        eventually("Home must actually stop the process including lifecycle debounce") {
            processState() == Lifecycle.State.CREATED
        }
    }

    private fun returnTask(app: Application, host: Activity) {
        onMain {
            app.getSystemService(ActivityManager::class.java).appTasks
                .single { it.taskInfo.taskId == host.taskId }.moveToFront()
        }
    }

    private fun resumedAdActivity(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is AdActivity && !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
    }

    private fun processState() = onMain { ProcessLifecycleOwner.get().lifecycle.currentState }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun eventually(message: String, timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }

    private fun mark(message: String) {
        Log.i("P0_RES_SHOW", "pid=${Process.myPid()} elapsed=${SystemClock.elapsedRealtime()} $message")
    }

    private companion object {
        // Google's official app-open test unit, already verified for the RES01 device fixture.
        const val TEST_UNIT = "ca-app-pub-3940256099942544/9257395921"
    }
}
