package io.retentionkit.notifications

import io.retentionkit.core.RetentionState
import io.retentionkit.core.RetentionTransaction

/** Durable readiness checkpoints never change the occurrence, civil date or delivery deadline. */
internal object DeferredCalendarRetry {
    fun remember(state: RetentionTransaction, alarm: ScheduledNotification, now: Long) {
        val same = state.string("deferred:${alarm.key}") == alarm.encode()
        // Duplicate OS callbacks before the chosen checkpoint cannot spend another retry.
        if (same && state.long("deferred_trigger:${alarm.key}") > now) return
        val attempts = (if (same) state.long("deferred_attempts:${alarm.key}") else 0).coerceIn(0, 6)
        // Six inexact checkpoints: +30s, +1m, +2m, +4m, +8m, +16m; then expiry cleanup.
        val next = if (attempts < 6) minOf(alarm.expires, now + 30_000L * (1L shl attempts.toInt())) else alarm.expires
        state.put("deferred:${alarm.key}", alarm.encode())
        state.put("deferred_trigger:${alarm.key}", next)
        state.put("deferred_attempts:${alarm.key}", attempts + 1)
    }

    fun trigger(state: RetentionState, alarm: ScheduledNotification, now: Long): Long =
        if (state.string("deferred:${alarm.key}") == alarm.encode())
            maxOf(now, minOf(alarm.expires, state.long("deferred_trigger:${alarm.key}", alarm.expires)))
        else alarm.due

    fun clear(state: RetentionTransaction, key: String) {
        state.remove("deferred:$key")
        state.remove("deferred_trigger:$key")
        state.remove("deferred_attempts:$key")
    }
}
