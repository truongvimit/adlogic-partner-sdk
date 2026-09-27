package io.onboardkit.ui.splash

import io.onboardkit.remote.OnboardingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashSlotVisibilityTest {
    private val now = 100_000L

    @Test fun `a banner already up and the dialog long gone is not held`() {
        assertEquals(0, slotHoldMs(1000, slotLoadedAtMs = now - 5_000, focusedAtMs = now - 5_000, budgetLeftMs = 60_000, nowMs = now))
    }

    @Test fun `time behind the permission dialog does not count`() {
        assertEquals(900, slotHoldMs(1000, slotLoadedAtMs = now - 6_000, focusedAtMs = now - 100, budgetLeftMs = 60_000, nowMs = now))
    }

    @Test fun `a slot that loads after the dialog is measured from its load`() {
        assertEquals(900, slotHoldMs(1000, slotLoadedAtMs = now - 100, focusedAtMs = now - 400, budgetLeftMs = 60_000, nowMs = now))
    }

    @Test fun `the shared ad budget always wins`() {
        assertEquals(200, slotHoldMs(1000, slotLoadedAtMs = now, focusedAtMs = now, budgetLeftMs = 200, nowMs = now))
        assertEquals(0, slotHoldMs(1000, slotLoadedAtMs = now, focusedAtMs = now, budgetLeftMs = 0, nowMs = now))
    }

    @Test fun `zero restores the old behaviour of never holding`() {
        assertEquals(0, slotHoldMs(0, slotLoadedAtMs = now, focusedAtMs = now, budgetLeftMs = 60_000, nowMs = now))
    }

    @Test fun `a silent slot waits out the budget while the interstitial has not loaded`() {
        assertEquals(42_000, silentSlotWaitMs(42_000, interLoadedAtMs = null, promptAnsweredAtMs = null, waitAfterInterMs = 10_000, nowMs = now))
    }

    @Test fun `a silent slot gets the wait counted from the interstitial load`() {
        assertEquals(7_000, silentSlotWaitMs(60_000, interLoadedAtMs = now - 3_000, promptAnsweredAtMs = null, waitAfterInterMs = 10_000, nowMs = now))
        assertEquals(0, silentSlotWaitMs(60_000, interLoadedAtMs = now - 12_000, promptAnsweredAtMs = null, waitAfterInterMs = 10_000, nowMs = now))
    }

    @Test fun `a prompt answered after the load restarts the silent slot's wait`() {
        assertEquals(9_000, silentSlotWaitMs(60_000, interLoadedAtMs = now - 8_000, promptAnsweredAtMs = now - 1_000, waitAfterInterMs = 10_000, nowMs = now))
        assertEquals(7_000, silentSlotWaitMs(60_000, interLoadedAtMs = now - 3_000, promptAnsweredAtMs = now - 20_000, waitAfterInterMs = 10_000, nowMs = now))
    }

    @Test fun `the budget also caps a silent slot's wait`() {
        assertEquals(2_000, silentSlotWaitMs(2_000, interLoadedAtMs = now - 3_000, promptAnsweredAtMs = null, waitAfterInterMs = 10_000, nowMs = now))
    }

    @Test fun `the shipped default protects the slot rather than leaving it unguarded`() {
        val default = OnboardingSettings.defaultNumber("splash.timing.slot_min_visible_ms")
        assertTrue("a 0 default would ship the very behaviour this replaced", default > 0)
        assertEquals(1000L, default)
    }

    @Test fun `a silent slot gets ten seconds after the interstitial loads`() {
        assertEquals(10_000L, OnboardingSettings.defaultNumber("splash.timing.slot_wait_after_inter_ms"))
    }
}
