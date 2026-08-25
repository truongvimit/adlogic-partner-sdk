package com.ads.module.helper.interstitial

import org.junit.Assert.assertEquals
import org.junit.Test

class InterstitialFrequencyTest {

    @Test
    fun `interval of zero never blocks`() {
        assertEquals(0L, InterstitialFrequency.remainingMs(1_000L, 900L, 0))
    }

    @Test
    fun `fresh install is never blocked`() {
        assertEquals(0L, InterstitialFrequency.remainingMs(1_000L, 0L, 30))
    }

    @Test
    fun `the boundary itself is allowed, one ms before it is not`() {
        // The module compares with a strict `<`, so elapsed == interval must show.
        assertEquals(0L, InterstitialFrequency.remainingMs(60_000L, 30_000L, 30))
        assertEquals(1L, InterstitialFrequency.remainingMs(59_999L, 30_000L, 30))
    }

    @Test
    fun `mid interval reports what is left`() {
        assertEquals(20_000L, InterstitialFrequency.remainingMs(40_000L, 30_000L, 30))
    }

    @Test
    fun `a rolled back clock blocks, mirroring the module`() {
        // Clamped to the interval rather than growing without bound.
        assertEquals(30_000L, InterstitialFrequency.remainingMs(10_000L, 60_000L, 30))
    }
}
