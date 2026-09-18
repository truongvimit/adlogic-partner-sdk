package io.onboardkit.ui.splash

import io.onboardkit.remote.OnboardingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the splash's bottom slot from being shown and buried in the same breath.
 *
 * The window runs from the later of the slot loading and the notification dialog closing, since
 * both have to be true before anyone can look at the ad. Vendor impressions are deliberately not
 * consulted — a collapsible banner never reports one, and waiting on it would be waiting for
 * something that may never arrive.
 */
class SplashSlotVisibilityTest {

    /**
     * `awaitSlotVisible`'s arithmetic, in the one form where all of its exits can be read at once.
     * Elapsed values are "how long ago", so the smaller one is the later moment.
     */
    private fun holdMs(
        minVisibleMs: Long,
        budgetLeftMs: Long,
        filled: Boolean,
        loadedElapsedMs: Long?,
        focusedElapsedMs: Long? = null,
    ): Long {
        if (minVisibleMs <= 0) return 0
        // A slot that never filled settles its latch without a load and is not waited on.
        if (!filled || loadedElapsedMs == null) return 0
        val onScreenFor = minOf(loadedElapsedMs, focusedElapsedMs ?: loadedElapsedMs)
        return minOf(minVisibleMs - onScreenFor, budgetLeftMs).coerceAtLeast(0)
    }

    @Test fun `a slot that failed never delays the interstitial`() {
        assertEquals(0, holdMs(1000, 60_000, filled = false, loadedElapsedMs = null))
    }

    @Test fun `a banner already up and the dialog long gone is not held`() {
        assertEquals(0, holdMs(1000, 60_000, filled = true, loadedElapsedMs = 5_000, focusedElapsedMs = 5_000))
    }

    @Test fun `time behind the permission dialog does not count`() {
        // The banner filled six seconds ago but the dialog only closed 100ms ago, so the user has
        // had it for 100ms, not six seconds.
        assertEquals(900, holdMs(1000, 60_000, filled = true, loadedElapsedMs = 6_000, focusedElapsedMs = 100))
    }

    @Test fun `a slot that loads after the dialog is measured from its load`() {
        // Dialog closed 400ms ago, the ad only arrived 100ms ago; the later moment wins.
        assertEquals(900, holdMs(1000, 60_000, filled = true, loadedElapsedMs = 100, focusedElapsedMs = 400))
    }

    @Test fun `the shared ad budget always wins`() {
        assertEquals(200, holdMs(1000, budgetLeftMs = 200, filled = true, loadedElapsedMs = 0, focusedElapsedMs = 0))
        assertEquals(0, holdMs(1000, budgetLeftMs = 0, filled = true, loadedElapsedMs = 0, focusedElapsedMs = 0))
    }

    @Test fun `zero restores the old behaviour of never holding`() {
        assertEquals(0, holdMs(0, 60_000, filled = true, loadedElapsedMs = 0, focusedElapsedMs = 0))
    }

    @Test fun `the shipped default protects the slot rather than leaving it unguarded`() {
        val default = OnboardingSettings.defaultNumber("splash.timing.slot_min_visible_ms")
        assertTrue("a 0 default would ship the very behaviour this replaced", default > 0)
        assertEquals(1000L, default)
    }
}
