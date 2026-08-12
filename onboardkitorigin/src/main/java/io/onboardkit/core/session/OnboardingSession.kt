package io.onboardkit.core.session

import android.os.Bundle
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory carrier for one onboarding run. Screens read/write here instead of forwarding
 * Intent extras hand-to-hand — the original SDK dropped extras at 4 different hops.
 */
internal class OnboardingSession {

    @Volatile
    var passthrough: Bundle? = null

    @Volatile
    var selectedLanguage: String? = null

    val stepsShown = CopyOnWriteArrayList<StepId>()
    val answers = CopyOnWriteArrayList<QuestionAnswer>()

    /** CAS guard: the completion callback must fire exactly once per run. */
    val finished = AtomicBoolean(false)

    /** Stamped by `OnboardingSdk.start`; end-to-end duration of the whole first-open flow. */
    @Volatile
    var startedAtMs: Long = 0L

    val elapsedMs: Long
        get() = if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs

    fun recordStepShown(stepId: StepId) {
        if (!stepsShown.contains(stepId)) stepsShown.add(stepId)
    }

    /**
     * Seeds the persisted language without clobbering a pick made during this run: the disk read
     * at install time may land after the user has already tapped a language.
     */
    fun seedLanguage(code: String?) {
        if (selectedLanguage == null) selectedLanguage = code
    }

    fun reset() {
        passthrough = null
        selectedLanguage = null
        stepsShown.clear()
        answers.clear()
        finished.set(false)
        startedAtMs = 0L
    }
}
