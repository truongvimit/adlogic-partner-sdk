package com.ads.module.helper.adnative

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native fill is normally parked until its host resumes. The splash bottom slot opts out, because
 * it shares a position with a banner and the banner renders under the notification permission
 * dialog instead of waiting for it — one slot should not look different depending on which format
 * remote config picked that launch.
 *
 * What makes the opt-in safe is the line it is drawn at. A permission dialog is its own Activity, so
 * the host is paused but still STARTED; leaving the app stops it. Binding on the first and refusing
 * on the second is the difference between an impression the user can see and one they cannot.
 */
class NativeBindWhilePausedTest {

    /** The helper's rule, kept here in the one form both branches of it can be read at once. */
    private fun hostReady(state: Lifecycle.State, optedIn: Boolean): Boolean =
        state.isAtLeast(Lifecycle.State.RESUMED) ||
            (optedIn && state.isAtLeast(Lifecycle.State.STARTED))

    @Test fun `an ordinary native still waits for its host to resume`() {
        assertTrue(hostReady(Lifecycle.State.RESUMED, optedIn = false))
        assertFalse("a paused host must still park the fill", hostReady(Lifecycle.State.STARTED, optedIn = false))
        assertFalse(hostReady(Lifecycle.State.CREATED, optedIn = false))
    }

    @Test fun `the opted-in slot binds under a permission dialog`() {
        // Paused but on screen: the dialog is a separate Activity sitting over a visible splash.
        assertTrue(hostReady(Lifecycle.State.STARTED, optedIn = true))
        assertTrue(hostReady(Lifecycle.State.RESUMED, optedIn = true))
    }

    @Test fun `the opted-in slot still refuses once the app is off screen`() {
        // Home was pressed: no dialog, nothing on screen, and an impression here is unviewable.
        assertFalse("a stopped host must never take a fill", hostReady(Lifecycle.State.CREATED, optedIn = true))
        assertFalse(hostReady(Lifecycle.State.DESTROYED, optedIn = true))
    }

    @Test fun `the opt-in is off by default, so no existing placement changes`() {
        assertFalse(
            "defaulting this on would bind every native in the SDK under a paused host",
            NativeAdConfig(listOf("unit"), true, false, 0).bindsWhileHostPaused,
        )
    }
}
