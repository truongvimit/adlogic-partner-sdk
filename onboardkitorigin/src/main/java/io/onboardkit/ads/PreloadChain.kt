package io.onboardkit.ads

import android.app.Activity
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.flow.FlowDestination
import io.onboardkit.remote.RemoteFlags

/**
 * The n+1 preload chain: while the user reads screen n, the ad for screen n+1 loads.
 *
 *   splash fetched   → only the ads of the screen the flow is actually about to open
 *   LFO shown        → language native slot 2 (if the second slot is on)
 *   step n selected  → ad of step n+1
 *   last step shown  → question native
 */
class PreloadChain internal constructor(
    private val provider: OnboardingAdProvider?,
    private val policy: AdPolicy,
    private val config: () -> OnboardKitConfig?,
    private val flags: () -> RemoteFlags,
) {

    /**
     * Splash finished fetching remote. Only the ads of [destination] — the screen the flow is
     * really about to open — are requested. A returning user whose flow is already completed
     * gets [destination] = null and therefore no request at all, instead of paying for an LFO
     * and an OB native that will never be shown.
     */
    fun onSplashRemoteReady(activity: Activity, destination: FlowDestination?, resumeIndex: Int) {
        val cfg = config() ?: return
        when (destination) {
            FlowDestination.LANGUAGE -> {
                preloadNative(
                    activity,
                    AdPlacement.Language1,
                    cfg.ads.languageNative,
                    languageLayout(),
                )
                firstEnabledStep()?.let { preloadForStep(activity, it) }
            }

            FlowDestination.ONBOARDING ->
                resumeStep(resumeIndex)?.let { preloadForStep(activity, it) }

            FlowDestination.QUESTION_NEW_USER,
            FlowDestination.QUESTION_OLD_USER,
            -> preloadQuestion(activity)

            null -> Unit
        }
    }

    /**
     * Called once the LFO is on screen. The second language native is requested here so it is
     * buffered before the user's first tap swaps it into view.
     */
    fun onLanguageShown(activity: Activity) {
        val cfg = config() ?: return
        if (cfg.language.secondNativeOnSelectEnabled && flags().enableLanguageNative2) {
            preloadNative(
                activity,
                AdPlacement.Language2,
                cfg.ads.languageDupNative ?: cfg.ads.languageNative,
                languageLayout(),
            )
        }
        firstEnabledStep()?.let { preloadForStep(activity, it) }
    }

    fun onStepSelected(activity: Activity, enabledSteps: List<StepId>, index: Int) {
        enabledSteps.getOrNull(index + 1)?.let { preloadForStep(activity, it) }
            ?: preloadQuestion(activity)
    }

    fun preloadQuestion(activity: Activity) {
        val cfg = config() ?: return
        preloadNative(activity, AdPlacement.QuestionNative, cfg.ads.questionNative, questionLayout())
        val interUnit = cfg.ads.questionInterstitial ?: return
        if (policy.canShowInterstitial(activity, AdPlacement.QuestionInterstitial)) {
            provider?.loadInterstitial(activity, AdPlacement.QuestionInterstitial, interUnit)
        }
    }

    fun preloadOb5(activity: Activity) {
        val cfg = config() ?: return
        preloadNative(
            activity,
            AdPlacement.Ob5,
            cfg.ads.ob5Native ?: cfg.ads.fullScreenStepNative,
            NativeTemplates.layoutFor(io.onboardkit.config.NativeTemplate.FULL_SCREEN),
        )
    }

    fun preloadForStep(activity: Activity, stepId: StepId) {
        val cfg = config() ?: return
        when (cfg.stepById(stepId)?.type) {
            StepType.CONTENT -> preloadNative(
                activity,
                AdPlacement.StepNative(stepId),
                cfg.ads.contentStepNative,
                contentLayout(),
            )

            StepType.AD_FULL_SCREEN -> preloadNative(
                activity,
                AdPlacement.StepFullScreen(stepId),
                cfg.ads.fullScreenStepNative,
                NativeTemplates.layoutFor(io.onboardkit.config.NativeTemplate.FULL_SCREEN),
            )

            null -> Unit
        }
    }

    private fun preloadNative(
        activity: Activity,
        placement: AdPlacement,
        unit: NativeAdUnit?,
        layoutRes: Int,
    ) {
        val adProvider = provider ?: return
        if (unit == null) return
        if (!policy.canShowNative(activity, placement)) return
        if (!adProvider.shouldPreloadNative(placement)) return
        adProvider.preloadNative(activity, NativeAdRequest(placement, unit, layoutRes))
    }

    private fun firstEnabledStep(): StepId? = enabledSteps().firstOrNull()

    private fun resumeStep(index: Int): StepId? = enabledSteps().getOrNull(index)

    private fun enabledSteps(): List<StepId> {
        val cfg = config() ?: return emptyList()
        val f = flags()
        return cfg.steps.filter { f.isStepEnabled(it.id) }.map { it.id }
    }

    private fun languageLayout(): Int {
        val cfg = config() ?: return NativeTemplates.layoutFor(io.onboardkit.config.NativeTemplate.CTA_BOTTOM)
        val template = NativeTemplates.fromRemote(flags().templateLanguage, cfg.ads.languageTemplate)
        return NativeTemplates.layoutFor(template)
    }

    private fun contentLayout(): Int {
        val cfg = config() ?: return NativeTemplates.layoutFor(io.onboardkit.config.NativeTemplate.CTA_TOP)
        val template = NativeTemplates.fromRemote(flags().templateContent, cfg.ads.contentStepTemplate)
        return NativeTemplates.layoutFor(template)
    }

    private fun questionLayout(): Int {
        val cfg = config() ?: return NativeTemplates.layoutFor(io.onboardkit.config.NativeTemplate.CTA_BOTTOM)
        val template = NativeTemplates.fromRemote(flags().templateQuestion, cfg.ads.questionTemplate)
        return NativeTemplates.layoutFor(template)
    }
}
