package io.retentionkit.core

import android.app.Activity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionEntryPurposeTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun explicitEntryUsesOwnGateThroughLeaseAndHandoffWhilePromptsRemainBlocked() {
        var allowEntry = true
        val host = object : RetentionUiHost {
            override fun canPresent(activity: Activity) = false
            override fun canPresentEntry(activity: Activity) = allowEntry
        }
        val rt = installed(RetentionOptions(store = testStore(), uiHost = host))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        assertTrue(rt.ui.acquire("review") is RetentionUiLeaseResult.Blocked)
        val lease = (rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) as RetentionUiLeaseResult.Acquired).lease
        assertSame(activity.get(), lease.activity())
        val scope = RetentionHandoffScope.forEntry(rt, activity.get(), "explicit.entry", "entry")
        scope.start()
        assertNull(lease.activity()) // The scope intentionally revokes the reservation.
        assertSame(activity.get(), scope.activity())
        allowEntry = false
        assertNull(scope.activity()) // Purpose applies to the final recheck as well.
        scope.close()
        allowEntry = true
        activity.pause()
        assertTrue(rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) is RetentionUiLeaseResult.Blocked)
        activity.stop().destroy()
    }

    @Test fun entryGateCannotOverrideReentrantBackgroundOrFinishedActivity() {
        lateinit var rt: RetentionRuntime
        var reenter = false
        val host = object : RetentionUiHost {
            override fun canPresentEntry(activity: Activity): Boolean {
                if (reenter) rt.signal(RetentionSignal.ProcessBackground)
                return true
            }
        }
        rt = installed(RetentionOptions(store = testStore(), uiHost = host))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        rt.signal(RetentionSignal.ProcessForeground)
        reenter = true
        assertTrue(rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) is RetentionUiLeaseResult.Blocked)
        reenter = false
        rt.signal(RetentionSignal.ProcessForeground)
        activity.get().finish()
        assertTrue(rt.ui.acquire("entry", 5000, RetentionUiPurpose.ENTRY) is RetentionUiLeaseResult.Blocked)
        activity.pause().stop().destroy()
    }

    @Test fun genericSplashRouterStartsCleanTaskAndRuntimePreservesEnvelope() {
        val rt = installed(RetentionOptions(store = testStore(), router = RetentionSplashRouter(Activity::class.java)))
        val entry = RetentionEntry(RetentionEntrySource.WIDGET, "notes", "open_notes", mode = RetentionEntryMode.REUSABLE)
        val intent = requireNotNull(rt.createEntryIntent(entry))
        assertEquals(Activity::class.java.name, intent.component!!.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertEquals(entry, (RetentionEntryCodec.read(intent) as RetentionEntryDecodeResult.Valid).entry)
    }
}
