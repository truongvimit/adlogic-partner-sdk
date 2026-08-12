package io.onboardkit.core

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

    /** Returns false when already at the first step (caller may then exit or ignore). */
    fun back(): Boolean


    fun finishFlow(reason: FinishReason)

}
