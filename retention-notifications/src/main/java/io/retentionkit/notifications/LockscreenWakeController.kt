package io.retentionkit.notifications

import io.retentionkit.core.RetentionClock
import io.retentionkit.core.RetentionStore
import org.json.JSONObject

private const val WAKE_STATE = "notifications.wake.v1"
private const val REWAKE_MARGIN = 10_000L

private data class WakeMessage(
    val occurrence: String, val attempts: Int = 0, val ended: Boolean = false,
    val interactiveSeen: Boolean = false, val notBefore: Long = 0,
    val checkpointAt: Long = 0, val checkpointPending: Boolean = false, val deferrals: Int = 0,
    val confirmed: Boolean = false,
) {
    fun encode(): String = JSONObject().put("occurrence", occurrence).put("attempts", attempts).put("ended", ended)
        .put("interactive", interactiveSeen).put("not_before", notBefore).put("checkpoint", checkpointAt)
        .put("pending", checkpointPending).put("deferrals", deferrals).put("confirmed", confirmed).toString()
    companion object {
        fun decode(raw: String): WakeMessage {
            require(raw.length <= 2048)
            val j = JSONObject(raw)
            return WakeMessage(j.getString("occurrence"), j.getInt("attempts"), j.getBoolean("ended"),
                j.getBoolean("interactive"), j.getLong("not_before"), j.getLong("checkpoint"),
                j.getBoolean("pending"), j.getInt("deferrals"), j.optBoolean("confirmed", true)).also {
                require(idPattern.matches(it.occurrence) && it.attempts in 0..2 && it.deferrals in 0..6)
            }
        }
    }
}

