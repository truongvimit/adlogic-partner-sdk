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
) {
    fun encode(): String = JSONObject().put("occurrence", occurrence).put("attempts", attempts).put("ended", ended)
        .put("interactive", interactiveSeen).put("not_before", notBefore).put("checkpoint", checkpointAt)
        .put("pending", checkpointPending).put("deferrals", deferrals).toString()
    companion object {
        fun decode(raw: String): WakeMessage {
            require(raw.length <= 2048)
            val j = JSONObject(raw)
            return WakeMessage(j.getString("occurrence"), j.getInt("attempts"), j.getBoolean("ended"),
                j.getBoolean("interactive"), j.getLong("not_before"), j.getLong("checkpoint"),
                j.getBoolean("pending"), j.getInt("deferrals")).also {
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
    private var confirming: String? = null
    private var leaseToken = 0L

    private fun read(): WakeMessage? = store.snapshot(WAKE_STATE).string("message")?.let(WakeMessage::decode)
    private fun write(value: WakeMessage) { store.transaction(WAKE_STATE) { it.put("message", value.encode()) } }
    private fun active(value: WakeMessage): Boolean = notifications.activeOccurrence(NotificationCampaign.LOCKSCREEN) == value.occurrence
    private fun current(occurrence: String): WakeMessage? = read()?.takeIf { it.occurrence == occurrence && !it.ended }
    private inline fun safely(stage: String, action: () -> Unit) { try { action() } catch (e: Exception) { report("wake_$stage", e) } }

    fun matchesPending(occurrence: String?): Boolean = synchronized(monitor) {
        if (closed || occurrence == null) false else try { current(occurrence) != null } catch (_: Exception) { false }
    }

    fun posted(occurrence: String) = synchronized(monitor) {
        if (closed) return
        safely("posted") {
            val old = read()
            if (old?.occurrence != occurrence) {
                stopResources(old?.occurrence)
                write(WakeMessage(occurrence))
            }
            confirming = occurrence
            confirmPost(occurrence, 0)
        }
    }

    private fun confirmPost(occurrence: String, check: Int) {
        if (closed || confirming != occurrence) return
        val value = current(occurrence) ?: return
        if (!active(value)) {
            if (check >= 3) { cancel(occurrence, "post_not_observed"); return }
            confirmation = delays.post(listOf(100L, 300L, 1000L)[check]) {
                synchronized(monitor) { safely("confirm") { confirmPost(occurrence, check + 1) } }
            }
            return
        }
        confirmation?.close(); confirmation = null; confirming = null
        observe()
        attempt(occurrence, "post")
    }

    /** Never wake merely because a process restarted. Restore observation and a saved checkpoint. */
    fun reconcile() = synchronized(monitor) {
        if (closed) return
        safely("reconcile") {
            val value = read()?.takeIf { !it.ended } ?: return@safely
            if (confirming == value.occurrence) return@safely
            if (!active(value)) { cancel(value.occurrence, "notification_gone"); return@safely }
            val reason = blocked()
            if (reason != null) {
                release()
                if (reason != "entitlement_unknown") cancel(value.occurrence, reason)
                else if (value.checkpointAt > 0) power.schedule(value.occurrence, value.checkpointAt)
                return@safely
            }
            if (value.attempts < 2) observe()
            if (value.checkpointPending && value.checkpointAt <= clock.wallTimeMillis()) {
                checkpoint(value.occurrence, value.checkpointAt)
            } else if (value.checkpointAt > 0) power.schedule(value.occurrence, value.checkpointAt)
        }
    }

    private fun observe() {
        if (observer != null || closed) return
        observer = power.observe { interactive ->
            synchronized(monitor) {
                if (closed) return@synchronized
                safely("screen") {
                    val value = read()?.takeIf { !it.ended } ?: return@safely
                    if (!active(value)) { cancel(value.occurrence, "notification_gone"); return@safely }
                    if (interactive) {
                        if (!value.interactiveSeen) {
                            write(value.copy(interactiveSeen = true))
                            event("wake_observed", mapOf("observation" to "interactive"))
                        }
                    } else attempt(value.occurrence, "screen_off")
                }
            }
        }
    }

    fun checkpoint(occurrence: String, atMillis: Long) = synchronized(monitor) {
        if (closed) return
        safely("checkpoint") {
            val value = current(occurrence) ?: return@safely
            if (!value.checkpointPending || value.checkpointAt != atMillis) return@safely
            val now = clock.wallTimeMillis()
            if (now < atMillis) { power.schedule(occurrence, atMillis); return@safely }
            if (!active(value)) { cancel(occurrence, "notification_gone"); return@safely }
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
            write(value.copy(checkpointAt = 0, checkpointPending = false))
            power.cancel(occurrence)
            observe()
            attempt(occurrence, "checkpoint")
        }
    }

    private fun attempt(occurrence: String, trigger: String) {
        val value = current(occurrence) ?: return
        if (value.attempts >= 2 || !active(value)) return
        val reason = blocked() ?: power.blocked()
        if (reason != null) {
            release()
            event("wake_blocked", mapOf("reason" to reason))
            if (reason != "entitlement_unknown") cancel(occurrence, reason)
            return
        }
        if (power.interactive()) {
            if (!value.interactiveSeen) write(value.copy(interactiveSeen = true))
            return
        }
        val now = clock.wallTimeMillis()
        if (value.attempts > 0 && (!value.interactiveSeen || now < value.notBefore)) return
        val duration = profile().wakeDurationMillis.coerceIn(1, MAX_WAKE_MILLIS)
        val next = if (value.attempts == 0) now + duration + REWAKE_MARGIN else 0L
        // Persist before acquire; uncertain failures consume the claim instead of risking a third wake.
        val claimed = value.copy(attempts = value.attempts + 1, notBefore = next,
            checkpointAt = next, checkpointPending = next > 0, deferrals = 0)
        write(claimed)
        if (closed || current(occurrence)?.attempts != claimed.attempts || !active(claimed) || blocked() != null) return
        release()
        val token = ++leaseToken
        event("wake_requested", mapOf("attempt" to claimed.attempts.toString(), "duration_ms" to duration.toString(), "trigger" to trigger))
        val acquired = try { power.acquire(duration) } catch (e: Exception) {
            event("wake_failed", mapOf("reason" to e.javaClass.simpleName)); throw e
        }
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
            event("wake_observed", mapOf("observation" to "interactive"))
        }
        if (next > 0) power.schedule(occurrence, next)
        else { power.cancel(occurrence); observer?.close(); observer = null }
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
        if (owned != null) safely("release") { owned.close(); event("wake_released", emptyMap()) }
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
