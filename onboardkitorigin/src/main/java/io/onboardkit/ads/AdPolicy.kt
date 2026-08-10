package io.onboardkit.ads

import android.content.Context
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.StepId
import io.onboardkit.remote.RemoteFlags

/**
 * Central gate for every ad decision. Premium hides ads on ALL screens — including OB5,
 * which the original SDK forgot — and optionally removes ad-only steps entirely.
 */
class AdPolicy internal constructor(
    private val provider: OnboardingAdProvider?,
    private val config: () -> OnboardKitConfig?,
    private val flags: () -> RemoteFlags,
) {

    fun isPremium(context: Context): Boolean =
        provider?.isPremium(context.applicationContext) == true

    private fun adsGloballyOn(context: Context): Boolean {
        val cfg = config() ?: return false
        return provider != null && cfg.ads.enabled && flags().enableAllAds && !isPremium(context)
    }

    fun canShowNative(context: Context, placement: AdPlacement): Boolean {
        if (!adsGloballyOn(context)) return false
        val f = flags()
        return when (placement) {
            AdPlacement.Language1, AdPlacement.Language2 -> f.adsLanguageNative
            is AdPlacement.StepNative -> f.adsContentNative
            is AdPlacement.StepFullScreen, AdPlacement.Ob5 -> f.adsFullScreenNative
            AdPlacement.QuestionNative -> f.adsQuestionNative
            else -> true
        }
    }

    fun canShowInterstitial(context: Context, placement: AdPlacement): Boolean {
        if (!adsGloballyOn(context)) return false
        return when (placement) {
            AdPlacement.QuestionInterstitial -> flags().adsQuestionInter
            else -> true
        }
    }

    fun canShowBanner(context: Context): Boolean = adsGloballyOn(context)

    /** Premium users skip steps that contain nothing but a full-screen ad. */
    fun shouldSkipAdOnlyStep(context: Context, stepId: StepId): Boolean {
        val cfg = config() ?: return false
        if (!cfg.ads.skipAdOnlyStepsWhenPremium) return false
        if (!isPremium(context)) return false
        return cfg.stepById(stepId)?.type == io.onboardkit.core.StepType.AD_FULL_SCREEN ||
            stepId == StepId.OB5
    }
}
