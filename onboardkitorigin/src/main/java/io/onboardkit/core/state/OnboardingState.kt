package io.onboardkit.core.state

import kotlinx.serialization.Serializable

/**
 * Persisted onboarding progress. Two independent flags (LFO vs whole flow) plus a per-step
 * checkpoint so a user killed mid-flow resumes where they left off instead of restarting.
 */
@Serializable
data class OnboardingState(
    val languageSelected: String? = null,
    val lfoCompletedAtMs: Long? = null,
    val lastCompletedStep: String? = null,
    val flowCompletedAtMs: Long? = null,
    val questionAnswers: List<StoredAnswer> = emptyList(),
) {
    val isLfoCompleted: Boolean get() = lfoCompletedAtMs != null
    val isFlowCompleted: Boolean get() = flowCompletedAtMs != null
}

@Serializable
data class StoredAnswer(val optionId: String, val title: String)
