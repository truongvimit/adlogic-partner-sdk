package com.ads.module.helper.interstitial

import org.junit.Assert.assertEquals
import org.junit.Test

class InterNextActionTest {

    private fun meaning(
        nextAction: InterNextAction = InterNextAction.AfterDismiss,
        committed: Boolean = false,
        completed: Boolean = false,
    ) = meaningOfNextAction(nextAction, committed, completed)

    @Test
    fun `no commit marker is the module refusing by one of its own caps`() {
        // Interval, click counter, "every Nth action": all answered with a bare onNextAction.
        assertEquals(NextActionMeaning.MODULE_CAP, meaning())
        assertEquals(
            NextActionMeaning.MODULE_CAP,
            meaning(nextAction = InterNextAction.UnderAd),
        )
    }

    @Test
    fun `under-ad completes as the ad goes to the screen`() {
        assertEquals(
            NextActionMeaning.NEXT_SCREEN,
            meaning(nextAction = InterNextAction.UnderAd, committed = true),
        )
    }

    @Test
    fun `after-dismiss leaves the outcome to onAdClosed`() {
        // The module fires onNextAction just before onAdClosed in this mode. Completing on it
        // would report the outcome before onClosed said what it was.
        assertEquals(
            NextActionMeaning.IGNORED,
            meaning(nextAction = InterNextAction.AfterDismiss, committed = true),
        )
    }

    @Test
    fun `the onNextAction trailing a show failure is not a second outcome`() {
        // onAdFailedToShow already reported and completed; the bare onNextAction behind it is the
        // same failure, whether or not the ad had been committed.
        assertEquals(NextActionMeaning.IGNORED, meaning(completed = true))
        assertEquals(NextActionMeaning.IGNORED, meaning(committed = true, completed = true))
        assertEquals(
            NextActionMeaning.IGNORED,
            meaning(nextAction = InterNextAction.UnderAd, completed = true),
        )
    }
}
