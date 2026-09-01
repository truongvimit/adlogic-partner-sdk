package io.onboardkit.ui.pager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvanceFlingTest {

    private fun advance(
        deltaX: Float,
        deltaY: Float = 0f,
        velocityX: Float = -2_000f,
        rtl: Boolean = false,
        minDistancePx: Float = 32f,
        minVelocityPx: Float = 400f,
    ) = isAdvanceFling(deltaX, deltaY, velocityX, rtl, minDistancePx, minVelocityPx)

    @Test
    fun `a leftward fling advances under LTR`() {
        assertTrue(advance(deltaX = -200f))
    }

    @Test
    fun `a rightward fling advances under RTL`() {
        assertTrue(advance(deltaX = 200f, velocityX = 2_000f, rtl = true))
    }

    @Test
    fun `the back-swipe direction never advances`() {
        assertFalse(advance(deltaX = 200f, velocityX = 2_000f))
        assertFalse(advance(deltaX = -200f, velocityX = -2_000f, rtl = true))
    }

    @Test
    fun `a mostly vertical gesture is a scroll, not an advance`() {
        assertFalse(advance(deltaX = -200f, deltaY = 300f))
    }

    @Test
    fun `a slow drag is not deliberate enough`() {
        assertFalse(advance(deltaX = -200f, velocityX = -100f))
    }

    @Test
    fun `a fling shorter than the paging slop is a tap that wobbled`() {
        assertFalse(advance(deltaX = -10f))
    }

    @Test
    fun `movement and velocity must agree on the direction`() {
        // The finger ended left of where it started but was flung back to the right —
        // the tail of an aborted gesture, not an advance.
        assertFalse(advance(deltaX = -200f, velocityX = 2_000f))
    }
}
