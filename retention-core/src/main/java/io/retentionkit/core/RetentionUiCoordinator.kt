package io.retentionkit.core

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.UUID

sealed class RetentionUiLeaseResult {
    data class Acquired(val lease: RetentionUiLease) : RetentionUiLeaseResult()
    data class Blocked(val reason: RetentionSuppressionReason) : RetentionUiLeaseResult()
}

/** A bounded, revocable UI reservation; never retains the Activity strongly. */
class RetentionUiLease internal constructor(val token: String, val owner: String, private val coordinator: RetentionUiCoordinator) : AutoCloseable {
    fun isValid(): Boolean = coordinator.activity(token) != null
    fun activity(): Activity? = coordinator.activity(token)
    override fun close() = coordinator.release(token)
}

class RetentionUiCoordinator internal constructor(
    private val clock: RetentionClock,
    private val activities: ForegroundActivityProvider,
    private val foreground: () -> Boolean,
    private val onboarding: () -> Boolean,
    private val host: RetentionUiHost = RetentionUiHost.NONE,
    private val report: (Exception) -> Unit = {},
) {
    private data class Reservation(val token: String, val owner: String, val expires: Long,
        val activity: WeakReference<Activity>, var resource: AutoCloseable? = null, var timeout: Runnable? = null)
    private data class Block(val reason: RetentionSuppressionReason, val expires: Long)
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val blocks = mutableMapOf<String, Block>()
    private var reservation: Reservation? = null
    private var closed = false

    fun eligibility(): RetentionEligibility {
        val snapshot = locked { _ -> reasonLocked() to activities.current() }
        snapshot.first?.let { return RetentionEligibility.Blocked(it) }
        val activity = snapshot.second ?: return RetentionEligibility.Blocked(RetentionSuppressionReason.NO_ACTIVITY)
        if (!hostAllows(activity)) return RetentionEligibility.Blocked(RetentionSuppressionReason.HOST_UI)
        // Host callback can synchronously navigate or report new UI. Recheck after it returns.
        val reason = locked { _ -> reasonLocked() ?: if (activities.current() !== activity) RetentionSuppressionReason.NO_ACTIVITY else null }
        return if (reason == null) RetentionEligibility.Allowed else RetentionEligibility.Blocked(reason)
    }

    @JvmOverloads fun acquire(owner: String, durationMillis: Long = 60_000): RetentionUiLeaseResult {
        require(validId(owner)) { "Invalid prompt owner" }
        require(durationMillis in 1..300_000) { "UI lease must expire within five minutes" }
        val eligibility = eligibility()
        if (eligibility is RetentionEligibility.Blocked) return RetentionUiLeaseResult.Blocked(eligibility.reason)
        val token = UUID.randomUUID().toString()
        val blocked = locked { _ ->
            reasonLocked()?.let { return@locked it }
            val activity = activities.current() ?: return@locked RetentionSuppressionReason.NO_ACTIVITY
            reservation = Reservation(token, owner, deadline(durationMillis), WeakReference(activity))
            null
        }
        if (blocked != null) return RetentionUiLeaseResult.Blocked(blocked)
        val resource = try { host.onLeaseAcquired(owner, token, durationMillis) }
        catch (error: Exception) { report(error); release(token); return RetentionUiLeaseResult.Blocked(RetentionSuppressionReason.HOST_UI) }
        val accepted = locked { closing ->
            val entry = reservation
            if (entry?.token != token) { closing.add(resource); false }
            else { entry.resource = resource; true }
        }
        if (!accepted) return RetentionUiLeaseResult.Blocked(RetentionSuppressionReason.HOST_UI)
        // Real delayed release, not just lazy expiry: a missing future eligibility query must not
        // leave a host resource alive. The timeout captures only coordinator + token.
        val timeout = Runnable { release(token) }
        val schedule = locked { _ -> reservation?.takeIf { it.token == token }?.let { it.timeout = timeout; true } ?: false }
        if (schedule) handler.postDelayed(timeout, durationMillis)
        if (activity(token) == null) return RetentionUiLeaseResult.Blocked(RetentionSuppressionReason.HOST_UI)
        return RetentionUiLeaseResult.Acquired(RetentionUiLease(token, owner, this))
    }

    internal fun activity(token: String): Activity? {
        val snapshot = locked { closing ->
            val entry = reservation?.takeIf { it.token == token } ?: return@locked null
            val activity = entry.activity.get()
            if (reasonLocked(includeReservation = false) != null || activity == null || activities.current() !== activity) {
                detachLocked(closing); null
            } else activity
        } ?: return null
        val allowed = hostAllows(snapshot)
        return locked { closing ->
            if (reservation?.token != token) return@locked null
            if (!allowed || reasonLocked(includeReservation = false) != null || activities.current() !== snapshot) {
                detachLocked(closing); null
            } else snapshot
        }
    }

    internal fun release(token: String) = locked { closing -> if (reservation?.token == token) detachLocked(closing) }
    internal fun invalidate() = locked { closing -> detachLocked(closing) }
    internal fun setBlock(owner: String, reason: RetentionSuppressionReason, durationMillis: Long) {
        require(durationMillis in 1..300_000) { "Host suppression must expire within five minutes" }
        locked { closing -> blocks[owner] = Block(reason, deadline(durationMillis)); detachLocked(closing) }
    }
    internal fun removeBlock(owner: String) = locked { _ -> blocks.remove(owner); Unit }
    internal fun externalTransitionActive(): Boolean = locked { _ -> blocks.values.any { it.reason == RetentionSuppressionReason.EXTERNAL_TRANSITION } }
    internal fun canContinueHandoff(token: String, activity: Activity): Boolean {
        fun eligibleLocked(): Boolean = !closed && foreground() && !onboarding() &&
            !activity.isFinishing && !activity.isDestroyed && activities.current() === activity &&
            blocks["external:$token"] != null && blocks.keys.none { it != "external:$token" } && reservation == null
        if (!locked { _ -> eligibleLocked() } || !hostAllows(activity)) return false
        return locked { _ -> eligibleLocked() }
    }
    internal fun shutdown() = locked { closing -> closed = true; detachLocked(closing); blocks.clear() }

    private fun reasonLocked(includeReservation: Boolean = true): RetentionSuppressionReason? = when {
        closed -> RetentionSuppressionReason.NOT_INSTALLED
        !foreground() -> RetentionSuppressionReason.BACKGROUND
        onboarding() -> RetentionSuppressionReason.HOST_UI
        blocks.isNotEmpty() -> blocks.values.first().reason
        activities.current() == null -> RetentionSuppressionReason.NO_ACTIVITY
        includeReservation && reservation != null -> RetentionSuppressionReason.PROMPT_BUSY
        else -> null
    }
    private fun hostAllows(activity: Activity): Boolean = try { host.canPresent(activity) }
        catch (error: Exception) { report(error); false }
    private fun close(resource: AutoCloseable) { try { resource.close() } catch (error: Exception) { report(error) } }
    private fun deadline(duration: Long): Long = clock.elapsedRealtimeMillis().let { now -> if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration }
    private fun detachLocked(closing: MutableList<AutoCloseable>) {
        reservation?.let { entry ->
            entry.timeout?.let(handler::removeCallbacks)
            entry.resource?.let(closing::add)
        }
        reservation = null
    }
    private fun <T> locked(block: (MutableList<AutoCloseable>) -> T): T {
        val closing = mutableListOf<AutoCloseable>()
        val result = synchronized(lock) {
            val now = clock.elapsedRealtimeMillis()
            blocks.entries.removeAll { now >= it.value.expires }
            if (reservation?.let { now >= it.expires } == true) detachLocked(closing)
            block(closing)
        }
        // Adapters can reenter core or release their own other resources without deadlocking us.
        closing.forEach(::close)
        return result
    }
}
