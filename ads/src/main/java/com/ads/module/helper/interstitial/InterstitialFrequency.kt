package com.ads.module.helper.interstitial

import android.content.Context
import android.os.SystemClock
import com.ads.module.ads.ERainAd

/** One process-local, monotonic clock for the placements owned by AutoBuffer. */
object InterstitialFrequency {
    private var activatedAt = -1L
    private var closedAt = -1L
    private var failedAt = -1L
    private val presenting = mutableSetOf<String>()
    private val placements = mutableMapOf<String, PlacementClock>()
    private var actionsSinceShow = 0

    private class PlacementClock {
        var activatedAt = -1L
        var closedAt = -1L
        var failedAt = -1L
        var taps = 0
    }

    internal fun isIndependent(placement: String): Boolean =
        InterstitialAutoBuffer.owns(placement) &&
            placement in InterstitialAutoBuffer.options().independentIntervalPlacements

    internal fun intervalMs(placement: String): Long =
        if (isIndependent(placement)) {
            InterstitialAutoBuffer.options().intervalMsByPlacement[placement]
                ?: (intervalSeconds().coerceAtLeast(0) * 1_000L)
        } else intervalSeconds().coerceAtLeast(0) * 1_000L

    private fun threshold(placement: String): Int =
        if (InterstitialAutoBuffer.owns(placement)) {
            InterstitialAutoBuffer.options().tapThresholds[placement] ?: 0
        } else 0

    internal fun hasTaps(placement: String): Boolean =
        (placements[placement]?.taps ?: 0) >= threshold(placement)

    internal fun recordAction(placement: String) {
        if (!InterstitialAutoBuffer.owns(placement)) return
        val state = placements.getOrPut(placement) { PlacementClock() }
        state.taps = (state.taps + 1).coerceAtMost(threshold(placement))
        if (isIndependent(placement)) actionsSinceShow = (actionsSinceShow + 1).coerceAtMost(2)
        InterstitialAutoBuffer.onGateChanged()
    }

    internal fun onShown(placement: String) {
        placements[placement]?.taps = 0
        if (isIndependent(placement)) actionsSinceShow = 0
    }

    internal fun preloadRemainingMs(placement: String): Long = remainingMs(placement, preload = true)

    private fun remainingMs(placement: String, preload: Boolean): Long {
        if (!isIndependent(placement)) return groupRemainingMs()
        val state = placements[placement] ?: return 0L
        val interval = intervalMs(placement)
        val anchor = maxOf(state.activatedAt, state.closedAt)
        val cycleAt = if (anchor < 0) 0L else anchor +
            (interval - if (preload) 2_000L else 0L).coerceAtLeast(0L)
        val retryAt = if (state.failedAt < 0) 0L else state.failedAt +
            if (interval > 0L) interval else InterstitialAutoBuffer.options().idleTickMs.coerceAtLeast(1L)
        return (maxOf(cycleAt, retryAt) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /** Read-only placement clock; unlike canShow, it does not depend on a buffered fill. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun remainingMs(context: Context, placement: String): Long = remainingMs(placement, preload = false)

    internal fun passesShowPolicy(placement: String): Boolean =
        hasTaps(placement) && (!isIndependent(placement) || actionsSinceShow >= 2) &&
            remainingMs(placement, preload = false) == 0L

    /** Kept for callers doing a pure wall-clock interval calculation. */
    @JvmStatic
    fun remainingMs(nowMs: Long, lastImpressionMs: Long, intervalSeconds: Int): Long {
        if (intervalSeconds <= 0 || lastImpressionMs <= 0L) return 0L
        val intervalMs = intervalSeconds * 1_000L
        return (intervalMs - (nowMs - lastImpressionMs)).coerceIn(0L, intervalMs)
    }

    @JvmStatic
    fun intervalSeconds(): Int = ERainAd.getInstance().adConfig?.intervalInterstitialAd ?: 0

    /** Time remaining for the entire configured group; background time counts too. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun remainingMs(context: Context): Long = groupRemainingMs()

    @JvmStatic
    fun elapsed(context: Context): Boolean = remainingMs(context) == 0L && presenting.isEmpty()

    @JvmStatic
    fun elapsed(context: Context, placement: String): Boolean =
        !InterstitialAutoBuffer.owns(placement) ||
            (passesShowPolicy(placement) && presenting.isEmpty())

    internal fun groupRemainingMs(): Long {
        val anchor = maxOf(activatedAt, closedAt, failedAt)
        if (anchor < 0) return 0L
        val interval = intervalSeconds().coerceAtLeast(0) * 1_000L
        return (interval - (SystemClock.elapsedRealtime() - anchor)).coerceIn(0L, interval)
    }

    internal fun activate() {
        if (activatedAt < 0) activatedAt = SystemClock.elapsedRealtime()
        InterstitialAutoBuffer.options().placements.forEach { placement ->
            val state = placements.getOrPut(placement) { PlacementClock() }
            if (state.activatedAt < 0) state.activatedAt = SystemClock.elapsedRealtime()
            state.taps = state.taps.coerceAtMost(threshold(placement))
        }
    }

    internal fun isPresenting(): Boolean = presenting.isNotEmpty()

    internal fun beginShow(placement: String) {
        if (InterstitialAutoBuffer.owns(placement)) presenting += placement
    }

    internal fun endShow(placement: String, closed: Boolean) {
        val ownedAttempt = presenting.remove(placement)
        if (ownedAttempt && closed && InterstitialAutoBuffer.owns(placement)) {
            if (isIndependent(placement)) {
                placements.getOrPut(placement) { PlacementClock() }.closedAt = SystemClock.elapsedRealtime()
            } else closedAt = SystemClock.elapsedRealtime()
        }
        if (ownedAttempt) InterstitialAutoBuffer.onGateChanged()
    }

    internal fun onLoadFailed(placement: String) {
        if (!InterstitialAutoBuffer.owns(placement)) return
        if (isIndependent(placement)) {
            placements.getOrPut(placement) { PlacementClock() }.failedAt = SystemClock.elapsedRealtime()
        } else failedAt = SystemClock.elapsedRealtime()
        InterstitialAutoBuffer.onGateChanged()
    }

    internal fun reset() {
        activatedAt = -1L
        closedAt = -1L
        failedAt = -1L
        placements.clear()
        actionsSinceShow = 0
        // Existing attempts retain ownership until their terminal callback.
    }
}
