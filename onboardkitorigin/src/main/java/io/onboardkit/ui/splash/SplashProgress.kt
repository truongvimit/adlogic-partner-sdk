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

    fun percent(nowMs: Long, timeoutMs: Long = 60_000L): Int {
        val elapsed = elapsedMs(nowMs)
        if (elapsed >= timeoutMs) return 100

        val fastPhaseMs = minOf(10_000L, timeoutMs)
        if (elapsed <= fastPhaseMs) return (90.0 * elapsed / fastPhaseMs).toInt()

        val slowFraction = (elapsed - fastPhaseMs).toDouble() / (timeoutMs - fastPhaseMs)
        val remainingFraction = 1.0 - slowFraction
        // Ease out over the remaining budget, reserving 100% for actual completion.
        return (100.0 - 10.0 * remainingFraction * remainingFraction).toInt().coerceAtMost(99)
    }
}
