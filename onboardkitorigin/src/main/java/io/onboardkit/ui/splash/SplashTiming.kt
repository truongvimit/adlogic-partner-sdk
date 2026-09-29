package io.onboardkit.ui.splash

import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.flow.StartDecision

internal fun resolveNextScreenTiming(
    nativeFsEligible: Boolean,
    isEntry: Boolean,
    remote: () -> NextScreenTiming?,
    hook: () -> NextScreenTiming,
): NextScreenTiming = remote() ?: when {
    nativeFsEligible -> NextScreenTiming.AFTER_AD
    isEntry -> hook()
    else -> hook()
}

internal fun defaultNextScreenTiming(
    entry: SplashEntry?,
    configured: () -> NextScreenTiming?,
    decision: StartDecision?,
): NextScreenTiming {
    if (entry != null) return NextScreenTiming.AFTER_AD
    configured()?.let { return it }
    // Every SDK screen (LFO, Welcome Back) opens after the ad; only a skip lands under it.
    return if (decision is StartDecision.Start) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD
}

internal fun remainingMs(deadlineMs: Long?, nowMs: Long): Long =
    ((deadlineMs ?: nowMs) - nowMs).coerceAtLeast(0)

internal fun minDisplayLeftMs(minDisplayMs: Long, adPhaseStartedAtMs: Long, nowMs: Long): Long {
    val target = minDisplayMs.coerceAtLeast(0)
    return (target - (nowMs - adPhaseStartedAtMs)).coerceIn(0, target)
}
