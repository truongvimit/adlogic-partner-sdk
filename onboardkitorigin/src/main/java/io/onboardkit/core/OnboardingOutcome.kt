package io.onboardkit.core

import android.content.Context
import android.os.Bundle

/** Terminal result of the onboarding flow, delivered once via [OnboardingListener]. */
sealed interface OnboardingOutcome {

    data class Completed(
        val selectedLanguage: String?,
        val answers: List<QuestionAnswer>,
        val passthrough: Bundle?,
        val stepsShown: List<StepId>,
    ) : OnboardingOutcome

    data class Skipped(val reason: SkipReason, val passthrough: Bundle?) : OnboardingOutcome

    data class Aborted(val atStep: StepId?) : OnboardingOutcome
}

fun interface OnboardingListener {
    fun onFinished(context: Context, outcome: OnboardingOutcome)
}
