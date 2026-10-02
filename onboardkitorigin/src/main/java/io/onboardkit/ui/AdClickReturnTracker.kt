package io.onboardkit.ui

import com.ads.module.helper.adnative.NativeClickAction

/**
 * Shared click-return state for native screens.
 *
 * AUTO_NEXT is armed by the ad callback, promoted to an away state only when the host pauses,
 * and consumed once the host resumes. Home/background pauses do not arm the state, so they never
 * advance a screen by themselves.
 */
internal class AdClickReturnTracker {
    private var engaged = false
    private var away = false

    fun onEngaged(action: NativeClickAction) {
        if (action == NativeClickAction.AUTO_NEXT && !away) engaged = true
    }

    fun onPause() {
        if (!engaged) return
        engaged = false
        away = true
    }

    /** Returns true exactly once when the host resumes from an armed ad destination. */
    fun onResume(): Boolean {
        engaged = false
        if (!away) return false
        away = false
        return true
    }

    /** A touch proves that a reported click did not actually take the screen away. */
    fun onTouch() {
        if (!away) engaged = false
    }

    fun reset() {
        engaged = false
        away = false
    }
}
