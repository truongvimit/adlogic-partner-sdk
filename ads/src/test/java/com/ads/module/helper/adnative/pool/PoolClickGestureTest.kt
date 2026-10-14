package com.ads.module.helper.adnative.pool

import org.junit.Assert.assertEquals
import org.junit.Test

class PoolClickGestureTest {
    private val clock = FakeClock()
    private val gesture = PoolClickGesture(clock)

    /** C = onAdClicked, O = onAdOpened, +ms = time passing; returns the clicks reported. */
    private fun play(vararg steps: String): Int = steps.count { step ->
        when {
            step == "C" -> gesture.clicked()
            step == "O" -> gesture.opened()
            step.startsWith("+") -> {
                clock.now += step.drop(1).toLong()
                false
            }
            else -> error(step)
        }
    }

    @Test fun `GMA's clicked then opened is one tap`() {
        assertEquals(1, play("C", "+5", "O"))
    }

    @Test fun `opened then clicked is one tap`() {
        assertEquals(1, play("O", "+5", "C"))
    }

    @Test fun `an adapter reporting only clicked counts every tap, however quick`() {
        assertEquals(3, play("C", "C", "+1", "C"))
    }

    @Test fun `an adapter reporting only opened counts every tap, however quick`() {
        assertEquals(3, play("O", "O", "+1", "O"))
    }

    @Test fun `two taps each reported twice are two`() {
        assertEquals(2, play("C", "O", "+1", "C", "O"))
        assertEquals(2, play("O", "C", "+1", "O", "C"))
        assertEquals("Each pair keeps its own order", 2, play("C", "O", "+1", "O", "C"))
    }

    @Test fun `the second half pairs with the open tap anywhere inside the window`() {
        assertEquals(1, play("C", "+${PoolClickGesture.PAIR_WINDOW_MS - 1}", "O"))
    }

    @Test fun `a lone half older than the window no longer swallows the next tap`() {
        assertEquals(2, play("C", "+${PoolClickGesture.PAIR_WINDOW_MS}", "O"))
        assertEquals(2, play("O", "+${PoolClickGesture.PAIR_WINDOW_MS}", "C"))
    }

    @Test fun `a tap after a lone half still counts once when its own pair follows`() {
        assertEquals(2, play("C", "+3000", "O", "C"))
    }
}
