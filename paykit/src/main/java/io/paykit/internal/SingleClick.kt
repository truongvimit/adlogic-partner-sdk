package io.paykit.internal

import android.os.SystemClock
import android.view.View

/**
 * Tap gate shared by every control of one paywall presentation.
 *
 * Shared rather than per-view on purpose: a double-tap on the CTA must not open two billing
 * flows, and neither must a tap that lands on Close while the first flow is still opening.
 */
internal class SingleClick(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastAcceptedMs = 0L

    /** True when [nowMs] is far enough past the previous accepted tap to act on. */
    fun accept(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastAcceptedMs != 0L && nowMs - lastAcceptedMs < windowMs) return false
        lastAcceptedMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 700L
    }
}

internal fun View.onSingleClick(gate: SingleClick, onClick: () -> Unit) {
    setOnClickListener { if (gate.accept()) onClick() }
}
