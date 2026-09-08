package io.onboardkit.ads

import android.app.Application
import android.accessibilityservice.AccessibilityService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicReference
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ads.module.admob.AppOpenManager
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.Entitlement
import com.google.android.gms.ads.MobileAds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real foreground host; no ad-provider replacement or SDK test seam. */
class AppOpenResumeDeviceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "RES-01 / RES-02 live GMA load observation"
            setPadding(24, 64, 24, 24)
        })
    }
}

/**
 * One phase per fresh instrumentation process. Never calls show. Normal phases use Google's
 * official app-open test unit; failure uses a deliberately invalid, nonmonetized identifier.
 * That phase exercises real GMA load failure, not NO_FILL. A negative buffer assertion alone
 * cannot prove dispatch counts or retry intervals: correlate marked ticks with actual vendor
 * error, dispatch and backoff logs before recording QA.
 *
 * Offline phase initializes GMA while online, then logs WAITING_FOR_OFFLINE so root can disable
 * Wi-Fi/data and restore their recorded state after the test. This fixture changes no radios.
 */
@RunWith(AndroidJUnit4::class)
class AppOpenResumeLoadDeviceTest {
    @Test
    fun exerciseAndObserveSelectedLoadPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("resumePhase") ?: "allowed"
        require(phase in setOf("allowed", "disabled", "unauthorized", "burst", "release", "off", "offline", "failure")) {
            "resumePhase must be allowed, disabled, unauthorized, burst, release, off, offline, or failure"
        }
        val unit = if (phase == "failure") INVALID_APP_OPEN_QA_UNIT else APP_OPEN_TEST_UNIT
        val observeMs = args.getString("resumeObserveMs")?.toLong() ?: 45_000L
        require(observeMs in 5_000L..120_000L) { "resumeObserveMs must be 5000..120000" }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = AppOpenManager.getInstance()
        val initialized = CountDownLatch(1)
        var intervalStarted = false

        instrumentation.runOnMainSync {
            assertFalse("Run this class alone in a fresh process", manager.isInitialized)
            assertFalse("Fixture must not inherit a purchased host", Entitlement.isPremium(app))
            // Blank ID and disabled mode precede Activity creation, so its natural resume cannot
            // send an extra AppOpen request even against the unfixed implementation.
            manager.disableAppResume()
            manager.init(app, "")
            manager.enableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
            manager.releaseCachedAds()
            // Explicit host authorization for GMA initialization; selected phase is applied below.
            ConsentCenter.setHostConsent(canRequestAds = true, personalized = false)
            MobileAds.initialize(app) { initialized.countDown() }
        }

