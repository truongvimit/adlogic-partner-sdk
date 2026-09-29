package io.onboardkit.ui.splash

import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.NextScreenTiming.AFTER_AD
import io.onboardkit.ads.NextScreenTiming.UNDER_AD
import io.onboardkit.core.SkipReason
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTimingTest {
    private val unasked: () -> NextScreenTiming? = { null }
    private val hookUnasked: () -> NextScreenTiming = { NextScreenTiming.AFTER_AD }

    @Test
    fun `first-open flow waits for the splash ad to close`() {
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Start(FlowDestination.LANGUAGE, 0)))
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Start(FlowDestination.ONBOARDING, 2)))
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Start(FlowDestination.QUESTION_NEW_USER, 0)))
    }

    @Test
    fun `launcher start after onboarding opens the destination under the splash ad`() {
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Skip(SkipReason.ALREADY_COMPLETED)))
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)))
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, { null }, StartDecision.Start(FlowDestination.QUESTION_OLD_USER, 0)))
    }

    @Test
    fun `entry launches always wait for the splash ad to close, whatever the setting says`() {
        SplashEntry.entries.forEach { entry ->
            assertEquals(AFTER_AD, defaultNextScreenTiming(entry, unasked, StartDecision.Skip(SkipReason.ALREADY_COMPLETED)))
            assertEquals(AFTER_AD, defaultNextScreenTiming(entry, unasked, StartDecision.Start(FlowDestination.LANGUAGE, 0)))
        }
    }

    @Test
    fun `an explicit setting outranks the destination on a launcher start`() {
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, { UNDER_AD }, StartDecision.Start(FlowDestination.LANGUAGE, 0)))
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, { AFTER_AD }, StartDecision.Skip(SkipReason.ALREADY_COMPLETED)))
    }

    @Test
    fun `unresolved decision follows the app destination fallback`() {
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, { null }, null))
    }

    @Test
    fun `an eligible native_fs falls back to AFTER_AD after remote is absent`() {
        assertEquals(AFTER_AD, resolveNextScreenTiming(nativeFsEligible = true, isEntry = false, unasked, hookUnasked))
        assertEquals(AFTER_AD, resolveNextScreenTiming(nativeFsEligible = true, isEntry = true, unasked, hookUnasked))
    }

    @Test
    fun `an explicit value outranks an eligible native_fs, entries included`() {
        assertEquals(UNDER_AD, resolveNextScreenTiming(nativeFsEligible = true, isEntry = false, { UNDER_AD }, hookUnasked))
        assertEquals(UNDER_AD, resolveNextScreenTiming(nativeFsEligible = true, isEntry = true, { UNDER_AD }, hookUnasked))
    }

    @Test
    fun `an entry uses the hook after remote is absent`() {
        assertEquals(UNDER_AD, resolveNextScreenTiming(nativeFsEligible = false, isEntry = true, unasked) { UNDER_AD })
    }

    @Test
    fun `a launcher start takes an explicit remote value before the hook`() {
        assertEquals(AFTER_AD, resolveNextScreenTiming(nativeFsEligible = false, isEntry = false, { AFTER_AD }, hookUnasked))
        assertEquals(UNDER_AD, resolveNextScreenTiming(nativeFsEligible = false, isEntry = false, { null }) { UNDER_AD })
    }

    @Test
    fun `the minimum display counts from the first request and never goes negative`() {
        assertEquals(2_000, minDisplayLeftMs(3_000, adPhaseStartedAtMs = 9_000, nowMs = 10_000))
        assertEquals(0, minDisplayLeftMs(3_000, adPhaseStartedAtMs = 5_000, nowMs = 10_000))
        assertEquals(0, minDisplayLeftMs(-1, adPhaseStartedAtMs = 10_000, nowMs = 10_000))
        assertEquals(3_000, minDisplayLeftMs(3_000, adPhaseStartedAtMs = 12_000, nowMs = 10_000))
    }

    @Test
    fun `a deadline left in the past or never armed leaves nothing to wait`() {
        assertEquals(400, remainingMs(deadlineMs = 10_400, nowMs = 10_000))
        assertEquals(0, remainingMs(deadlineMs = 9_000, nowMs = 10_000))
        assertEquals(0, remainingMs(deadlineMs = null, nowMs = 10_000))
    }
}
