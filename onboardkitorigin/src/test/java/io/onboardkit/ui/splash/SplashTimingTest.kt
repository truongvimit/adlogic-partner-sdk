package io.onboardkit.ui.splash

import io.onboardkit.ads.NextScreenTiming.AFTER_AD
import io.onboardkit.ads.NextScreenTiming.UNDER_AD
import io.onboardkit.core.SkipReason
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTimingTest {

    @Test
    fun `first-open flow waits for the splash ad to close`() {
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, StartDecision.Start(FlowDestination.LANGUAGE, 0)))
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, StartDecision.Start(FlowDestination.ONBOARDING, 2)))
        assertEquals(AFTER_AD, defaultNextScreenTiming(null, StartDecision.Start(FlowDestination.QUESTION_NEW_USER, 0)))
    }

    @Test
    fun `launcher start after onboarding opens the destination under the splash ad`() {
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, StartDecision.Skip(SkipReason.ALREADY_COMPLETED)))
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)))
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, StartDecision.Start(FlowDestination.QUESTION_OLD_USER, 0)))
    }

    @Test
    fun `entry launches always wait for the splash ad to close`() {
        SplashEntry.entries.forEach { entry ->
            assertEquals(AFTER_AD, defaultNextScreenTiming(entry, StartDecision.Skip(SkipReason.ALREADY_COMPLETED)))
            assertEquals(AFTER_AD, defaultNextScreenTiming(entry, StartDecision.Start(FlowDestination.LANGUAGE, 0)))
        }
    }

    @Test
    fun `unresolved decision follows the app destination fallback`() {
        assertEquals(UNDER_AD, defaultNextScreenTiming(null, null))
    }
}
