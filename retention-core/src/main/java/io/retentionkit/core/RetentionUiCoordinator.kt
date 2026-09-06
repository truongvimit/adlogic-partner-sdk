package io.retentionkit.core

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.UUID

sealed class RetentionUiLeaseResult {
    data class Acquired(val lease: RetentionUiLease) : RetentionUiLeaseResult()
    data class Blocked(val reason: RetentionSuppressionReason) : RetentionUiLeaseResult()
}

/** A bounded, revocable UI reservation; never retains the Activity strongly. */
class RetentionUiLease internal constructor(
    val token: String,
    val owner: String,
    private val coordinator: RetentionUiCoordinator,
) : AutoCloseable {
    fun isValid(): Boolean = coordinator.activity(token) != null
    fun activity(): Activity? = coordinator.activity(token)
    override fun close() = coordinator.release(token)
}

class RetentionUiCoordinator internal constructor(
    private val clock: RetentionClock,
    private val activities: ForegroundActivityProvider,
    private val foreground: () -> Boolean,
    private val onboarding: () -> Boolean,
) {
    private data class Reservation(val token: String, val owner: String, val expires: Long, val activity: WeakReference<Activity>)
    private data class Block(val reason: RetentionSuppressionReason, val expires: Long)
    private val blocks = mutableMapOf<String, Block>()
    private var reservation: Reservation? = null
    private var closed = false

    @Synchronized fun eligibility(): RetentionEligibility {
        prune()
        val reason = when {
            closed -> RetentionSuppressionReason.NOT_INSTALLED
            !foreground() -> RetentionSuppressionReason.BACKGROUND
            onboarding() -> RetentionSuppressionReason.HOST_UI
            blocks.isNotEmpty() -> blocks.values.first().reason
            activities.current() == null -> RetentionSuppressionReason.NO_ACTIVITY
            reservation != null -> RetentionSuppressionReason.PROMPT_BUSY
            else -> null
        }
        return if (reason == null) RetentionEligibility.Allowed else RetentionEligibility.Blocked(reason)
    }

    @JvmOverloads @Synchronized fun acquire(owner: String, durationMillis: Long = 60_000): RetentionUiLeaseResult {
        require(validId(owner)) { "Invalid prompt owner" }
        require(durationMillis in 1..300_000) { "UI lease must expire within five minutes" }
        val eligibility = eligibility()
        if (eligibility is RetentionEligibility.Blocked) return RetentionUiLeaseResult.Blocked(eligibility.reason)
        val activity = activities.current() ?: return RetentionUiLeaseResult.Blocked(RetentionSuppressionReason.NO_ACTIVITY)
        val token = UUID.randomUUID().toString()
        reservation = Reservation(token, owner, deadline(durationMillis), WeakReference(activity))
        return RetentionUiLeaseResult.Acquired(RetentionUiLease(token, owner, this))
    }

    @Synchronized internal fun activity(token: String): Activity? {
        prune()
        val entry = reservation ?: return null
        if (entry.token != token) return null
        val activity = entry.activity.get()
        if (closed || !foreground() || onboarding() || blocks.isNotEmpty() || activity == null || activities.current() !== activity) {
            reservation = null
            return null
        }
        return activity
    }

    @Synchronized internal fun release(token: String) { if (reservation?.token == token) reservation = null }
    @Synchronized internal fun invalidate() { reservation = null }
    @Synchronized internal fun setBlock(owner: String, reason: RetentionSuppressionReason, durationMillis: Long) {
        require(durationMillis in 1..300_000) { "Host suppression must expire within five minutes" }
        blocks[owner] = Block(reason, deadline(durationMillis))
        reservation = null
    }
    @Synchronized internal fun removeBlock(owner: String) { blocks.remove(owner) }
    @Synchronized internal fun externalTransitionActive(): Boolean {
        prune()
        return blocks.values.any { it.reason == RetentionSuppressionReason.EXTERNAL_TRANSITION }
    }
    @Synchronized internal fun shutdown() { closed = true; reservation = null; blocks.clear() }

    private fun deadline(duration: Long): Long = clock.elapsedRealtimeMillis().let { now ->
        if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
    }
    private fun prune() {
        val now = clock.elapsedRealtimeMillis()
        blocks.entries.removeAll { now >= it.value.expires }
        if (reservation?.let { now >= it.expires } == true) reservation = null
    }
}
