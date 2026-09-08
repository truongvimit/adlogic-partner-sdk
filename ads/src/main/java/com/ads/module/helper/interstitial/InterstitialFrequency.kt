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
        !InterstitialAutoBuffer.owns(placement) || elapsed(context)

    internal fun groupRemainingMs(): Long {
        val anchor = maxOf(activatedAt, closedAt, failedAt)
        if (anchor < 0) return 0L
        val interval = intervalSeconds().coerceAtLeast(0) * 1_000L
        return (interval - (SystemClock.elapsedRealtime() - anchor)).coerceIn(0L, interval)
    }

    internal fun activate() {
        if (activatedAt < 0) activatedAt = SystemClock.elapsedRealtime()
    }

    internal fun isPresenting(): Boolean = presenting.isNotEmpty()

    internal fun beginShow(placement: String) {
        if (InterstitialAutoBuffer.owns(placement)) presenting += placement
    }

    internal fun endShow(placement: String, closed: Boolean) {
        val ownedAttempt = presenting.remove(placement)
        if (ownedAttempt && closed && InterstitialAutoBuffer.owns(placement)) {
            closedAt = SystemClock.elapsedRealtime()
        }
        if (ownedAttempt) InterstitialAutoBuffer.onGateChanged()
    }

    internal fun onLoadFailed(placement: String) {
        if (!InterstitialAutoBuffer.owns(placement)) return
        failedAt = SystemClock.elapsedRealtime()
        InterstitialAutoBuffer.onGateChanged()
    }

    internal fun reset() {
        activatedAt = -1L
        closedAt = -1L
        failedAt = -1L
        // Existing attempts retain ownership until their terminal callback.
    }
}
