package io.onboardkit.flow

import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.SkipReason
import io.onboardkit.core.StepId
import io.onboardkit.core.state.OnboardingState
import io.onboardkit.remote.RemoteFlags

/** Where to go after splash. */
enum class FlowDestination { LANGUAGE, ONBOARDING, QUESTION_NEW_USER, QUESTION_OLD_USER }

sealed interface StartDecision {
    data class Start(val destination: FlowDestination, val resumeStepIndex: Int) : StartDecision
    data class Skip(val reason: SkipReason) : StartDecision
}

/** What happens when the pager runs out of steps. */
sealed interface ExitDecision {
    data object ShowReusedInterstitialThenComplete : ExitDecision
    data object GoToOb5 : ExitDecision
    data object GoToQuestion : ExitDecision
    data object Complete : ExitDecision
}

/**
 * Pure flow decisions from (persisted state, remote flags, config) — no Android types, fully
 * unit-testable. Uses two independent persisted flags plus the per-step checkpoint, fixing
 * the shared-preference-key bug and the restart-from-language loop of the original.
 */
object FlowNavigator {

    fun decideStart(
        state: OnboardingState,
        flags: RemoteFlags,
        config: OnboardKitConfig,
    ): StartDecision {
        if (state.isFlowCompleted) {
            return if (flags.enableQuestionOldUser) {
                StartDecision.Start(FlowDestination.QUESTION_OLD_USER, 0)
            } else {
                StartDecision.Skip(SkipReason.ALREADY_COMPLETED)
            }
        }

        if (!state.isLfoCompleted) {
            return StartDecision.Start(FlowDestination.LANGUAGE, 0)
        }

        // LFO done but flow not finished (user killed the app mid-onboarding)
        if (!flags.passLfoIfCompleted) {
            return StartDecision.Start(FlowDestination.LANGUAGE, 0)
        }

        val enabled = enabledSteps(config, flags)
        if (enabled.isNotEmpty()) {
            val resume = resumeIndex(state, enabled)
            if (resume < enabled.size) {
                return StartDecision.Start(FlowDestination.ONBOARDING, resume)
            }
        }

        if (flags.enableQuestion && config.question != null) {
            return StartDecision.Start(FlowDestination.QUESTION_NEW_USER, 0)
        }

        return StartDecision.Skip(SkipReason.DISABLED_BY_REMOTE)
    }

    /** Fixed order from config; remote can only drop entries, never reorder. */
    fun enabledSteps(config: OnboardKitConfig, flags: RemoteFlags): List<StepId> =
        config.steps.filter { flags.isStepEnabled(it.id) }.map { it.id }

    /** Checkpoint: resume right after the last completed step. */
    fun resumeIndex(state: OnboardingState, enabledSteps: List<StepId>): Int {
        val last = state.lastCompletedStep ?: return 0
        val index = enabledSteps.indexOfFirst { it.value == last }
        return if (index < 0) 0 else index + 1
    }

    /**
     * End-of-pager handoff, priority top-down. The original gated the Question branch on an
     * unrelated "OB3 helper created" flag; here the remote flag alone decides.
     */
    fun decideExit(
        flags: RemoteFlags,
        config: OnboardKitConfig,
        hasReusableSplashInterstitial: Boolean,
        isOb5NativeReady: Boolean,
    ): ExitDecision = when {
        flags.reuseSplashInter && hasReusableSplashInterstitial ->
            ExitDecision.ShowReusedInterstitialThenComplete

        flags.enableStepOb5 && isOb5NativeReady -> ExitDecision.GoToOb5

        flags.enableQuestion && config.question != null -> ExitDecision.GoToQuestion

        else -> ExitDecision.Complete
    }
}
