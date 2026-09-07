package io.retentionkit.core

import android.app.Activity

/** Optional vendor-free UI integration. Callbacks are short and always outside coordinator locks. */
interface RetentionUiHost {
    /** Rechecked both before acquisition and whenever an asynchronous flow reads its weak Activity. */
    fun canPresent(activity: Activity): Boolean = true

    /** Explicit user entry navigation may use an intermediary Main that must not show prompts. */
    fun canPresentEntry(activity: Activity): Boolean = canPresent(activity)

    /**
     * Own a bounded host resource such as resume-ad suppression for this exact lease. Do not retain
     * an Activity. Close releases only this token, and must be safe after expiry/lifecycle invalidation.
     */
    fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable = AutoCloseable {}

    companion object { @JvmField val NONE = object : RetentionUiHost {} }
}
