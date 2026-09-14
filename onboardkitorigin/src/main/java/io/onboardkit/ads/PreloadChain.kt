package io.onboardkit.ads

import io.onboardkit.remote.OnboardingSettings
import android.app.Activity
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
 *   splash ready     → only the ads of the screen the flow is actually about to open
 *   LFO shown        → language native slot 2 (when the second slot is on)
 *   language picked  → first two content natives
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

    private var splashAttemptId: String? = null
    private var language1HandoffPending = false

    internal fun beginSplashAttempt(id: String) {
        if (splashAttemptId == id) return
        splashAttemptId = id
        language1HandoffPending = false
    }

    /** Transfers scheduling metadata only. Ads and terminal outcomes stay in the provider. */
    internal fun takeLanguage1Preload(): Boolean = language1HandoffPending.also {
        language1HandoffPending = false
    }

    /**
     * Splash has settled remote, its configured ad waits and its permission prompt, and is ready to proceed.
     * Only the ads of [destination] are requested — a returning
     * user whose flow is already completed gets `null` and therefore no request at all, instead of
     * paying for an LFO and an OB native that will never be shown.
     */
    @JvmOverloads
    fun onSplashRemoteReady(activity: Activity, destination: FlowDestination?, resumeIndex: Int, language1AlreadyScheduled: Boolean = false) {
        ObLog.d(ObLog.Section.PRELOAD, "splash_ready destination=$destination resumeIndex=$resumeIndex")
        config() ?: return
        when (destination) {
            FlowDestination.LANGUAGE -> {
                if (!language1AlreadyScheduled) preloadLanguage1(activity)
                if (OnboardingSettings.text("onboarding.preload.initial_content_trigger") == "SPLASH_HANDOFF") preloadInitialContent(activity)
            }

            FlowDestination.ONBOARDING ->
                enabledSteps().getOrNull(resumeIndex)?.let { preloadForStep(activity, it) }

            FlowDestination.QUESTION_NEW_USER,
            FlowDestination.QUESTION_OLD_USER,
            -> preloadQuestion(activity)

            null -> Unit
        }
    }

    /** Splash warms only LFO1; content natives wait for language selection. */
    @JvmOverloads
    fun preloadLanguage1(activity: Activity, allowWhileVisible: Boolean = false) {
        language1HandoffPending = true
        preloadNative(activity, AdPlacement.Language1, allowWhileVisible)
    }

    /** Called only after the splash interstitial loads; owns a separate buffer from OB. */
    fun preloadSplashNative(activity: Activity, allowWhileVisible: Boolean = false) {
        preloadNative(activity, AdPlacement.SplashNative, allowWhileVisible)
    }

    /** The LFO is on screen; slot 2 is buffered before the user's first tap swaps it into view. */
    fun onLanguageShown(activity: Activity) {
        val cfg = config() ?: return
        if (OnboardingSettings.text("lfo.native2.preload_trigger") == "LFO_SHOWN" && cfg.language.secondNativeOnSelectEnabled && flags().enableLanguageNative2) {
            preloadNative(activity, AdPlacement.Language2)
        }
        if (OnboardingSettings.text("onboarding.preload.initial_content_trigger") == "LFO_SHOWN") preloadInitialContent(activity)
        if (OnboardingSettings.text("lfo.confirm_dialog.native_preload_trigger") == "LFO_SHOWN") preloadNative(activity, AdPlacement.LanguageConfirm)
    }

    /**
     * The first language selection warms OB1 and OB2 alongside the slot-2 wait/show.
     * Fullscreen stays on the pager-entry chain; the confirm dialog loads on demand.
     */
    fun onLanguageSelected(activity: Activity) {
        if (OnboardingSettings.text("lfo.native2.preload_trigger") == "FIRST_SELECTION") preloadNative(activity, AdPlacement.Language2)
        if (OnboardingSettings.text("lfo.confirm_dialog.native_preload_trigger") == "FIRST_SELECTION") preloadNative(activity, AdPlacement.LanguageConfirm)
        if (OnboardingSettings.text("onboarding.preload.initial_content_trigger") == "FIRST_LANGUAGE_SELECTION") preloadInitialContent(activity)
    }

    private fun preloadInitialContent(activity: Activity) {
        val cfg = config() ?: return
        enabledSteps().filter { cfg.stepById(it)?.type == StepType.CONTENT }
            .take(OnboardingSettings.number("onboarding.preload.initial_content_count").toInt()).forEach { preloadForStep(activity, it) }
    }

    /** Pager entry, including resumed flows. Empty flows never call this. */
    fun onOnboardingShown(activity: Activity) {
        if (!OnboardingSettings.bool("onboarding.exit_interstitial.preload_on_entry")) return
        val unit = config()?.ads?.afterOnboardingInterstitial ?: return
        val placement = AdPlacement.AfterOnboardingInterstitial
        if (guard.skipReason(activity, placement) == null) {
            provider?.loadInterstitial(activity, placement, unit)
        }
    }

    fun onStepSelected(activity: Activity, enabledSteps: List<StepId>, index: Int) {
        val next = enabledSteps.getOrNull(index + 1)
        if (next != null && OnboardingSettings.bool("onboarding.preload.next_step_enabled")) preloadForStep(activity, next)

        // An ad-only page has no content of its own: arriving there without a filled ad leaves the
        // user staring at a spinner. It gets the longest lead time available — requested from the
        // first page that precedes it, not just from the one immediately before.
        nextAdOnlyStep(enabledSteps, index)?.takeIf { OnboardingSettings.bool("onboarding.preload.upcoming_fullscreen_enabled") }?.let { preloadForStep(activity, it) }

        if (next != null) return
        // Last pager step: warm every possible exit. OB5 used to have a preload nobody called, so
        // its native was never ready and the whole screen was unreachable.
        if (flags().enableStepOb5 && OnboardingSettings.bool("onboarding.preload.ob5_on_last_step")) preloadOb5(activity)
        if (OnboardingSettings.bool("onboarding.preload.question_on_last_step")) preloadQuestion(activity)
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
    private fun preloadNative(activity: Activity, placement: AdPlacement, allowWhileVisible: Boolean = false) {
        val adProvider = provider ?: return
        val unit = config()?.ads?.nativeUnitFor(placement)
        if (unit == null) {
            ObLog.w(ObLog.Section.PRELOAD, "${placement.key} skip — no ad unit")
            return
        }
        if (guard.skipReason(activity, placement) != null) return
        if (adProvider.isNativeReady(placement)) {
            ObLog.d(ObLog.Section.PRELOAD, "${placement.key} skip — already buffered")
            return
        }
        // Also call for a queued/in-flight preload: the provider joins the network request or
        // transfers a foreground wait from the departing splash to the destination's owner.
        ObLog.d(ObLog.Section.PRELOAD, "${placement.key} request/join tiers=${unit.tierCount}")
        adProvider.preloadNative(
            activity,
            NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement), allowWhileVisible),
        )
    }

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
