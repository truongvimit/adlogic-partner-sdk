package com.ads.module.helper.interstitial

import com.ads.module.helper.interstitial.InterstitialAutoBuffer.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class InterstitialAutoBufferTest {

    private fun decide(
        ready: Boolean = false,
        loading: Boolean = false,
        backoffUntil: Long = 0L,
        hasIds: Boolean = true,
        reserved: Boolean = false,
        now: Long = 1_000L,
    ) = InterstitialAutoBuffer.decide(now, ready, loading, backoffUntil, hasIds, reserved)

    @Test
    fun `loads when the placement is empty`() {
        assertEquals(Decision.LOAD, decide())
    }

    @Test
    fun `an ad already buffered satisfies the placement`() {
        // Including one a screen loaded itself — the store is shared.
        assertEquals(Decision.SKIP_READY, decide(ready = true))
    }

    @Test
    fun `a load already in flight is never doubled`() {
        // The "screen preloaded at t=20s, tick fires at t=25s" case.
        assertEquals(Decision.SKIP_IN_FLIGHT, decide(ready = false, loading = true))
    }

    @Test
    fun `backoff holds off a placement that did not fill`() {
        assertEquals(Decision.SKIP_BACKOFF, decide(now = 1_000L, backoffUntil = 5_000L))
        assertEquals(Decision.LOAD, decide(now = 6_000L, backoffUntil = 5_000L))
    }

    @Test
    fun `a placement with no configured ids is skipped`() {
        assertEquals(Decision.SKIP_NO_IDS, decide(hasIds = false))
    }

    @Test
    fun `reserved placements are never touched`() {
        assertEquals(Decision.SKIP_RESERVED, decide(reserved = true, ready = false))
    }

    @Test
    fun `period follows the interval, floored and capped`() {
        assertEquals(30_000L, InterstitialAutoBuffer.periodMs(0L, 30, 45_000L, 5_000L))
        assertEquals(45_000L, InterstitialAutoBuffer.periodMs(0L, 0, 45_000L, 5_000L))
        assertEquals(7_000L, InterstitialAutoBuffer.periodMs(7_000L, 30, 45_000L, 5_000L))
        assertEquals(5_000L, InterstitialAutoBuffer.periodMs(0L, 1, 45_000L, 5_000L))
        // A pathological interval must not let the buffer expire between checks.
        assertEquals(30 * 60 * 1_000L, InterstitialAutoBuffer.periodMs(0L, 7_200, 45_000L, 5_000L))
    }
}
