package com.ads.module.helper.interstitial

import com.ads.module.config.settings.AdBehavior
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.ads.ERainAd

/**
 * Process-local clocks and tap counters for placements owned by AutoBuffer. The clocks stand
 * still while the process is in background, so an interval only runs while the app is in use.
 */
object InterstitialFrequency {
    private var hiddenMs = 0L
    private var hiddenSince = -1L
    private var observingProcess = false

    private var closedAt = -1L
    private var failedAt = -1L

    /** Last dismissal of a counted placement; every AutoBuffer clock waits from it as well. */
    private var countedClosedAt = -1L
    private val presenting = mutableSetOf<String>()
    private val placements = mutableMapOf<String, PlacementClock>()

    private class PlacementClock {
        var closedAt = -1L
        var failedAt = -1L
        var taps = 0
    }

    private fun now(): Long {
        val t = SystemClock.elapsedRealtime()
        return t - hiddenMs - if (hiddenSince < 0) 0L else t - hiddenSince
    }

    private fun observeProcess() {
        if (observingProcess) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { observeProcess() }
            return
        }
        observingProcess = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) hiddenSince = SystemClock.elapsedRealtime()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (hiddenSince >= 0) {
                    hiddenMs += SystemClock.elapsedRealtime() - hiddenSince
                    hiddenSince = -1L
                }
                Lifecycle.Event.ON_STOP -> if (hiddenSince < 0) hiddenSince = SystemClock.elapsedRealtime()
                else -> Unit
            }
        })
    }

    internal fun isIndependent(placement: String): Boolean =
        InterstitialAutoBuffer.owns(placement) && !AdBehavior.bool("interstitial_auto_buffer.shared_config")

    private fun globalIntervalMs(): Long = AdBehavior.number("interstitial.frequency.interval_ms", (ERainAd.getInstance().adConfig?.intervalInterstitialAd ?: 0).coerceAtLeast(0) * 1000L)

    internal fun intervalMs(placement: String): Long =
        if (isIndependent(placement)) {
            InterstitialAutoBuffer.options().intervalMsByPlacement[placement]
                ?: (globalIntervalMs())
        } else globalIntervalMs()

    private fun threshold(placement: String): Int =
        if (InterstitialAutoBuffer.owns(placement)) {
            InterstitialAutoBuffer.options().tapThresholds[placement] ?: 0
        } else 0

    private fun tracks(placement: String): Boolean =
        InterstitialAutoBuffer.owns(placement) || InterstitialAutoBuffer.counts(placement)

    internal fun hasTaps(placement: String): Boolean =
        (placements[placement]?.taps ?: 0) >= threshold(placement)

    /** One tap short of [hasTaps]: the ad is bought before the action that will show it. */
    internal fun hasPreloadTaps(placement: String): Boolean =
        (placements[placement]?.taps ?: 0) >= threshold(placement) - 1

    internal fun recordAction(placement: String) {
        if (!InterstitialAutoBuffer.owns(placement)) return
        val state = placements.getOrPut(placement) { PlacementClock() }
        state.taps = (state.taps + 1).coerceAtMost(threshold(placement))
        InterstitialAutoBuffer.onGateChanged()
    }

    internal fun onShown(placement: String) {
        placements[placement]?.taps = 0
    }

    internal fun preloadRemainingMs(placement: String): Long = remainingMs(placement, preload = true)

    private fun remainingMs(placement: String, preload: Boolean): Long {
        if (!isIndependent(placement)) return groupRemainingMs()
        val state = placements[placement]
        val interval = intervalMs(placement)
        val anchor = maxOf(state?.closedAt ?: -1L, countedClosedAt)
        val cycleAt = if (anchor < 0) 0L else anchor +
            (interval - if (preload) AdBehavior.number("interstitial_auto_buffer.preload_lead_ms") else 0L).coerceAtLeast(0L)
        val failed = state?.failedAt ?: -1L
        val retryAt = if (failed < 0) 0L else failed +
            if (interval > 0L) interval else InterstitialAutoBuffer.IDLE_TICK_MS
        return (maxOf(cycleAt, retryAt) - now()).coerceAtLeast(0L)
    }

    /** Read-only placement clock; unlike canShow, it does not depend on a buffered fill. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun remainingMs(context: Context, placement: String): Long = remainingMs(placement, preload = false)

    internal fun passesShowPolicy(placement: String): Boolean =
        hasTaps(placement) && remainingMs(placement, preload = false) == 0L

    /** Kept for callers doing a pure wall-clock interval calculation. */
    @JvmStatic
    fun remainingMs(nowMs: Long, lastImpressionMs: Long, intervalSeconds: Int): Long {
        if (intervalSeconds <= 0 || lastImpressionMs <= 0L) return 0L
        val intervalMs = intervalSeconds * 1_000L
        return (intervalMs - (nowMs - lastImpressionMs)).coerceIn(0L, intervalMs)
    }

    @JvmStatic
    fun intervalSeconds(): Int = ((globalIntervalMs() + 999) / 1000).toInt()

    /** Time remaining for the entire configured group; background time does not count. */
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
        val anchor = maxOf(closedAt, failedAt, countedClosedAt)
        if (anchor < 0) return 0L
        val interval = globalIntervalMs()
        return (interval - (now() - anchor)).coerceIn(0L, interval)
    }

    internal fun activate() {
        observeProcess()
        InterstitialAutoBuffer.options().placements.forEach { placement ->
            val state = placements.getOrPut(placement) { PlacementClock() }
            state.taps = state.taps.coerceAtMost(threshold(placement))
        }
    }

    internal fun isPresenting(): Boolean = presenting.isNotEmpty()

    internal fun beginShow(placement: String) {
        if (tracks(placement)) presenting += placement
    }

    internal fun endShow(placement: String, closed: Boolean) {
        val trackedAttempt = presenting.remove(placement)
        if (trackedAttempt && closed) {
            observeProcess()
            val now = now()
            when {
                isIndependent(placement) -> placements.getOrPut(placement) { PlacementClock() }.closedAt = now
                InterstitialAutoBuffer.owns(placement) -> closedAt = now
                else -> countedClosedAt = now
            }
        }
        if (trackedAttempt) InterstitialAutoBuffer.onGateChanged()
    }

    internal fun onLoadFailed(placement: String) {
        if (!InterstitialAutoBuffer.owns(placement)) return
        observeProcess()
        if (isIndependent(placement)) {
            placements.getOrPut(placement) { PlacementClock() }.failedAt = now()
        } else failedAt = now()
        InterstitialAutoBuffer.onGateChanged()
    }

    internal fun reset() {
        closedAt = -1L
        failedAt = -1L
        placements.clear()
        // A counted dismissal outlives buffer reconfiguration; existing attempts keep ownership.
    }

    /** [reset] that also forgets the counted dismissal. */
    internal fun resetAll() {
        reset()
        countedClosedAt = -1L
    }
}
