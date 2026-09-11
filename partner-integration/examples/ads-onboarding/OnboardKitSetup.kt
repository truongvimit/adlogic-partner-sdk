package com.example.app

import com.ads.module.config.AdRemoteConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.*
import io.onboardkit.core.StepId

object OnboardKitSetup {
    fun configure() {
        val adConfig = AdRemoteConfig.getInstance()
        val config = onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            steps(
                ContentStepDefinition(
                    StepId.OB1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = R.drawable.img_onboard_sample_1,
                ),
                ContentStepDefinition(
                    StepId.OB2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = R.drawable.img_onboard_sample_2,
                ),
                AdFullScreenStepDefinition(StepId.OB3),
                ContentStepDefinition(
                    StepId.OB4,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = R.drawable.img_onboard_sample_4,
                ),
            )
            ads = AdsConfig(
                splashBanner = adConfig.unit(AppAdPlacement.BANNER_SPLASH)
                    .takeIf { it.isUsable }?.let { BannerAdUnit(it.waterfallIds.first()) },
                splashInterstitial = adConfig.interstitial(AppAdPlacement.INTER_SPLASH),
                languageNative = adConfig.native(AppAdPlacement.NATIVE_LANG),
                languageDupNative = adConfig.native(AppAdPlacement.NATIVE_LANG_ALT),
                languageConfirmNative = adConfig.native(AppAdPlacement.NATIVE_POPUP_LANG),
                fullScreenStepNative = adConfig.native(AppAdPlacement.NATIVE_FS),
                stepNatives = listOfNotNull(
                    adConfig.native(AppAdPlacement.NATIVE_OB1)?.let { StepId.OB1 to it },
                    adConfig.native(AppAdPlacement.NATIVE_OB2)?.let { StepId.OB2 to it },
                    adConfig.native(AppAdPlacement.NATIVE_OB3)?.let { StepId.OB4 to it },
                ).toMap(),
                afterOnboardingInterstitial = adConfig.interstitial(AppAdPlacement.INTER_AFTER_OB3),
                languageTemplate = adConfig.template(AppAdPlacement.NATIVE_LANG, NativeTemplate.CTA_BOTTOM),
                contentStepTemplate = adConfig.template(AppAdPlacement.NATIVE_OB1, NativeTemplate.CTA_TOP),
            )
        }.getOrThrow()
        OnboardingSdk.configure(config).getOrThrow()
    }

    private fun AdRemoteConfig.template(key: String, fallback: NativeTemplate): NativeTemplate =
        when (unit(key).positionCTA) {
            "TOP" -> NativeTemplate.CTA_TOP
            "BOTTOM" -> NativeTemplate.CTA_BOTTOM
            else -> fallback
        }

    private fun AdRemoteConfig.native(key: String): NativeAdUnit? =
        tiersFor(key).takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

    private fun AdRemoteConfig.interstitial(key: String): InterstitialAdUnit? =
        tiersFor(key).takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }
}
