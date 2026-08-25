package io.onboardkit.flow

import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.SkipReason
import io.onboardkit.core.StepId
import io.onboardkit.core.state.OnboardingState
import io.onboardkit.remote.RemoteFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowNavigatorTest {

    private val config = onboardKitConfig {
        defaultSteps()
        question = QuestionConfig(
            options = listOf(QuestionOption("a"), QuestionOption("b")),
        )
    }.getOrThrow()

    private val flags = RemoteFlags()

    // State matrix from the architecture report §1.4

    @Test
    fun `fresh install goes to language`() {
        val decision = FlowNavigator.decideStart(OnboardingState(), flags, config)
        assertEquals(
            StartDecision.Start(FlowDestination.LANGUAGE, 0),
            decision,
        )
    }

    @Test
    fun `killed during LFO returns to language`() {
        val state = OnboardingState(languageSelected = "en-US")
        assertEquals(
            StartDecision.Start(FlowDestination.LANGUAGE, 0),
            FlowNavigator.decideStart(state, flags, config),
        )
    }

    @Test
    fun `LFO done resumes onboarding instead of repeating language`() {
        val state = OnboardingState(languageSelected = "en-US", lfoCompletedAtMs = 1L)
        assertEquals(
            StartDecision.Start(FlowDestination.ONBOARDING, 0),
            FlowNavigator.decideStart(state, flags, config),
        )
    }

    @Test
    fun `pass flag off repeats language after LFO`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L)
        val decision =
            FlowNavigator.decideStart(state, flags.copy(passLfoIfCompleted = false), config)
        assertEquals(StartDecision.Start(FlowDestination.LANGUAGE, 0), decision)
    }

    @Test
    fun `checkpoint resumes after last completed step`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L, lastCompletedStep = "ob2")
        val decision = FlowNavigator.decideStart(state, flags, config)
        assertEquals(StartDecision.Start(FlowDestination.ONBOARDING, 2), decision)
    }

    @Test
    fun `checkpoint past the end falls through to question`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L, lastCompletedStep = "ob4")
        val decision = FlowNavigator.decideStart(state, flags, config)
        assertEquals(StartDecision.Start(FlowDestination.QUESTION_NEW_USER, 0), decision)
    }

    @Test
    fun `completed user skips by default`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L, flowCompletedAtMs = 2L)
        assertEquals(
            StartDecision.Skip(SkipReason.ALREADY_COMPLETED),
            FlowNavigator.decideStart(state, flags, config),
        )
    }

    @Test
    fun `completed user sees question when old-user flag on`() {
        val state = OnboardingState(flowCompletedAtMs = 2L)
        val decision =
            FlowNavigator.decideStart(state, flags.copy(enableQuestionOldUser = true), config)
        assertEquals(StartDecision.Start(FlowDestination.QUESTION_OLD_USER, 0), decision)
    }

    @Test
    fun `all steps disabled goes to question`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L)
        val noSteps = flags.copy(
            enableStepOb1 = false,
            enableStepOb2 = false,
            enableStepOb3 = false,
            enableStepOb4 = false,
        )
        assertEquals(
            StartDecision.Start(FlowDestination.QUESTION_NEW_USER, 0),
            FlowNavigator.decideStart(state, noSteps, config),
        )
    }

    @Test
    fun `everything disabled skips with remote reason`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L)
        val allOff = flags.copy(
            enableStepOb1 = false,
            enableStepOb2 = false,
            enableStepOb3 = false,
            enableStepOb4 = false,
            enableQuestion = false,
        )
        assertEquals(
            StartDecision.Skip(SkipReason.DISABLED_BY_REMOTE),
            FlowNavigator.decideStart(state, allOff, config),
        )
    }

    @Test
    fun `disabling one step drops it from order without reordering`() {
        val enabled = FlowNavigator.enabledSteps(config, flags.copy(enableStepOb2 = false))
        assertEquals(listOf(StepId.OB1, StepId.OB3, StepId.OB4), enabled)
    }

    @Test
    fun `premium users get no ad-only page`() {
        // OB3 is the AD_FULL_SCREEN page of the default config
        assertEquals(
            listOf(StepId.OB1, StepId.OB2, StepId.OB4),
            FlowNavigator.enabledSteps(config, flags, isPremium = true),
        )
    }

    @Test
    fun `premium keeps ad-only pages when the app opted out of skipping them`() {
        val keepAll = onboardKitConfig {
            defaultSteps()
            ads = AdsConfig(skipAdOnlyStepsWhenPremium = false)
        }.getOrThrow()
        assertEquals(
            listOf(StepId.OB1, StepId.OB2, StepId.OB3, StepId.OB4),
            FlowNavigator.enabledSteps(keepAll, flags, isPremium = true),
        )
    }

    @Test
    fun `an ad-only page with no ad to show is dropped, not left blank`() {
        assertEquals(
            listOf(StepId.OB1, StepId.OB2, StepId.OB4),
            FlowNavigator.enabledSteps(config, flags, canShowAdStep = { false }),
        )
    }

    @Test
    fun `dropping an ad-only page moves the resume target with it`() {
        // The resume index is an index into the list the pager builds, so both must be computed
        // with the same filter. OB2 is done; the page after it is OB3, the ad page. With no ad to
        // show, resuming has to land on OB4 — reading the index against the unfiltered list is how
        // a resuming user lands one page off.
        val state = OnboardingState(lfoCompletedAtMs = 1L, lastCompletedStep = StepId.OB2.value)
        val noAdStep = { _: StepId -> false }

        val pages = FlowNavigator.enabledSteps(config, flags, canShowAdStep = noAdStep)
        val decision =
            FlowNavigator.decideStart(state, flags, config, canShowAdStep = noAdStep)
                as StartDecision.Start

        assertEquals(StepId.OB4, pages[decision.resumeStepIndex])
        // Unfiltered, the very same index names the ad page instead
        assertEquals(
            StepId.OB3,
            FlowNavigator.enabledSteps(config, flags)[decision.resumeStepIndex],
        )
    }

    @Test
    fun `a checkpoint whose step left the flow resumes after it, not from zero`() {
        // OB3 done, then its ad-only page drops out because there is no ad. The user is one page
        // from the end — resuming at 0 would replay the whole onboarding.
        val state = OnboardingState(lfoCompletedAtMs = 1L, lastCompletedStep = StepId.OB3.value)
        val noAdStep = { _: StepId -> false }
        val pages = FlowNavigator.enabledSteps(config, flags, canShowAdStep = noAdStep)

        val decision =
            FlowNavigator.decideStart(state, flags, config, canShowAdStep = noAdStep)
                as StartDecision.Start

        assertEquals(listOf(StepId.OB1, StepId.OB2, StepId.OB4), pages)
        assertEquals(StepId.OB4, pages[decision.resumeStepIndex])
    }

    @Test
    fun `a checkpoint on the last remaining step lands past the pager`() {
        val state = OnboardingState(lfoCompletedAtMs = 1L, lastCompletedStep = StepId.OB4.value)
        val enabled = listOf(StepId.OB1, StepId.OB2)
        // OB4 is configured but no longer enabled; nothing enabled follows it.
        assertEquals(
            enabled.size,
            FlowNavigator.resumeIndex(state, enabled, listOf(StepId.OB1, StepId.OB2, StepId.OB4)),
        )
    }

    @Test
    fun `unknown checkpoint restarts from zero`() {
        val state = OnboardingState(lastCompletedStep = "deleted_step")
        assertEquals(0, FlowNavigator.resumeIndex(state, listOf(StepId.OB1, StepId.OB2)))
    }

    // Exit resolution priority §1.1

    @Test
    fun `reusable interstitial wins exit priority`() {
        val decision = FlowNavigator.decideExit(
            flags.copy(enableStepOb5 = true),
            config,
            hasReusableSplashInterstitial = true,
            isOb5NativeReady = true,
        )
        assertEquals(ExitDecision.ShowReusedInterstitialThenComplete, decision)
    }

    @Test
    fun `ob5 beats question when its native is ready`() {
        val decision = FlowNavigator.decideExit(
            flags.copy(enableStepOb5 = true),
            config,
            hasReusableSplashInterstitial = false,
            isOb5NativeReady = true,
        )
        assertEquals(ExitDecision.GoToOb5, decision)
    }

    @Test
    fun `question branch does not depend on ob3 state`() {
        val decision = FlowNavigator.decideExit(
            flags,
            config,
            hasReusableSplashInterstitial = false,
            isOb5NativeReady = false,
        )
        assertEquals(ExitDecision.GoToQuestion, decision)
    }

    @Test
    fun `no options left completes`() {
        val noQuestion = onboardKitConfig { defaultSteps() }.getOrThrow()
        val decision = FlowNavigator.decideExit(
            flags,
            noQuestion,
            hasReusableSplashInterstitial = false,
            isOb5NativeReady = false,
        )
        assertEquals(ExitDecision.Complete, decision)
    }

    @Test
    fun `content and ad steps mix in configured order`() {
        val custom = onboardKitConfig {
            steps(
                ContentStepDefinition(StepId("intro")),
                AdFullScreenStepDefinition(StepId("promo")),
                ContentStepDefinition(StepId("finish")),
            )
        }.getOrThrow()
        val enabled = FlowNavigator.enabledSteps(custom, flags)
        assertTrue(enabled.map { it.value } == listOf("intro", "promo", "finish"))
    }
}
