package io.onboardkit.ads

import android.app.Activity
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.NativeTemplate
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.remote.RemoteFlags

/**
 * The n+1 preload chain: while the user reads screen n, the ad for screen n+1 loads.
 *
 *   splash fetched   → only the ads of the screen the flow is actually about to open
 *   LFO shown        → language native slot 2 (when the second slot is on)
 *   step n selected  → ad of step n+1
 *   last step shown  → OB5 and question
 */
class PreloadChain internal constructor(
    private val provider: OnboardingAdProvider?,
    private val guard: AdsGuard,
    private val config: () -> OnboardKitConfig?,
    private val flags: () -> RemoteFlags,
    private val canShowAdStep: (StepId) -> Boolean = { true },
) {

    /**
     * Splash finished fetching remote. Only the ads of [destination] are requested — a returning
     * user whose flow is already completed gets `null` and therefore no request at all, instead of
     * paying for an LFO and an OB native that will never be shown.
     */
    fun onSplashRemoteReady(activity: Activity, destination: FlowDestination?, resumeIndex: Int) {
        ObLog.d(ObLog.Section.PRELOAD, "splash_ready destination=$destination resumeIndex=$resumeIndex")
        config() ?: return
        when (destination) {
            FlowDestination.LANGUAGE -> {
                preloadNative(activity, AdPlacement.Language1)
                firstEnabledStep()?.let { preloadForStep(activity, it) }
            }

            FlowDestination.ONBOARDING ->
                enabledSteps().getOrNull(resumeIndex)?.let { preloadForStep(activity, it) }

            FlowDestination.QUESTION_NEW_USER,
            FlowDestination.QUESTION_OLD_USER,
            -> preloadQuestion(activity)

            null -> Unit
        }
    }

    /** The LFO is on screen; slot 2 is buffered before the user's first tap swaps it into view. */
    fun onLanguageShown(activity: Activity) {
        val cfg = config() ?: return
        if (cfg.language.secondNativeOnSelectEnabled && flags().enableLanguageNative2) {
            preloadNative(activity, AdPlacement.Language2)
        }
        firstEnabledStep()?.let { preloadForStep(activity, it) }
    }

    /**
     * A language is selected, so the next tap on that same row can raise the confirm modal.
     *
     * Requested on selection rather than on LFO entry: before the first tap the modal is
     * unreachable, and warming it then would spend a request on most users who never re-tap.
     */
    fun onLanguageSelected(activity: Activity) {
        val cfg = config() ?: return
        if (!cfg.language.confirmDialogOnReselectEnabled) return
        if (!flags().showLanguageConfirmDialog) return
        preloadNative(activity, AdPlacement.LanguageConfirm)
    }

    fun onStepSelected(activity: Activity, enabledSteps: List<StepId>, index: Int) {
        val next = enabledSteps.getOrNull(index + 1)
        if (next != null) preloadForStep(activity, next)

        // An ad-only page has no content of its own: arriving there without a filled ad leaves the
        // user staring at a spinner. It gets the longest lead time available — requested from the
        // first page that precedes it, not just from the one immediately before.
        nextAdOnlyStep(enabledSteps, index)?.let { preloadForStep(activity, it) }

        if (next != null) return
        // Last pager step: warm every possible exit. OB5 used to have a preload nobody called, so
        // its native was never ready and the whole screen was unreachable.
        if (flags().enableStepOb5) preloadOb5(activity)
        preloadQuestion(activity)
    }

    private fun nextAdOnlyStep(enabledSteps: List<StepId>, index: Int): StepId? {
        val cfg = config() ?: return null
        return enabledSteps
            .drop(index + 1)
            .firstOrNull { cfg.stepById(it)?.type == StepType.AD_FULL_SCREEN }
    }

    fun preloadQuestion(activity: Activity) {
        val cfg = config() ?: return
        preloadNative(activity, AdPlacement.QuestionNative)
        val interUnit = cfg.ads.questionInterstitial ?: return
        if (guard.skipReason(activity, AdPlacement.QuestionInterstitial) == null) {
            provider?.loadInterstitial(activity, AdPlacement.QuestionInterstitial, interUnit)
        }
    }

    private fun preloadOb5(activity: Activity) {
        preloadNative(activity, AdPlacement.Ob5)
    }

    private fun preloadForStep(activity: Activity, stepId: StepId) {
        val cfg = config() ?: return
        when (cfg.stepById(stepId)?.type) {
            StepType.CONTENT -> preloadNative(activity, AdPlacement.StepNative(stepId))
            StepType.AD_FULL_SCREEN -> preloadNative(activity, AdPlacement.StepFullScreen(stepId))
            null -> Unit
        }
    }

    /**
     * The unit and the layout both come from the same place the screen will read them from, so a
     * buffered ad always matches what the screen asks for.
     */
    private fun preloadNative(activity: Activity, placement: AdPlacement) {
        val adProvider = provider ?: return
        val unit = config()?.ads?.nativeUnitFor(placement)
        if (unit == null) {
            ObLog.w(ObLog.Section.PRELOAD, "${placement.key} skip — no ad unit")
            return
        }
        if (guard.skipReason(activity, placement) != null) return
        if (adProvider.isNativeReady(placement) || adProvider.isNativeLoading(placement)) {
            ObLog.d(ObLog.Section.PRELOAD, "${placement.key} skip — already buffered or in flight")
            return
        }
        ObLog.d(ObLog.Section.PRELOAD, "${placement.key} request tiers=${unit.tierCount}")
        adProvider.preloadNative(
            activity,
            NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement)),
        )
    }

    private fun firstEnabledStep(): StepId? = enabledSteps().firstOrNull()

    /**
     * Premium is not applied here: the guard already declines every preload for those users, and
     * a second copy of the step-filter rule is a second place for it to drift.
     *
     * [canShowAdStep] is applied though — the resume index arrives as an index into the list the
     * pager will build, so preloading against an unfiltered one warms the ad of the wrong page.
     */
    private fun enabledSteps(): List<StepId> {
        val cfg = config() ?: return emptyList()
        return FlowNavigator.enabledSteps(cfg, flags(), canShowAdStep = canShowAdStep)
    }
}
