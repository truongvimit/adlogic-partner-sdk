package io.onboardkit.ads

import androidx.lifecycle.Lifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRequestWindowTest {

    @Test fun `an ordinary request needs a resumed owner with window focus`() {
        assertEquals(true, isRequestWindowOpen(alive = true, State.RESUMED, focused = true, allowWhileVisible = false))
        assertEquals(false, isRequestWindowOpen(alive = true, State.RESUMED, focused = false, allowWhileVisible = false))
        assertEquals(false, isRequestWindowOpen(alive = true, State.STARTED, focused = true, allowWhileVisible = false))
    }

    @Test fun `under the splash's own prompt a started owner is enough`() {
        assertEquals(true, isRequestWindowOpen(alive = true, State.STARTED, focused = false, allowWhileVisible = true))
        assertEquals(false, isRequestWindowOpen(alive = true, State.CREATED, focused = false, allowWhileVisible = true))
    }

    @Test fun `a finishing or destroyed owner never opens the window`() {
        assertEquals(false, isRequestWindowOpen(alive = false, State.RESUMED, focused = true, allowWhileVisible = true))
    }

    @Test fun `an owner without a lifecycle is judged on focus alone`() {
        assertEquals(true, isRequestWindowOpen(alive = true, state = null, focused = true, allowWhileVisible = false))
        assertEquals(false, isRequestWindowOpen(alive = true, state = null, focused = false, allowWhileVisible = true))
    }
}
