package io.onboardkit.ui.splash

import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision

internal fun resolveNextScreenTiming(
    nativeFsEligible: Boolean,
    isEntry: Boolean,
    remote: () -> NextScreenTiming?,
    hook: () -> NextScreenTiming,
): NextScreenTiming = when {
    nativeFsEligible -> NextScreenTiming.AFTER_AD
    isEntry -> hook()
    else -> remote() ?: hook()
}

internal fun defaultNextScreenTiming(
    entry: SplashEntry?,
    configured: () -> NextScreenTiming?,
    decision: StartDecision?,
): NextScreenTiming {
    if (entry != null) return NextScreenTiming.AFTER_AD
    configured()?.let { return it }
    val opensFirstOpenFlow = (decision as? StartDecision.Start)
        ?.let { it.destination != FlowDestination.QUESTION_OLD_USER } == true
    return if (opensFirstOpenFlow) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD
}

internal fun remainingMs(deadlineMs: Long?, nowMs: Long): Long =
    ((deadlineMs ?: nowMs) - nowMs).coerceAtLeast(0)

internal fun silentSlotWaitMs(
    budgetLeftMs: Long,
    interLoadedAtMs: Long?,
    promptAnsweredAtMs: Long?,
    waitAfterInterMs: Long,
    nowMs: Long,
): Long {
    val loadedAt = interLoadedAtMs ?: return budgetLeftMs
    val deadline = maxOf(loadedAt, promptAnsweredAtMs ?: loadedAt) + waitAfterInterMs
    return minOf(budgetLeftMs, remainingMs(deadline, nowMs))
}

internal fun slotHoldMs(
    minVisibleMs: Long,
    slotLoadedAtMs: Long,
    focusedAtMs: Long,
    budgetLeftMs: Long,
    nowMs: Long,
): Long {
    val onScreenSince = maxOf(slotLoadedAtMs, focusedAtMs)
    return minOf(minVisibleMs - (nowMs - onScreenSince), budgetLeftMs).coerceAtLeast(0)
}

internal fun minDisplayLeftMs(minDisplayMs: Long, adPhaseStartedAtMs: Long, nowMs: Long): Long {
    val target = minDisplayMs.coerceAtLeast(0)
    return (target - (nowMs - adPhaseStartedAtMs)).coerceIn(0, target)
}
