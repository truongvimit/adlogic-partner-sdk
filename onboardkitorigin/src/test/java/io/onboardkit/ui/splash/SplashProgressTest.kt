package io.onboardkit.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashProgressTest {
    @Test
    fun `progress advances from zero to ninety in ten visible seconds`() {
        val progress = SplashProgress()

        assertEquals(0, progress.percent(nowMs = 5_000))
        progress.setActive(true, nowMs = 5_000)

        assertEquals(0, progress.percent(nowMs = 5_000))
        assertEquals(45, progress.percent(nowMs = 10_000))
        assertEquals(90, progress.percent(nowMs = 15_000))
    }

    @Test
    fun `the last ten percent slows down and completes only at the timeout`() {
        val progress = SplashProgress()
        progress.setActive(true, nowMs = 0)

        assertEquals(90, progress.percent(nowMs = 10_000))
        assertEquals(93, progress.percent(nowMs = 20_000))
        assertEquals(97, progress.percent(nowMs = 35_000))
        assertEquals(99, progress.percent(nowMs = 50_000))
        assertEquals(99, progress.percent(nowMs = 59_999))
        assertEquals(100, progress.percent(nowMs = 60_000))
        assertEquals(100, progress.percent(nowMs = 90_000))
    }

    @Test
    fun `hidden splash time is excluded across prompts background and recreation`() {
        val progress = SplashProgress()
        assertFalse(progress.isActive)
        progress.setActive(true, nowMs = 1_000)
        assertTrue(progress.isActive)
        progress.setActive(false, nowMs = 6_000)

        assertFalse(progress.isActive)
        assertEquals(5_000L, progress.elapsedMs(nowMs = 120_000))
        assertEquals(45, progress.percent(nowMs = 120_000))

        progress.setActive(true, nowMs = 120_000)
        assertEquals(5_000L, progress.elapsedMs(nowMs = 120_000))
        assertEquals(90, progress.percent(nowMs = 125_000))

        progress.setActive(false, nowMs = 125_000)
        progress.setActive(true, nowMs = 200_000)
        assertEquals(99, progress.percent(nowMs = 249_999))
        assertEquals(100, progress.percent(nowMs = 250_000))
    }

    @Test
    fun `repeated visibility updates do not reset or double count elapsed time`() {
        val progress = SplashProgress()
        progress.setActive(true, nowMs = 1_000)
        progress.setActive(true, nowMs = 3_000)
        assertEquals(4_000L, progress.elapsedMs(nowMs = 5_000))

        progress.setActive(false, nowMs = 5_000)
        progress.setActive(false, nowMs = 8_000)
        assertEquals(4_000L, progress.elapsedMs(nowMs = 9_000))

        progress.setActive(true, nowMs = 9_000)
        assertEquals(5_000L, progress.elapsedMs(nowMs = 10_000))
    }

    @Test
    fun `progress follows a configured timeout without reaching one hundred early`() {
        val progress = SplashProgress()
        progress.setActive(true, nowMs = 0)

        assertEquals(90, progress.percent(nowMs = 10_000, timeoutMs = 30_000))
        assertEquals(97, progress.percent(nowMs = 20_000, timeoutMs = 30_000))
        assertEquals(99, progress.percent(nowMs = 29_999, timeoutMs = 30_000))
        assertEquals(100, progress.percent(nowMs = 30_000, timeoutMs = 30_000))
        assertEquals(45, progress.percent(nowMs = 2_500, timeoutMs = 5_000))
        assertEquals(100, progress.percent(nowMs = 5_000, timeoutMs = 5_000))
        assertEquals(100, progress.percent(nowMs = 0, timeoutMs = 0))
    }
    @Test
    fun `scheduler wakes only when the displayed percent changes`() {
        val progress = SplashProgress()
        progress.setActive(true, nowMs = 0)
        var now = 0L
        var previous = 0
        var wakes = 0
        while (true) {
            val wait = progress.nextUpdateDelayMs(now, 60_000) ?: break
            assertTrue("Every scheduled wake must be in the future", wait > 0)
            assertEquals(previous, progress.percent(now + wait - 1))
            now += wait
            val percent = progress.percent(now)
            assertEquals(previous + 1, percent)
            previous = percent
            wakes++
        }
        assertEquals(100, wakes)
        assertEquals(60_000L, now)
    }

    @Test
    fun `scheduler sleeps while paused and recalculates after resuming or a late wake`() {
        val progress = SplashProgress()
        assertEquals(null, progress.nextUpdateDelayMs(0))
        progress.setActive(true, 0)
        assertEquals(112L, progress.nextUpdateDelayMs(0))
        progress.setActive(false, 5_000)
        assertEquals(null, progress.nextUpdateDelayMs(90_000))
        progress.setActive(true, 90_000)
        assertEquals(112L, progress.nextUpdateDelayMs(90_000))
        val lateNow = 115_000L
        val wait = requireNotNull(progress.nextUpdateDelayMs(lateNow))
        assertEquals(progress.percent(lateNow) + 1, progress.percent(lateNow + wait))
        assertEquals(100, progress.percent(145_000))
        assertEquals(null, progress.nextUpdateDelayMs(145_000))
    }

}
