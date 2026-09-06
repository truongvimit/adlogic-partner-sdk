package io.retentionkit.core

import android.app.Activity
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionUiHostTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun hostGateRechecksAsyncBusyStateAndClosesOnlyItsOwnResource() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var busy = false
        val closed = mutableListOf<String>()
        val host = object : RetentionUiHost {
            override fun canPresent(activity: Activity) = !busy
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long) = AutoCloseable { closed.add(token) }
        }
        val ui = RetentionUiCoordinator(TestClock(), ForegroundActivityProvider { activity }, { true }, { false }, host)
        val first = (ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
        busy = true
        assertNull(first.activity())
        assertEquals(listOf(first.token), closed)
        assertEquals(RetentionSuppressionReason.HOST_UI, (ui.acquire("review") as RetentionUiLeaseResult.Blocked).reason)
        busy = false
        val second = (ui.acquire("review") as RetentionUiLeaseResult.Acquired).lease
        first.close()
        assertTrue(second.isValid())
        second.close(); second.close()
        assertEquals(listOf(first.token, second.token), closed)
    }

    @Test fun adapterFailuresDenySafelyAndCloseFailureCannotLeaveReservationLocked() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var gateFails = true
        var acquireFails = false
        val errors = mutableListOf<Exception>()
        val host = object : RetentionUiHost {
            override fun canPresent(activity: Activity): Boolean {
                if (gateFails) throw IllegalStateException("gate")
                return true
            }
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable {
                if (acquireFails) throw IllegalStateException("acquire")
                return AutoCloseable { throw IllegalStateException("close") }
            }
        }
        val ui = RetentionUiCoordinator(TestClock(), ForegroundActivityProvider { activity }, { true }, { false }, host, errors::add)
        assertTrue(ui.acquire("widget") is RetentionUiLeaseResult.Blocked)
        gateFails = false; acquireFails = true
        assertTrue(ui.acquire("widget") is RetentionUiLeaseResult.Blocked)
        acquireFails = false
        val lease = (ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
        lease.close()
        assertEquals(RetentionEligibility.Allowed, ui.eligibility())
        assertEquals(3, errors.size)
    }

    @Test fun acquisitionAndReleaseCallbacksRunOutsideCoordinatorLockEvenWhenTheyReenterFromAnotherThread() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var ui: RetentionUiCoordinator
        val executor = Executors.newSingleThreadExecutor()
        val host = object : RetentionUiHost {
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable {
                // If this callback held the UI lock, this bounded cross-thread query would deadlock.
                val nested = executor.submit<Boolean> { ui.externalTransitionActive() }
                assertFalse(nested.get(1, TimeUnit.SECONDS))
                return AutoCloseable { executor.submit { ui.removeBlock("unused") }.get(1, TimeUnit.SECONDS) }
            }
        }
        try {
            ui = RetentionUiCoordinator(TestClock(), ForegroundActivityProvider { activity }, { true }, { false }, host)
            val lease = (ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
            lease.close()
            assertEquals(RetentionEligibility.Allowed, ui.eligibility())
        } finally { executor.shutdownNow() }
    }

    @Test fun invalidationDuringAdapterAcquisitionClosesReturnedResourceAndNeverPublishesLease() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var ui: RetentionUiCoordinator
        var closes = 0
        val host = object : RetentionUiHost {
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable {
                ui.setBlock("paywall", RetentionSuppressionReason.HOST_UI, 1000)
                return AutoCloseable { closes++ }
            }
        }
        ui = RetentionUiCoordinator(TestClock(), ForegroundActivityProvider { activity }, { true }, { false }, host)
        assertTrue(ui.acquire("widget") is RetentionUiLeaseResult.Blocked)
        assertEquals(1, closes)
    }

    @Test fun resourceExpiresWithoutAnyFurtherReadsAndPauseShutdownReleaseExactlyOnce() {
        var closes = 0
        val host = object : RetentionUiHost {
            override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long) = AutoCloseable { closes++ }
        }
        val runtime = installed(RetentionOptions(store = testStore(), uiHost = host))
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground)
        val first = (runtime.ui.acquire("widget", 1000) as RetentionUiLeaseResult.Acquired).lease
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        assertEquals(1, closes)
        assertFalse(first.isValid())
        runtime.ui.acquire("review")
        controller.pause()
        assertEquals(2, closes)
        controller.resume()
        runtime.ui.acquire("widget")
        RetentionRuntime.uninstallForTests()
        assertEquals(3, closes)
        first.close()
        assertEquals(3, closes)
        controller.pause().stop().destroy()
    }
}
