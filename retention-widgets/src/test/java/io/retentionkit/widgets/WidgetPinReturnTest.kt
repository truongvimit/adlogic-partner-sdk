package io.retentionkit.widgets

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPinReturnTest {
    @After fun after() { RetentionRuntime.uninstallForTests(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    // Inspect Android's actual registrations, so missing unregisters cannot be hidden by a closed flag.
    private fun callbacks(app: Application): List<Application.ActivityLifecycleCallbacks> =
        ReflectionHelpers.getField<ArrayList<Application.ActivityLifecycleCallbacks>>(app, "mActivityLifecycleCallbacks").toList()

    @Test fun transparentLauncherReturnWithoutProcessStopReleasesExactlyItsObserverAndToken() {
        val f = Fixture()
        val host = f.foreground()
        val baseline = callbacks(f.app).size
        val signals = mutableListOf<RetentionSignal>()
        f.runtime.subscribe("evidence") { signals.add(it) }
        val request = f.module.requestPin() as WidgetPinResult.Requested
        assertEquals(baseline + 1, callbacks(f.app).size)
        f.runtime.signal(RetentionSignal.ExternalTransitionStarted("other", "permission", 10_000))
        host.pause().resume() // No stop/start: this matches the transparent launcher Cancel path.
        assertEquals(WidgetPinResult.Requested(request.token), f.module.pinStatus(request.token))
        idle()
        assertFalse(signals.contains(RetentionSignal.ProcessBackground))
        assertEquals(WidgetPinResult.Unknown(request.token, "returned_without_callback"), f.module.pinStatus(request.token))
        assertEquals(baseline, callbacks(f.app).size)
        assertEquals(1, signals.count { it == RetentionSignal.ExternalTransitionFinished("widgets.pin:${request.token}") })
        assertTrue(f.runtime.ui.eligibility() is RetentionEligibility.Blocked)
        f.runtime.signal(RetentionSignal.ExternalTransitionFinished("other"))
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
    }

    @Test fun initialResumeAndAnotherActivityCannotPretendTheLauncherReturned() {
        val f = Fixture()
        val host = f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        callbacks(f.app).forEach { it.onActivityResumed(host.get()) }
        idle()
        assertEquals(WidgetPinResult.Requested(request.token), f.module.pinStatus(request.token))
        host.pause()
        val other = f.foreground()
        idle()
        assertEquals(WidgetPinResult.Requested(request.token), f.module.pinStatus(request.token))
        other.pause().stop().destroy()
        host.resume()
        idle()
        assertEquals(WidgetPinResult.Unknown(request.token, "returned_without_callback"), f.module.pinStatus(request.token))
    }

    @Test fun confirmationBeforeDeferredReturnWinsWithoutUnknownOrDuplicateFinish() {
        val f = Fixture()
        val host = f.foreground()
        val baseline = callbacks(f.app).size
        val finishes = mutableListOf<String>()
        f.runtime.subscribe("finish") { if (it is RetentionSignal.ExternalTransitionFinished) finishes.add(it.token) }
        val request = f.module.requestPin() as WidgetPinResult.Requested
        host.pause().resume()
        f.installWidget(77)
        f.module.receivePin(request.token, 77)
        idle()
        assertEquals(WidgetPinResult.Confirmed(request.token, 77), f.module.pinStatus(request.token))
        assertTrue(f.events.none { it.name == "retention_widget_pin_unknown" })
        assertEquals(listOf("widgets.pin:${request.token}"), finishes)
        assertEquals(baseline, callbacks(f.app).size)
    }

    @Test fun lateVerifiedCallbackDoesNotCloseTheNextRequestsObserverOrTransition() {
        val f = Fixture()
        val host = f.foreground()
        val baseline = callbacks(f.app).size
        val old = f.module.requestPin() as WidgetPinResult.Requested
        host.pause().resume(); idle()
        val next = f.module.requestPin() as WidgetPinResult.Requested
        f.installWidget(11)
        f.module.receivePin(old.token, 11)
        assertEquals(WidgetPinResult.Confirmed(old.token, 11), f.module.pinStatus(old.token))
        assertEquals(WidgetPinResult.Requested(next.token), f.module.pinStatus(next.token))
        assertEquals(baseline + 1, callbacks(f.app).size)
        assertEquals(RetentionSuppressionReason.EXTERNAL_TRANSITION, (f.runtime.ui.eligibility() as RetentionEligibility.Blocked).reason)
        f.installWidget(12)
        f.module.receivePin(next.token, 12)
        assertEquals(baseline, callbacks(f.app).size)
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
    }

    @Test fun sourceDestroyReleasesObserverAndOnlyReportsUnknownThenAcceptsLateProof() {
        val f = Fixture()
        val host = f.foreground()
        val baseline = callbacks(f.app).size
        val request = f.module.requestPin() as WidgetPinResult.Requested
        host.pause().stop().destroy()
        idle()
        assertEquals(baseline, callbacks(f.app).size)
        assertEquals(WidgetPinResult.Unknown(request.token, "source_destroyed"), f.module.pinStatus(request.token))
        f.installWidget(13)
        f.module.receivePin(request.token, 13)
        assertEquals(WidgetPinResult.Confirmed(request.token, 13), f.module.pinStatus(request.token))
    }

    @Test fun timeoutDisableAndShutdownRemoveRealRegistrationsAndQueuedReturnWork() {
        listOf("timeout", "disable", "shutdown").forEach { terminal ->
            RetentionRuntime.uninstallForTests()
            val f = Fixture(initialOverrides = mapOf("widgets.pin.timeout_ms" to "1000"))
            val host = f.foreground()
            val baseline = callbacks(f.app).size
            f.module.requestPin()
            host.pause().resume()
            when (terminal) {
                "timeout" -> { f.clock.advance(1001); f.module.refresh() }
                "disable" -> f.runtime.updateConfig(mapOf("widgets.enabled" to "false"))
                else -> f.module.shutdown()
            }
            val eventCount = f.events.size
            idle()
            assertEquals(terminal, baseline, callbacks(f.app).size)
            assertEquals(terminal, eventCount, f.events.size)
            assertEquals(terminal, RetentionEligibility.Allowed, f.runtime.ui.eligibility())
        }
    }

    @Test fun failedReturnReadOrWriteNeverRetainsObserverAndDoesNotClaimAPersistedOutcome() {
        listOf("read", "write").forEach { failure ->
            RetentionRuntime.uninstallForTests()
            val store = FailingPinStore()
            val f = Fixture(store = store)
            val host = f.foreground()
            val baseline = callbacks(f.app).size
            val request = f.module.requestPin() as WidgetPinResult.Requested
            store.failRead = failure == "read"
            store.failWrite = failure == "write"
            host.pause().resume(); idle()
            assertEquals(baseline, callbacks(f.app).size)
            assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
            assertTrue(f.events.none { it.name == "retention_widget_pin_unknown" })
            store.failRead = false; store.failWrite = false
            assertEquals(WidgetPinResult.Requested(request.token), f.module.pinStatus(request.token))
            f.installWidget(15)
            f.module.receivePin(request.token, 15)
            assertEquals(WidgetPinResult.Confirmed(request.token, 15), f.module.pinStatus(request.token))
        }
    }

    @Test fun timeoutConfirmationAndPlatformFailureReleaseObserverDespiteStorageOutage() {
        listOf("timeout_read", "confirmation_write", "platform_write", "disable_read").forEach { failure ->
            RetentionRuntime.uninstallForTests()
            val store = FailingPinStore()
            val f = Fixture(store = store, initialOverrides = mapOf("widgets.pin.timeout_ms" to "1000"))
            f.foreground()
            val baseline = callbacks(f.app).size
            if (failure == "platform_write") {
                f.platform.onRequest = { store.failWrite = true }
                f.platform.fail = true
                assertTrue(f.module.requestPin() is WidgetPinResult.Failed)
            } else {
                val request = f.module.requestPin() as WidgetPinResult.Requested
                when (failure) {
                    "timeout_read" -> {
                        store.failRead = true
                        f.clock.advance(1001)
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
                    }
                    "confirmation_write" -> { store.failWrite = true; f.installWidget(18); f.module.receivePin(request.token, 18) }
                    else -> { store.failRead = true; f.runtime.updateConfig(mapOf("widgets.enabled" to "false")) }
                }
            }
            assertEquals(failure, baseline, callbacks(f.app).size)
            assertEquals(failure, RetentionEligibility.Allowed, f.runtime.ui.eligibility())
            assertTrue(f.events.none { it.name == "retention_widget_pin_confirmed" })
        }
    }

    private class FailingPinStore : RetentionStore {
        private val delegate = SharedPreferencesRetentionStore(ApplicationProvider.getApplicationContext(), "pin_failure_${UUID.randomUUID()}")
        var failRead = false
        var failWrite = false
        override fun snapshot(namespace: String): RetentionState {
            if (namespace == "widgets.pins" && failRead) throw RetentionStorageException("Unavailable pin snapshot")
            return delegate.snapshot(namespace)
        }
        override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
            if (namespace == "widgets.pins" && failWrite) throw RetentionStorageException("Unavailable pin write")
            return delegate.transaction(namespace, block)
        }
    }
}
