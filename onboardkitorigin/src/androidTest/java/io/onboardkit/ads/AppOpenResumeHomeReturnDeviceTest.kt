package io.onboardkit.ads

import android.accessibilityservice.AccessibilityService
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
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One real Android Home/return flow, run alone in a fresh instrumentation process on an unlocked
 * device. The host emits the public click-suppression signal; this is not a real ad-click test.
 * Blank app-open unit + durable OPEN off avoid any ad request/show. No manager/vendor replacement.
 */
@RunWith(AndroidJUnit4::class)
class AppOpenResumeHomeReturnDeviceTest {
    @Test
    fun publicClickSignalIsSharedForOnePhysicalReturnAndGoneOnTheNextReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = AppOpenManager.getInstance()
        val round = AtomicInteger(0)
        val reads = CopyOnWriteArrayList<ReturnRead>()
        lateinit var host: AppOpenResumeDeviceActivity

        instrumentation.runOnMainSync {
            assertFalse("Run this class alone in a fresh process", manager.isInitialized)
            manager.disableAppResume()
            manager.init(app, "")
            manager.setResumeSkipPolicy(null)
            manager.setDisableAdResumeByClickAction(false)
            manager.setInterstitialShowing(false)
            ConsentCenter.setHostConsent(true, false)
        }

        fun reader(name: String) = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val number = round.get()
                if (number == 0) return // Ignore addObserver's initial lifecycle synchronization.
                val reason = manager.resumeSkipReasonFor(host)
                reads += ReturnRead(number, name, reason)
                mark("reader=$name round=$number reason=$reason")
            }
        }
        val firstReader = reader("first")
        val secondReader = reader("second")
        var observersAttached = false

        try {
            ActivityScenario.launch<AppOpenResumeDeviceActivity>(
                Intent(app, AppOpenResumeDeviceActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { host = it }
                eventually("Host and process must initially be resumed") {
                    scenario.state == Lifecycle.State.RESUMED && processState() == Lifecycle.State.RESUMED
                }
                instrumentation.runOnMainSync {
                    assertSame(host, manager.currentActivity)
                    assertNull("The fixture must have no unrelated policy denial", manager.resumeSkipReasonFor(host))
                    ProcessLifecycleOwner.get().lifecycle.addObserver(firstReader)
                    ProcessLifecycleOwner.get().lifecycle.addObserver(secondReader)
                    observersAttached = true
                    // The same public source signal used by an app before leaving after an ad click.
                    // It deliberately does not enable OPEN; the alternate welcome reader must still
                    // receive and consume this return while the OPEN mode is disabled.
                    manager.disableAdResumeByClickAction()
                    round.set(1)
                    mark("PUBLIC_CLICK_SIGNAL_SET")
                }

                fun physicalHomeAndReturn(number: Int, expectedReason: String?) {
                    mark("HOME round=$number")
                    assertTrue("Android Home action was rejected", instrumentation.uiAutomation.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME,
                    ))
                    // Wait for the actual process ON_STOP, including ProcessLifecycleOwner's delay.
                    // A fast pause/resume would not exercise the process-return contract.
                    eventually("Home must stop the host AND process") {
                        scenario.state == Lifecycle.State.CREATED && processState() == Lifecycle.State.CREATED
                    }
                    mark("PROCESS_STOPPED round=$number")
                    instrumentation.runOnMainSync {
                        app.getSystemService(ActivityManager::class.java).appTasks
                            .single { it.taskInfo.taskId == host.taskId }
                            .moveToFront()
                    }
                    eventually("The same task must return to a resumed host and process") {
                        scenario.state == Lifecycle.State.RESUMED && processState() == Lifecycle.State.RESUMED
                    }
                    eventually("Both synchronous process readers must observe this return") {
                        reads.count { it.round == number } >= 2
                    }
                    instrumentation.waitForIdleSync() // Also drains the posted return-snapshot clear.
                    scenario.onActivity { assertSame("Return must retain the existing host", host, it) }
                    val observed = reads.filter { it.round == number }
                    assertEquals("One onStart read per observer for this physical return", 2, observed.size)
                    assertEquals(mapOf("first" to expectedReason, "second" to expectedReason),
                        observed.associate { it.reader to it.reason })
                    instrumentation.runOnMainSync {
                        assertNull("The consumed snapshot must not block a later request in this foreground",
                            manager.resumeSkipReasonFor(host))
                    }
                    mark("RETURN_VERIFIED round=$number reason=$expectedReason snapshotCleared=true")
                }

                physicalHomeAndReturn(1, "returning_from_ad_click")
                round.set(2) // No second source signal; perform another actual Home/return.
                physicalHomeAndReturn(2, null)
                assertEquals(4, reads.size)
                mark("COMPLETE physicalReturns=2 synchronousReads=4")
            }
        } finally {
            round.set(0)
            instrumentation.runOnMainSync {
                if (observersAttached) {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(firstReader)
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(secondReader)
                }
                manager.disableAppResume()
                manager.setDisableAdResumeByClickAction(false)
                manager.setResumeSkipPolicy(null)
                manager.releaseCachedAds()
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun processState(): Lifecycle.State {
        val state = AtomicReference<Lifecycle.State>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            state.set(ProcessLifecycleOwner.get().lifecycle.currentState)
        }
        return state.get()
    }

    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }

    private fun mark(message: String) {
        Log.i("P0_RES_RETURN", "pid=${Process.myPid()} elapsed=${SystemClock.elapsedRealtime()} $message")
    }

    private data class ReturnRead(val round: Int, val reader: String, val reason: String?)
}