/** Shares the module monitor: callbacks cannot invert controller/module locks or spend a third wake. */
internal class LockscreenWakeController(
    private val monitor: Any,
    private val store: RetentionStore,
    private val clock: RetentionClock,
    private val notifications: NotificationPlatform,
    private val power: NotificationWakePlatform,
    private val delays: NotificationDelays,
    private val profile: () -> NotificationProfile,
    private val blocked: () -> String?,
    private val event: (String, Map<String, String>) -> Unit,
    private val report: (String, Exception) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var observer: AutoCloseable? = null
    private var lease: AutoCloseable? = null
    private var releaseTimer: AutoCloseable? = null
    private var confirmation: AutoCloseable? = null
    private var execution: NotificationAlarmExecution? = null
    private var confirming: String? = null
    private var leaseToken = 0L

    private fun read(): WakeMessage? = store.snapshot(WAKE_STATE).string("message")?.let(WakeMessage::decode)
    private fun write(value: WakeMessage) { store.transaction(WAKE_STATE) { it.put("message", value.encode()) } }
    private fun active(value: WakeMessage): Boolean = notifications.activeOccurrence(NotificationCampaign.LOCKSCREEN) == value.occurrence
    private fun current(occurrence: String): WakeMessage? = read()?.takeIf { it.occurrence == occurrence && !it.ended }
    private inline fun safely(stage: String, action: () -> Unit) {
        try { action() } catch (e: Exception) { release(); report("wake_$stage", e) }
    }

    fun matchesPending(occurrence: String?): Boolean = synchronized(monitor) {
        if (closed || occurrence == null) false else try { current(occurrence) != null } catch (_: Exception) { false }
    }

    fun posted(occurrence: String, alarmExecution: NotificationAlarmExecution? = null) = synchronized(monitor) {
        if (closed) return
        safely("posted") {
            val old = read()
            if (old?.occurrence != occurrence) {
                stopResources(old?.occurrence)
                write(WakeMessage(occurrence))
            }
            confirming = occurrence
            execution = alarmExecution?.takeIf { it.retain() }
            val value = current(occurrence) ?: return@safely
            if (blocked() != null || execution == null) {
                confirming = null
                val pending = if (!value.confirmed && active(value)) value.copy(confirmed = true).also(::write) else value
                requestCheckpoint(pending, "post")
                release()
                return@safely
            }
            confirmPost(occurrence, 0, execution)
        }
    }

    private fun confirmPost(occurrence: String, check: Int, owner: NotificationAlarmExecution?) {
        if (closed || confirming != occurrence || execution !== owner) return
        val value = current(occurrence) ?: return
        if (blocked() != null || execution?.isActive() != true) {
            confirming = null
            requestCheckpoint(value, "confirmation")
            release()
            return
        }
        if (!active(value)) {
            // Save recovery before waiting: process death or a blocked main thread can prevent
            // the short confirmation callback from running even though Android accepted notify.
            if (check == 0 && !value.checkpointPending && value.deferrals < 6) {
                val at = clock.wallTimeMillis() + 30_000L * (1L shl value.deferrals)
                write(value.copy(checkpointAt = at, checkpointPending = true))
                power.schedule(occurrence, at)
            }
            if (check >= 3) { cancel(occurrence, "post_not_observed"); return }
            confirmation = delays.post(listOf(100L, 300L, 1000L)[check]) {
                synchronized(monitor) { safely("confirm") { confirmPost(occurrence, check + 1, owner) } }
            }
            return
        }
        confirmation?.close(); confirmation = null; confirming = null
        write(value.copy(confirmed = true))
        observe()
        attempt(occurrence, "post")
    }

    /** Restore observation/checkpoints, including an explicitly deferred first wake, not a new request. */
    fun reconcile() = synchronized(monitor) {
        if (closed) return
        safely("reconcile") {
            val value = read()?.takeIf { !it.ended } ?: return@safely
            if (confirming == value.occurrence && blocked() == null && execution?.isActive() == true) return@safely
            if (confirming == value.occurrence) {
                confirmation?.close(); confirmation = null; confirming = null
                val pending = if (!value.confirmed && active(value)) value.copy(confirmed = true).also(::write) else value
                requestCheckpoint(pending, "confirmation_readiness")
                release()
                return@safely
            }
            if (!active(value) && value.confirmed) { cancel(value.occurrence, "notification_gone"); return@safely }
            val reason = blocked()
            if (reason != null) {
                release()
                if (reason != "entitlement_unknown") cancel(value.occurrence, reason)
                else if (value.checkpointAt > 0) power.schedule(value.occurrence, value.checkpointAt)
                return@safely
            }
            if (value.attempts < 2) observe()
            if (value.attempts == 0 && value.checkpointPending) {
                requestCheckpoint(value, "readiness")
            } else if (value.checkpointPending && value.checkpointAt <= clock.wallTimeMillis()) {
                requestCheckpoint(value, "readiness")
            } else if (value.checkpointAt > 0) power.schedule(value.occurrence, value.checkpointAt)
        }
    }

    private fun observe() {
        if (observer != null || closed) return
        val observedOccurrence = read()?.occurrence ?: return
        observer = power.observe { interactive ->
            synchronized(monitor) {
                if (closed) return@synchronized
                safely("screen") {
                    val value = current(observedOccurrence) ?: return@safely
                    if (!active(value)) { cancel(value.occurrence, "notification_gone"); return@safely }
                    if (interactive) {
                        if (!value.interactiveSeen) {
                            write(value.copy(interactiveSeen = true))
                            event("wake_observed", mapOf("observation" to "interactive", "attribution" to "not_proven"))
                        }
                    } else requestCheckpoint(value, "screen_off")
                }
            }
        }
    }

    fun checkpoint(occurrence: String, atMillis: Long, alarmExecution: NotificationAlarmExecution? = null) = synchronized(monitor) {
        if (closed) return
        safely("checkpoint") {
            val value = current(occurrence) ?: return@safely
            if (!value.checkpointPending || value.checkpointAt != atMillis) return@safely
            val now = clock.wallTimeMillis()
            if (now < atMillis) { power.schedule(occurrence, atMillis); return@safely }
            if (!active(value) && value.confirmed) { cancel(occurrence, "notification_gone"); return@safely }
            if (blocked() == "entitlement_unknown") {
                release()
                // Bounded cold Billing readiness checks. No timer holds the process between them.
                if (value.deferrals < 6) {
                    val next = now + 30_000L * (1L shl value.deferrals)
                    write(value.copy(checkpointAt = next, deferrals = value.deferrals + 1))
                    power.schedule(occurrence, next)
                } else {
                    write(value.copy(checkpointAt = 0))
                    power.cancel(occurrence) // A later authoritative signal can still decide the pending attempt.
                }
                return@safely
            }
            if (alarmExecution == null || !alarmExecution.retain()) {
                requestCheckpoint(value, "outside_alarm")
                return@safely
            }
            release()
            execution = alarmExecution
            write(value.copy(checkpointAt = 0, checkpointPending = false,
                deferrals = if (!value.confirmed) (value.deferrals + 1).coerceAtMost(6) else value.deferrals))
            power.cancel(occurrence)
            observe()
            if (!value.confirmed) {
                confirming = occurrence
                confirmPost(occurrence, 0, execution)
            } else attempt(occurrence, "checkpoint")
        }
    }

    private fun attempt(occurrence: String, trigger: String) {
        val value = current(occurrence) ?: return
        if (value.attempts >= 2 || !active(value)) { release(); return }
        val reason = blocked() ?: power.blocked()
        if (reason != null) {
            release()
            if (reason != "entitlement_unknown") cancel(occurrence, reason)
            else if (value.attempts == 0 && !value.checkpointPending) {
                // No wake claim was spent. Persist this confirmed pending decision before arming
                // bounded readiness recovery; repeated UNKNOWN signals cannot reset its budget.
                val at = clock.wallTimeMillis() + 30_000L
                write(value.copy(checkpointAt = at, checkpointPending = true))
                if (current(occurrence)?.checkpointAt == at) power.schedule(occurrence, at)
            }
            event("wake_blocked", mapOf("reason" to reason))
            return
        }
        val owner = execution
        if (owner?.isActive() != true) {
            requestCheckpoint(value, trigger)
            release()
            return
        }
        if (power.interactive()) {
            if (!value.interactiveSeen) write(value.copy(interactiveSeen = true))
            release()
            return
        }
        val now = clock.wallTimeMillis()
        if (value.attempts > 0 && (!value.interactiveSeen || now < value.notBefore)) { release(); return }
        val duration = profile().wakeDurationMillis.coerceIn(1, MAX_WAKE_MILLIS)
        val next = if (value.attempts == 0) now + duration + REWAKE_MARGIN else 0L
        // Persist before acquire; uncertain failures consume the claim instead of risking a third wake.
        val claimed = value.copy(attempts = value.attempts + 1, notBefore = next,
            checkpointAt = next, checkpointPending = next > 0, deferrals = 0)
        write(claimed)
        if (closed || current(occurrence)?.attempts != claimed.attempts || !active(claimed) || blocked() != null) { release(); return }
        releaseTimer?.close(); releaseTimer = null
        lease?.close(); lease = null
        val token = ++leaseToken
        event("wake_requested", mapOf("attempt" to claimed.attempts.toString(), "duration_ms" to duration.toString(), "trigger" to trigger))
        val acquired = try { owner.acquire(duration) { power.acquire(duration) } } catch (e: Exception) {
            event("wake_failed", mapOf("reason" to e.javaClass.simpleName)); throw e
        } ?: return
        // An Android adapter or store boundary can reenter and revoke the selected message.
        if (closed || token != leaseToken || current(occurrence)?.attempts != claimed.attempts || !active(claimed) || blocked() != null) {
            acquired.close(); return
        }
        lease = acquired
        event("wake_lock_acquired", mapOf("attempt" to claimed.attempts.toString()))
        val timer = delays.post(duration) { synchronized(monitor) { if (token == leaseToken) release() } }
        if (token == leaseToken) releaseTimer = timer else timer.close()
        if (power.interactive()) {
            write(claimed.copy(interactiveSeen = true))
            event("wake_observed", mapOf("observation" to "interactive", "attribution" to "not_proven"))
        }
        if (next > 0) power.schedule(occurrence, next)
        else { power.cancel(occurrence); observer?.close(); observer = null }
    }

    /** Only an owned manifest alarm may acquire. Foreign callbacks persist an inexact request. */
    private fun requestCheckpoint(value: WakeMessage, trigger: String) {
        if (value.attempts >= 2) return
        if (!value.confirmed && value.deferrals >= 6 && !active(value)) {
            cancel(value.occurrence, "post_not_observed")
            return
        }
        val reason = blocked() ?: power.blocked()
        if (reason != null && reason != "entitlement_unknown") { cancel(value.occurrence, reason); return }
        if (reason == "entitlement_unknown" && value.checkpointPending) return
        val now = clock.wallTimeMillis()
        val desired = if (reason == "entitlement_unknown") now + 30_000L else maxOf(now + 1, value.notBefore)
        val at = if (value.checkpointPending && value.checkpointAt > 0) minOf(value.checkpointAt, desired) else desired
        val pending = value.copy(checkpointAt = at, checkpointPending = true)
        if (pending != value) write(pending)
        power.schedule(value.occurrence, at)
        event("wake_deferred", mapOf("reason" to (reason ?: "alarm_execution_required"), "trigger" to trigger))
    }

    fun cancel(occurrence: String? = null, reason: String) = synchronized(monitor) {
        safely("cancel") {
            val value = read() ?: return@safely
            if (occurrence != null && value.occurrence != occurrence) return@safely
            stopResources(value.occurrence) // Release resources even if the terminal write fails.
            write(value.copy(ended = true, checkpointAt = 0, checkpointPending = false))
            event("wake_cancelled", mapOf("reason" to reason))
        }
    }

    private fun release() {
        leaseToken++
        releaseTimer?.close(); releaseTimer = null
        val owned = lease; lease = null
        val receipt = execution; execution = null
        var failure: Exception? = null
        try { owned?.close() } catch (error: Exception) { failure = error }
        try { receipt?.close() } catch (error: Exception) { if (failure == null) failure = error }
        // No partner telemetry before both mandatory raw/receipt cleanup operations.
        if (failure != null) report("wake_release", failure)
        else if (owned != null && receipt?.cleanupSucceeded() == true) event("wake_released", emptyMap())
    }
    private fun stopResources(occurrence: String?) {
        release()
        confirmation?.close(); confirmation = null; confirming = null
        observer?.close(); observer = null
        if (occurrence != null) safely("cancel_alarm") { power.cancel(occurrence) }
    }
    override fun close() = synchronized(monitor) {
        closed = true
        stopResources(try { read()?.occurrence } catch (_: Exception) { null })
    }
}
