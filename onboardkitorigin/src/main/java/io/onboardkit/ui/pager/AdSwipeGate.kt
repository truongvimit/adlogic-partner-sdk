package io.onboardkit.ui.pager

import io.onboardkit.core.StepHost
import io.onboardkit.core.StepId

/**
 * Pager swipe for a page that waits on its ad. Every visit starts locked and unlocks once the
 * page reports its ad settled. The ad outlives the visit, so a revisit with a settled ad unlocks
 * at once: a kept ad never fires a second impression.
 */
internal class AdSwipeGate(private val stepId: StepId) {

    private var host: StepHost? = null

    var settled = false
        private set

    fun enter(host: StepHost) {
        this.host = host
        host.setAdStepSwipeEnabled(stepId, settled)
    }

    /** Remembered for later visits; unlocks only while a visit is open. */
    fun settle() {
        settled = true
        host?.setAdStepSwipeEnabled(stepId, true)
    }

    /** Ends the visit's unlock: the page was left, or it is completing and must not move twice. */
    fun leave() {
        host?.setAdStepSwipeEnabled(stepId, false)
        host = null
    }

    /** A recreated view loads a new ad. */
    fun reset() {
        leave()
        settled = false
    }
}
