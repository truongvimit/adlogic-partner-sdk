package io.onboardkit.core.events

import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Intermediate events emitted while the flow runs — the channel the original SDK never exposed. */
sealed interface OnboardingEvent {
    data object FlowStarted : OnboardingEvent
    data class SplashViewed(val elapsedMs: Long) : OnboardingEvent
    data class StepViewed(val stepId: StepId, val index: Int) : OnboardingEvent
    data class StepCompleted(val stepId: StepId, val dwellMs: Long) : OnboardingEvent
    data class LanguageSelected(val code: String) : OnboardingEvent
    data class QuestionAnswered(val answers: List<QuestionAnswer>) : OnboardingEvent
    data class AdShown(val placementName: String) : OnboardingEvent
    data class PaywallShown(val placementName: String) : OnboardingEvent
    data class FlowCompleted(val stepsShown: List<StepId>) : OnboardingEvent
}

internal class EventBus {
    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 32)

    val events: Flow<OnboardingEvent> get() = _events

    fun emit(event: OnboardingEvent) {
        _events.tryEmit(event)
    }
}
