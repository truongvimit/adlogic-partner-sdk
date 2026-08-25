package io.onboardkit.core

import io.onboardkit.core.analytics.StepExit
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract the pager host offers every step. Steps never reach into the Activity directly:
 * progress visibility, navigation and position all go through here.
 */
interface StepHost {
    val currentIndex: StateFlow<Int>
    val totalSteps: StateFlow<Int>

    fun next() = next(null)

    /**
     * @param exitReason how the step was left — the CTA, a skip, auto-next, an unfilled ad. It ends
     * up on the step-completed event, which is the only way to tell a step users finished from one
     * they escaped.
     */
    fun next(exitReason: String?)

    /**
     * An ad-only page has no ad to show — leave it.
     *
     * Separate from [next] because the answer can arrive while the pager is still animating onto
     * the page, and [next] drops calls made then so a CTA double-tap cannot jump two pages. A page
     * whose only content is an ad would sit there empty until the user found the Skip button.
     *
     * @param stepId the page reporting the failure, so a late answer cannot advance a page the
     *   user has already moved on to.
     */
    fun skipAdStep(stepId: StepId) = next(StepExit.AD_FAILED)

    /** Returns false when already at the first step (caller may then exit or ignore). */
    fun back(): Boolean


    fun finishFlow(reason: FinishReason)

}
