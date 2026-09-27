package io.onboardkit.ui.splash

/**
 * Counts only time during which the splash is visible. Keep one instance per splash attempt,
 * and pause it while a prompt, another screen, or the background hides the splash.
 * All timestamps must come from the same monotonic clock.
 */
internal class SplashProgress {
    private var accumulatedMs = 0L
    private var activeSinceMs: Long? = null

    val isActive: Boolean get() = activeSinceMs != null

    fun setActive(active: Boolean, nowMs: Long) {
        if (active == isActive) return
        if (active) {
            activeSinceMs = nowMs
        } else {
            accumulatedMs = elapsedMs(nowMs)
            activeSinceMs = null
        }
    }

    fun elapsedMs(nowMs: Long): Long = accumulatedMs +
        (activeSinceMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

    fun percent(nowMs: Long, timeoutMs: Long = 60_000L): Int =
        (fractionAt(elapsedMs(nowMs), timeoutMs) * 100).toInt()

    /** Continuous curve for ValueAnimator; reserve completion for the actual end of the budget. */
    fun fractionAt(elapsedMs: Long, timeoutMs: Long = 60_000L): Float {
        if (elapsedMs >= timeoutMs) return 1f
        val fastPhaseMs = minOf(10_000L, timeoutMs)
        if (elapsedMs <= fastPhaseMs) return (0.9 * elapsedMs / fastPhaseMs).toFloat()
        val remaining = (timeoutMs - elapsedMs).toDouble() / (timeoutMs - fastPhaseMs)
        return (1.0 - 0.1 * remaining * remaining).toFloat().coerceAtMost(0.9999f)
    }
}
