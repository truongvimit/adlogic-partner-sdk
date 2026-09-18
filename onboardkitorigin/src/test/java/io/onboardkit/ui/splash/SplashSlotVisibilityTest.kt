package io.onboardkit.ui.splash

import io.onboardkit.remote.OnboardingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the splash's bottom slot from being shown and buried in the same breath.
 *
 * Waiting for the slot to *load* was never the same as the user seeing it: an ad that fills behind
 * the notification dialog, or lands just as that dialog closes, satisfied the old rule and was then
 * covered by the interstitial immediately. The wait is now measured from the impression.
 *
 * The three properties below are the ones worth pinning, because each of them is a way the flow
 * could stall on an ad that is never coming.
 */
class SplashSlotVisibilityTest {

    /**
     * `awaitSlotVisible`'s arithmetic, in the one form where all of its exits can be read at once.
     * Returns how long the interstitial is held back.
     */
    private fun holdMs(
        minVisibleMs: Long,
        budgetLeftMs: Long,
        filled: Boolean,
        shownElapsedMs: Long?,
        focusedElapsedMs: Long? = null,
    ): Long {
        if (minVisibleMs <= 0) return 0
        // A slot that never filled resolves its latch at once and is not waited on.
        if (!filled) return 0
        val shown = shownElapsedMs ?: return minOf(minVisibleMs, budgetLeftMs) // waited, never shown
        // The later of the two: time behind the permission dialog does not count as seen.
        val seenFor = minOf(shown, focusedElapsedMs ?: shown)
        return minOf(minVisibleMs - seenFor, budgetLeftMs).coerceAtLeast(0)
    }

    @Test fun `a slot that failed never delays the interstitial`() {
        // The case that matters most: no fill, no impression, and the flow must not pay for it.
        assertEquals(0, holdMs(minVisibleMs = 1000, budgetLeftMs = 60_000, filled = false, shownElapsedMs = null))
    }

    @Test fun `a slot already seen long enough on a clear screen is not held`() {
        // Shown and focused five seconds ago: the user has had it in front of them.
        assertEquals(0, holdMs(1000, 60_000, filled = true, shownElapsedMs = 5_000, focusedElapsedMs = 5_000))
    }

    @Test fun `time behind the permission dialog does not count as seen`() {
        // The banner rendered under the dialog six seconds ago, but the dialog only closed 100ms
        // ago — the user has had 100ms with it, not six seconds, so it is still owed its window.
        assertEquals(900, holdMs(1000, 60_000, filled = true, shownElapsedMs = 6_000, focusedElapsedMs = 100))
    }

    @Test fun `a native binding after the dismissal is measured from its own impression`() {
        // Focus came back 400ms ago but the native only bound 100ms ago; the later one wins.
        assertEquals(900, holdMs(1000, 60_000, filled = true, shownElapsedMs = 100, focusedElapsedMs = 400))
    }



    @Test fun `a filled slot that never reports an impression gives up after the same budget`() {
        // Collapsible banners never report one, so this may not be an open-ended wait.
        assertEquals(1000, holdMs(1000, 60_000, filled = true, shownElapsedMs = null))
    }

    @Test fun `the shared ad budget always wins`() {
        assertEquals(200, holdMs(1000, budgetLeftMs = 200, filled = true, shownElapsedMs = 0, focusedElapsedMs = 0))
        assertEquals(0, holdMs(1000, budgetLeftMs = 0, filled = true, shownElapsedMs = 0, focusedElapsedMs = 0))
    }

    @Test fun `zero restores the old behaviour of never holding`() {
        assertEquals(0, holdMs(0, 60_000, filled = true, shownElapsedMs = 0, focusedElapsedMs = 0))
    }

    @Test fun `the shipped default protects the slot rather than leaving it unguarded`() {
        val default = OnboardingSettings.defaultNumber("splash.timing.slot_min_visible_ms")
        assertTrue("a 0 default would ship the very behaviour this replaced", default > 0)
        assertEquals(1000L, default)
    }
}
