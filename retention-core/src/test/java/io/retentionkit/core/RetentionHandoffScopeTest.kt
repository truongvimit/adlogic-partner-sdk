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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionHandoffScopeTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun registeredBeforeStartedObserverCanCloseWithoutResurrectingScope() {
        val rt = installed()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        val owners = mutableMapOf<String, RetentionHandoffScope>()
        var closed = 0
        val scope = RetentionHandoffScope(rt, host.get(), "test.scope", "test", onClosed = { owners.remove("test.scope"); closed++ })
        owners[scope.token] = scope
        rt.subscribe("test.close") { if (it is RetentionSignal.ExternalTransitionStarted) owners[it.token]?.close() }
        scope.start()
        assertTrue(owners.isEmpty())
        var launched = false
        scope.dispatch { launched = it != null }
        assertFalse(launched)
        scope.start(); scope.close()
        assertEquals(1, closed)
        assertEquals(RetentionEligibility.Allowed, rt.ui.eligibility())
        host.pause().stop().destroy()
    }

    @Test fun onlySourceReturnClosesAndForeignOwnerSurvives() {
        val rt = installed()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        var closed = 0
        val scope = RetentionHandoffScope(rt, host.get(), "test.return", "test", onClosed = { closed++ })
        scope.start()
        scope.onActivityResumed(host.get())
        assertNotNull(scope.activity()) // An initial resume without departure is not a return.
        val unrelated = Robolectric.buildActivity(Activity::class.java).create()
        scope.onActivityPaused(unrelated.get()); scope.onActivityResumed(unrelated.get())
        assertEquals(0, closed)
        unrelated.destroy()
        rt.signal(RetentionSignal.ExternalTransitionStarted("foreign", "host"))
        host.pause().resume()
        assertEquals(1, closed)
        assertTrue(rt.ui.eligibility() is RetentionEligibility.Blocked)
        rt.signal(RetentionSignal.ExternalTransitionFinished("foreign"))
        assertEquals(RetentionEligibility.Allowed, rt.ui.eligibility())
        host.pause().stop().destroy()
    }

    @Test fun timeoutAndSourceDestroyCloseExactlyOnce() {
        val clock = TestClock()
        val rt = installed(RetentionOptions(store = testStore(), clock = clock))
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        var closed = 0
        val scope = RetentionHandoffScope(rt, host.get(), "test.timeout", "test", 100, { closed++ })
        scope.start()
        clock.advance(100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertNull(scope.activity())
        host.pause().stop().destroy(); scope.close()
        assertEquals(1, closed)
    }

    @Test fun deferredContinuationWaitsForAllQueuedObserversAndClosedScopeCannotLaunch() {
        val rt = installed()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        var calls = 0
        var permitted = true
        lateinit var scope: RetentionHandoffScope
        rt.subscribe("test.create") { signal ->
            if (signal is RetentionSignal.BusinessSuccess) {
                scope = RetentionHandoffScope(rt, host.get(), "test.queued", "test")
                scope.start()
                scope.dispatch { calls++; permitted = it != null }
                assertEquals(0, calls)
            }
        }
        rt.subscribe("test.cancel") { if (it is RetentionSignal.ExternalTransitionStarted) scope.close() }
        rt.signal(RetentionSignal.BusinessSuccess("tool", "one"))
        assertEquals(1, calls)
        assertFalse(permitted)
        assertEquals(RetentionEligibility.Allowed, rt.ui.eligibility())
        host.pause().stop().destroy()
    }

    @Test fun postHandoffHostGateCanReenterAndStillRejectsChangedState() {
        var safe = true
        lateinit var rt: RetentionRuntime
        rt = installed(RetentionOptions(store = testStore(), uiHost = object : RetentionUiHost {
            override fun canPresent(activity: Activity): Boolean {
                if (!safe) rt.signal(RetentionSignal.HostUiChanged("paywall", true))
                return true
            }
        }))
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        val scope = RetentionHandoffScope(rt, host.get(), "test.gate", "test")
        scope.start(); safe = false
        assertNull(scope.activity())
        scope.close()
        assertTrue(rt.ui.eligibility() is RetentionEligibility.Blocked)
        host.pause().stop().destroy()
    }

    @Test fun continuationQueueIsBoundedAndShutdownDiscardsQueuedWork() {
        val rt = installed()
        var calls = 0
        rt.subscribe("test.queue") {
            repeat(128) { assertTrue(rt.afterSignalDispatch { calls++ }) }
            assertFalse(rt.afterSignalDispatch { calls++ })
            RetentionRuntime.uninstallForTests()
        }
        rt.signal(RetentionSignal.BusinessSuccess("tool", "shutdown"))
        assertEquals(0, calls)
    }

    @Test fun throwingCloseCallbackCannotLeaveAnExternalBlock() {
        val rt = installed()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        val scope = RetentionHandoffScope(rt, host.get(), "test.bad_close", "test", onClosed = { error("host callback") })
        scope.start(); scope.close(); scope.close()
        assertEquals(RetentionEligibility.Allowed, rt.ui.eligibility())
        assertEquals(1, rt.diagnostics.snapshot().count { it.component == "core.handoff.close" })
        host.pause().stop().destroy()
    }
}
