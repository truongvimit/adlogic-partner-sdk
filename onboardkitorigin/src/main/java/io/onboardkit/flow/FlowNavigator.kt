package io.onboardkit.flow

import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.SkipReason
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
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
 * unit-testable. Only whole-flow completion allows a new launch to bypass language.
 */
object FlowNavigator {

    /** Legacy filtering arguments remain source-compatible; incomplete runs always start at LFO. */
    @Suppress("UNUSED_PARAMETER")
    fun decideStart(
        state: OnboardingState,
        flags: RemoteFlags,
        config: OnboardKitConfig,
        isPremium: Boolean = false,
        canShowAdStep: (StepId) -> Boolean = { true },
    ): StartDecision {
        if (state.isFlowCompleted) {
            return if (flags.enableQuestionOldUser) {
                StartDecision.Start(FlowDestination.QUESTION_OLD_USER, 0)
            } else {
                StartDecision.Skip(SkipReason.ALREADY_COMPLETED)
            }
        }

        // A persisted checkpoint is diagnostic progress, not completion. Every new
        // launch must run Splash -> LFO -> the first enabled onboarding page.
        return StartDecision.Start(FlowDestination.LANGUAGE, 0)
    }

    /**
     * The pages this run will actually show, in configured order.
     *
     * @param isPremium drops the pages whose only content is an ad, honouring
     *   [io.onboardkit.config.AdsConfig.skipAdOnlyStepsWhenPremium]. A paying user staring at an
     *   empty ad slot is the worst version of both.
     * @param canShowAdStep answers whether an ad-only page has an ad to show at all. `false` drops
     *   it: with no ad the page is an empty screen, and an empty screen in the middle of the flow
     *   is worse than one page fewer. Ask [io.onboardkit.ads.AdsGuard.canFillAdOnlyStep] rather
     *   than re-deriving the rule, and pass the same predicate to [decideStart] — the resume index
     *   is an index into this list.
     */
    fun enabledSteps(
        config: OnboardKitConfig,
        flags: RemoteFlags,
        isPremium: Boolean = false,
        canShowAdStep: (StepId) -> Boolean = { true },
    ): List<StepId> {
        val dropAdOnly = isPremium && config.ads.skipAdOnlyStepsWhenPremium
        return config.steps
            .filter { flags.isStepEnabled(it.id) }
            .filterNot {
                it.type == StepType.AD_FULL_SCREEN && (dropAdOnly || !canShowAdStep(it.id))
            }
            .map { it.id }
    }

    /**
     * Checkpoint: resume right after the last completed step.
     *
     * @param configuredOrder every step the config declares, enabled or not. It is what rescues a
     *   checkpoint whose step has since left the flow — remote turned it off, or its ad-only page
     *   had no ad to show. Looking only at [enabledSteps] found nothing and resumed at 0, replaying
     *   the whole onboarding for a user who had almost finished it.
     */
    @JvmStatic
    @JvmOverloads
    fun resumeIndex(
        state: OnboardingState,
        enabledSteps: List<StepId>,
        configuredOrder: List<StepId> = enabledSteps,
    ): Int {
        val last = state.lastCompletedStep ?: return 0
        val index = enabledSteps.indexOfFirst { it.value == last }
        if (index >= 0) return index + 1

        val positionInOrder = configuredOrder.indexOfFirst { it.value == last }
        // A checkpoint naming a step this build no longer declares at all: nothing to anchor to.
        if (positionInOrder < 0) return 0
        val nextStillEnabled = configuredOrder
            .drop(positionInOrder + 1)
            .firstOrNull { it in enabledSteps }
        // Nothing enabled follows it — the user is past the pager, not back at its start.
        return if (nextStillEnabled == null) enabledSteps.size
        else enabledSteps.indexOf(nextStillEnabled)
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
