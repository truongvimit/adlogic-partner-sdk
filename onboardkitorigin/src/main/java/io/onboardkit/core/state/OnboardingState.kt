package io.onboardkit.core.state

import kotlinx.serialization.Serializable

/**
 * Persisted onboarding progress. Only [isFlowCompleted] skips the first-open flow on a
 * new launch; partial LFO/step progress never resumes a killed onboarding run.
 */
@Serializable
data class OnboardingState(
    val languageSelected: String? = null,
    val lfoCompletedAtMs: Long? = null,
    val lastCompletedStep: String? = null,
    val flowCompletedAtMs: Long? = null,
    val selectedGoals: List<StoredGoal> = emptyList(),
) {
    val isLfoCompleted: Boolean get() = lfoCompletedAtMs != null
    val isFlowCompleted: Boolean get() = flowCompletedAtMs != null
}

@Serializable
data class StoredGoal(val id: String, val title: String)
