package io.onboardkit.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract the pager host offers every step. Steps never reach into the Activity directly:
 * progress visibility, navigation and position all go through here.
 */
interface StepHost {
    val currentIndex: StateFlow<Int>
    val totalSteps: StateFlow<Int>

    fun next()

    /** Returns false when already at the first step (caller may then exit or ignore). */
    fun back(): Boolean

    fun skipTo(stepId: StepId)

    fun finishFlow(reason: FinishReason)

    fun setProgressVisible(visible: Boolean)
}
