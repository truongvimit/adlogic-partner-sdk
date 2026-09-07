package io.retentionkit.notifications

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface NotificationDeadlines {
    fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable
}

private object SystemNotificationDeadlines : NotificationDeadlines {
    private val executor = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "retention-wake-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    override fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable {
        val task = executor.schedule({
            try { callback() }
            catch (error: Exception) { Log.e("RetentionKit", "Notification alarm cleanup failed", error) }
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel(false) }
    }
}

/** A real background alarm receipt owns confirmation and ONE raw lease, never a retry interval. */
internal class NotificationAlarmExecution(
    private val finishReceiver: () -> Unit,
    private val deadlines: NotificationDeadlines = SystemNotificationDeadlines,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val guard = Any()
    private val expiresAt = elapsed() + MAX_EXECUTION_MILLIS
    private var retained = false
    private var closed = false
    private var finished = false
    private var acquiring = false
    private var cleanupComplete = false
    private var closeFailure: Exception? = null
    private var raw: AutoCloseable? = null
    private var overallDeadline: AutoCloseable? = null
    private var stageDeadline: AutoCloseable? = null

    init { installDeadline(deadlines.post(MAX_EXECUTION_MILLIS, ::close), overall = true) }

    fun isActive(): Boolean = synchronized(guard) { !closed && elapsed() < expiresAt }

    fun cleanupSucceeded(): Boolean = synchronized(guard) { cleanupComplete && closeFailure == null }

    fun retain(): Boolean {
        val first = synchronized(guard) {
            if (closed || elapsed() >= expiresAt) return false
            if (retained) false else { retained = true; true }
        }
        if (first) installDeadline(deadlines.post(2000, ::close), overall = false)
        return isActive()
    }

    fun acquire(durationMillis: Long, createRaw: () -> AutoCloseable): AutoCloseable? {
        require(durationMillis in 1..MAX_WAKE_MILLIS)
        val duration = synchronized(guard) {
            if (!retained || closed || acquiring || raw != null || elapsed() >= expiresAt) return null
            acquiring = true
            minOf(durationMillis, expiresAt - elapsed()).coerceAtLeast(1)
        }
        try {
            installDeadline(deadlines.post(duration, ::close), overall = false)
            // A deadline racing an in-flight acquire marks it closed. The returned raw handle
            // must close before the receipt finishes; it can never become a late retained lease.
            if (!isActive()) {
                synchronized(guard) { acquiring = false }
                close()
                return null
            }
            val acquired = createRaw()
            synchronized(guard) { raw = acquired; acquiring = false }
            if (!isActive()) { close(); return null }
            return AutoCloseable { close() }
        } catch (error: Exception) {
            synchronized(guard) { acquiring = false }
            close()
            throw error
        }
    }

    private fun installDeadline(handle: AutoCloseable, overall: Boolean) {
        val previous = synchronized(guard) {
            if (closed) { handle.close(); return }
            if (overall) overallDeadline.also { overallDeadline = handle }
            else stageDeadline.also { stageDeadline = handle }
        }
        previous?.close()
    }

    fun finishIfUnused() {
        if (synchronized(guard) { !retained } || !isActive()) close()
    }

    override fun close() {
        val owned = synchronized(guard) {
            closed = true
            if (finished) { closeFailure?.let { throw it }; return }
            if (acquiring) return
            finished = true
            raw.also { raw = null }
        }
        // Deliberately no module monitor, main Handler or telemetry callback before raw close.
        var failure: Exception? = null
        fun cleanup(work: () -> Unit) {
            try { work() } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { owned?.close() }
        cleanup { overallDeadline?.close() }
        cleanup { stageDeadline?.close() }
        cleanup { finishReceiver() }
        synchronized(guard) { closeFailure = failure; cleanupComplete = true }
        failure?.let { throw it }
    }

    private companion object { const val MAX_EXECUTION_MILLIS = 23_000L }
}

/** SCREEN_ON/OFF and other foreground broadcasts cannot transfer their ~10s budget to a 20s lease. */
internal inline fun BroadcastReceiver.withAlarmExecution(intent: Intent, work: (NotificationAlarmExecution?) -> Unit) {
    val pending = if (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND == 0) goAsync() else null
    val execution = pending?.let { NotificationAlarmExecution(it::finish) }
    try { work(execution) } finally { execution?.finishIfUnused() }
}
