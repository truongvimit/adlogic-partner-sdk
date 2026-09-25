package io.onboardkit.ads

import io.onboardkit.remote.OnboardingSettings
import android.app.Activity
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.flow.FlowDestination
import io.onboardkit.remote.RemoteFlags

/**
 * Native onboarding preload: all configured, eligible steps warm on language selection.
 *
 *   splash ready     → only the ads of the screen the flow is actually about to open
 *   LFO shown        → language native slot 2 (when the second slot is on)
 *   language picked  → all eligible content and fullscreen natives
 *   last step shown  → OB5 and question
 */
class PreloadChain internal constructor(
    private val provider: OnboardingAdProvider?,
    private val guard: AdsGuard,
    private val config: () -> OnboardKitConfig?,
    private val flags: () -> RemoteFlags,
    private val canShowAdStep: (StepId) -> Boolean = { true },
) {

    private val requestedSteps = mutableSetOf<AdPlacement>()
    private var plannedSteps: List<StepDefinition>? = null
    private var splashAttemptId: String? = null
    private var language1HandoffPending = false

    internal fun beginSplashAttempt(id: String) {
        if (splashAttemptId == id) return
        resetStepRequests()
        splashAttemptId = id
        language1HandoffPending = false
    }

    internal fun resetStepRequests() {
        requestedSteps.clear()
        plannedSteps = null
    }

    /**
     * Freeze order/enabled screens at first preload so language exit and pager use the same plan.
     * Ad eligibility remains live: a purchase or placement kill switch must still win.
     */
    internal fun stepDefinitions(isPremium: Boolean = false): List<StepDefinition> {
        val cfg = config() ?: return emptyList()
        val planned = plannedSteps ?: run {
            val snapshot = flags()
            cfg.steps.filter { it.enabled && snapshot.isStepEnabled(it.id) }
        }.also { plannedSteps = it }
        return planned.filterNot {
            it.type == StepType.AD_FULL_SCREEN &&
                ((isPremium && cfg.ads.skipAdOnlyStepsWhenPremium) || !canShowAdStep(it.id))
        }
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
            }

            FlowDestination.ONBOARDING ->
                stepDefinitions().getOrNull(resumeIndex)?.let { preloadForStep(activity, it) }

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
        preloadLanguageSlots(activity, "LFO_SHOWN")
    }

    /**
     * Language selection warms every eligible onboarding native, including both fullscreen slots.
     * The confirm dialog retains its own trigger.
     */
    fun onLanguageSelected(activity: Activity) {
        preloadLanguageSlots(activity, "FIRST_SELECTION")
        stepDefinitions().forEach { preloadForStep(activity, it) }
    }

    /** Gated on the same resolved switches the screen shows by, so a slot that cannot open is never requested. */
    private fun preloadLanguageSlots(activity: Activity, trigger: String) {
        val language = config()?.language ?: return
        if (language.secondNativeOnSelectEnabled && OnboardingSettings.text("lfo.native2.preload_trigger") == trigger) {
            preloadNative(activity, AdPlacement.Language2)
        }
        if (language.confirmDialogOnReselectEnabled && OnboardingSettings.text("lfo.confirm_dialog.native_preload_trigger") == trigger) {
            preloadNative(activity, AdPlacement.LanguageConfirm)
        }
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
        if (index != enabledSteps.lastIndex) return
        // Last pager step: warm every possible exit. OB5 used to have a preload nobody called, so
        // its native was never ready and the whole screen was unreachable.
        if (flags().enableStepOb5 && OnboardingSettings.bool("onboarding.preload.ob5_on_last_step")) preloadOb5(activity)
        if (OnboardingSettings.bool("onboarding.preload.question_on_last_step")) preloadQuestion(activity)
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

    private fun preloadForStep(activity: Activity, step: StepDefinition) {
        when (step.type) {
            StepType.CONTENT -> preloadNative(activity, AdPlacement.StepNative(step.id))
            StepType.AD_FULL_SCREEN -> preloadNative(activity, AdPlacement.StepFullScreen(step.id))
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
        requestNativeOnce(activity, NativeAdRequest(placement, unit, NativeTemplates.layoutForPlacement(placement), allowWhileVisible))
    }

    /** Step requests share one attempt between language preload and screen binding. */
    internal fun requestNativeOnce(activity: Activity, request: NativeAdRequest): Boolean {
        val adProvider = provider ?: return false
        val placement = request.placement
        if (placement is AdPlacement.StepNative || placement is AdPlacement.StepFullScreen) {
            if (!requestedSteps.add(placement)) {
                // Keep the in-flight request; attaching the screen may transfer its focus wait.
                if (!adProvider.isNativeLoading(placement) && !adProvider.isNativeReady(placement)) return false
            }
        }
        adProvider.preloadNative(activity, request)
        return true
    }

}