        try {
            assertTrue("Real MobileAds initialization did not finish", initialized.await(45, TimeUnit.SECONDS))
            ActivityScenario.launch<AppOpenResumeDeviceActivity>(
                Intent(app, AppOpenResumeDeviceActivity::class.java),
            ).use { scenario ->
                if (phase == "offline") {
                    mark(phase, "WAITING_FOR_OFFLINE root_must_disable_wifi_and_data; fixture_changes_no_radios")
                    val deadline = SystemClock.elapsedRealtime() + 45_000L
                    while (hasActiveNetwork(app) && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(100)
                    }
                    assertFalse("Disable Wi-Fi/data after the marker; an active network would not test the offline gate",
                        hasActiveNetwork(app))
                    mark(phase, "OFFLINE_ENVIRONMENT_CONFIRMED")
                }
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertFalse(activity.isDestroyed)
                    assertFalse("Fixture must start with an empty resume buffer", manager.isAdAvailable(false))
                    mark(phase, if (phase == "failure") "BEGIN unit=$unit spacedRetryProbes=20"
                        else "BEGIN unit=$unit windowMs=$observeMs")
                    intervalStarted = true
                    ConsentCenter.setHostConsent(canRequestAds = phase != "unauthorized", personalized = false)
                    // Enable and explicit fetches must not warm the buffer in the foreground.
                    manager.setAppResumeAdId(unit)
                    if (phase != "disabled") manager.enableAppResume()
                    // Same main-thread runnable: no queued GMA terminal can interleave the burst
                    // or the explicit release/off operation below.
                    val calls = if (phase == "burst") 3 else 1
                    repeat(calls) { manager.fetchAd(false) }
                    mark(phase, "FETCH_RETURNED explicitCalls=$calls (not a vendor-request count)")
                    assertFalse("Foreground fetch must not synchronously fill", manager.isAdAvailable(false))
                }
                SystemClock.sleep(1_000)
                assertFalse("Startup/foreground must not warm the buffer", isReady(manager))
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                val stopDeadline = SystemClock.elapsedRealtime() + 10_000L
                while (!processStopped() && SystemClock.elapsedRealtime() < stopDeadline) SystemClock.sleep(50)
                assertTrue("Physical Home must stop the process", processStopped())
                mark(phase, "PROCESS_BACKGROUND delayMs=2000")
                if (phase == "release" || phase == "off") {
                    // Invalidate the one background request after its dispatch opportunity.
                    SystemClock.sleep(2_100)
                    instrumentation.runOnMainSync {
                        if (phase == "release") manager.releaseCachedAds() else manager.disableAppResume()
                    }
                    mark(phase, "INVALIDATED_BACKGROUND_REQUEST")
                }

                if (phase == "allowed" || phase == "burst") {
                    val deadline = SystemClock.elapsedRealtime() + observeMs
                    var ready = isReady(manager)
                    while (!ready && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(100)
                        ready = isReady(manager)
                    }
                    mark(phase, "OBSERVED_BUFFER ready=$ready")
                    assertTrue("No real test fill within window; inspect GMA error/network logs before diagnosis", ready)
                    // A ready buffer should remain reusable. These API calls are not request counts.
                    instrumentation.runOnMainSync { repeat(3) { manager.fetchAd(false) } }
                    mark(phase, "READY_BUFFER_FETCH_RETURNED explicitCalls=3")
                    SystemClock.sleep(1_000)
                    assertTrue("No show/release was requested; ready buffer should remain", isReady(manager))
                    mark(phase, "OBSERVATION_COMPLETE positiveBuffer=true dispatchCount=external-evidence-required")
                } else if (phase == "failure") {
                    // These public fetch probes must not retry during this same background stay.
                    // Correlate the dispatch log to verify the one-opportunity contract.
                    repeat(20) { index ->
                        SystemClock.sleep(1_000)
                        instrumentation.runOnMainSync {
                            assertTrue("Failure backoff needs network; offline is a separate phase", hasActiveNetwork(app))
                            manager.fetchAd(false)
                            mark(phase, "RETRY_PROBE_RETURNED index=${index + 1} explicitCallsAfterInitial=${index + 1}")
                        }
                        assertFalse("A deliberately invalid QA unit must not produce a fill", isReady(manager))
                    }
                    SystemClock.sleep(1_000)
                    assertFalse(isReady(manager))
                    mark(phase, "OBSERVATION_COMPLETE emptyBuffer=true invalidUnitFailure_not_NO_FILL " +
                        "sameBackgroundRetries=0_verify_dispatch_logs probeCallsAfterInitial=20")
                } else {
                    val deadline = SystemClock.elapsedRealtime() + observeMs
                    do {
                        if (phase == "offline") {
                            assertFalse("Keep radios off through END_BEFORE_CLEANUP; restore afterwards", hasActiveNetwork(app))
                        }
                        val ready = isReady(manager)
                        if (ready) mark(phase, "UNEXPECTED_BUFFER ready=true")
                        assertFalse("Forbidden or invalidated request restored a ready buffer: phase=$phase", ready)
                        SystemClock.sleep(100)
                    } while (SystemClock.elapsedRealtime() < deadline)
                    mark(phase, "OBSERVATION_COMPLETE emptyBuffer=true networkAndLateSuccess=external-evidence-required")
                }
            }
        } finally {
            if (intervalStarted) mark(phase, "END_BEFORE_CLEANUP")
            instrumentation.runOnMainSync {
                manager.disableAppResume()
                manager.setAppResumeAdId("")
                manager.releaseCachedAds()
                manager.enableAppResumeWithActivity(AppOpenResumeDeviceActivity::class.java)
                ConsentCenter.clearHostConsent()
            }
        }
    }

    private fun processStopped(): Boolean {
        val stopped = AtomicReference(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            stopped.set(ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED)
        }
        return stopped.get()
    }

    private fun hasActiveNetwork(app: Application): Boolean =
        app.getSystemService(ConnectivityManager::class.java).activeNetwork != null

    private fun isReady(manager: AppOpenManager): Boolean {
        var ready = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ready = manager.isAdAvailable(false)
        }
        return ready
    }

    private fun mark(phase: String, message: String) {
        Log.i("P0_RES_DEVICE", "pid=${Process.myPid()} phase=$phase elapsed=${SystemClock.elapsedRealtime()} $message")
    }

    private companion object {
        // Verified 2026-09-06: https://developers.google.com/admob/android/app-open#always_test_with_test_ads
        const val APP_OPEN_TEST_UNIT = "ca-app-pub-3940256099942544/9257395921"
        // Intentionally malformed: cannot identify any publisher inventory or monetized ad unit.
        // Record GMA's real error text/code; never label this failure as NO_FILL.
        const val INVALID_APP_OPEN_QA_UNIT = "invalid-app-open-unit-for-resume-qa"
    }
}
