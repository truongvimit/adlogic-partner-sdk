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
class RetentionUiTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun onePromptLeaseExpiresAndOldReleaseCannotAffectNewOwner() {
        val clock = TestClock()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ui = RetentionUiCoordinator(clock, ForegroundActivityProvider { activity }, { true }, { false })
        val first = (ui.acquire("review", 100) as RetentionUiLeaseResult.Acquired).lease
        assertSame(activity, first.activity())
        assertEquals(RetentionSuppressionReason.PROMPT_BUSY, (ui.acquire("feedback") as RetentionUiLeaseResult.Blocked).reason)
        // Elapsed time determines leases, so wall clock corrections do not release/revive one.
        clock.now -= 999_999
        assertTrue(first.isValid())
        clock.elapsed += 100
        assertFalse(first.isValid())
        val second = (ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
        first.close()
        assertTrue(second.isValid())
        second.close()
        assertEquals(RetentionEligibility.Allowed, ui.eligibility())
    }

    @Test fun lifecycleRemovesPausedOrDestroyedActivitiesAndInvalidatesLease() {
        val runtime = installed()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground)
        val activity = controller.get()
        assertSame(activity, runtime.activities.current())
        val lease = (runtime.ui.acquire("review") as RetentionUiLeaseResult.Acquired).lease
        controller.pause()
        assertNull(runtime.activities.current())
        assertFalse(lease.isValid())
        controller.stop().destroy()
        assertNull(lease.activity())
    }

    @Test fun boundedExternalAndHostUiSuppressionRevokesCurrentLeaseAndDoesNotLeakAcrossRestart() {
        val clock = TestClock()
        val store = testStore()
        val options = RetentionOptions(store = store, clock = clock,
            initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER))
        val runtime = installed(options)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        runtime.signal(RetentionSignal.ProcessForeground)
        val lease = (runtime.ui.acquire("widget") as RetentionUiLeaseResult.Acquired).lease
        runtime.signal(RetentionSignal.ExternalTransitionStarted("pin-123", "widget", 100))
        assertFalse(lease.isValid())
        assertEquals(RetentionSuppressionReason.EXTERNAL_TRANSITION, (runtime.marketingEligibility(0, false) as RetentionEligibility.Blocked).reason)
        clock.elapsed += 100
        assertEquals(RetentionEligibility.Allowed, runtime.marketingEligibility(0, false))
        runtime.signal(RetentionSignal.HostUiChanged("ads", true))
        assertEquals(RetentionSuppressionReason.HOST_UI, (runtime.ui.acquire("review") as RetentionUiLeaseResult.Blocked).reason)
        runtime.signal(RetentionSignal.HostUiChanged("ads", false))
        assertTrue(runtime.ui.acquire("review") is RetentionUiLeaseResult.Acquired)
        runtime.signal(RetentionSignal.ExternalTransitionStarted("permission", "permission"))
        RetentionRuntime.uninstallForTests()
        val restored = installed(options)
        assertEquals(RetentionEligibility.Allowed, restored.marketingEligibility(0, false))
        controller.pause().stop().destroy()
    }

    @Test fun onboardingPhaseRequiresScopedUnfinishedOnboardingAndSharesOtherGates() {
        val runtime = installed(RetentionOptions(store = testStore(), initialUserState = RetentionUserState(entitlement = RetentionEntitlement.NON_SUBSCRIBER)))
        fun gate() = runtime.marketingEligibility(0, phase = RetentionMarketingPhase.ONBOARDING)
        assertEquals(RetentionSuppressionReason.WRONG_PHASE, (gate() as RetentionEligibility.Blocked).reason)
        runtime.signal(RetentionSignal.OnboardingChanged(true))
        assertEquals(RetentionEligibility.Allowed, gate())
        assertEquals(RetentionSuppressionReason.SETUP_INCOMPLETE, (runtime.marketingEligibility(0) as RetentionEligibility.Blocked).reason)
        runtime.signal(RetentionSignal.ProcessForeground)
        assertEquals(RetentionSuppressionReason.FOREGROUND, (gate() as RetentionEligibility.Blocked).reason)
        runtime.signal(RetentionSignal.ProcessBackground)
        runtime.signal(RetentionSignal.ExternalTransitionStarted("permission", "permission"))
        assertEquals(RetentionSuppressionReason.EXTERNAL_TRANSITION, (gate() as RetentionEligibility.Blocked).reason)
        runtime.signal(RetentionSignal.ExternalTransitionFinished("permission"))
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.UNKNOWN))
        assertEquals(RetentionSuppressionReason.ENTITLEMENT_UNKNOWN, (gate() as RetentionEligibility.Blocked).reason)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        runtime.signal(RetentionSignal.SetupCompleted)
        assertEquals(RetentionSuppressionReason.WRONG_PHASE, (gate() as RetentionEligibility.Blocked).reason)
    }

    @Test fun noForegroundActivityAndOnboardingBlockPromptWithoutAcquiring() {
        val clock = TestClock()
        val absent = RetentionUiCoordinator(clock, ForegroundActivityProvider { null }, { true }, { false })
        assertEquals(RetentionSuppressionReason.NO_ACTIVITY, (absent.acquire("review") as RetentionUiLeaseResult.Blocked).reason)
        val background = RetentionUiCoordinator(clock, ForegroundActivityProvider { null }, { false }, { false })
        assertEquals(RetentionSuppressionReason.BACKGROUND, (background.acquire("review") as RetentionUiLeaseResult.Blocked).reason)
        val onboarding = RetentionUiCoordinator(clock, ForegroundActivityProvider { null }, { true }, { true })
        assertEquals(RetentionSuppressionReason.HOST_UI, (onboarding.acquire("review") as RetentionUiLeaseResult.Blocked).reason)
        assertThrows(IllegalArgumentException::class.java) { absent.acquire("review", 300001) }
    }
}
