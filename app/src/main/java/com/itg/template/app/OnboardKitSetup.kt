package com.itg.template.app

import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdUnitConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.AdTierStrategy
import io.onboardkit.config.AdsConfig
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.ContentStepDefinition
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.NativeAdUnit
import io.onboardkit.config.SplashConfig
import io.onboardkit.config.onboardKitConfig
import io.onboardkit.core.StepId
import timber.log.Timber

/**
 * Builds the OnboardKit config from the app's AdRemoteConfig. Called at startup with
 * asset defaults and again from splash once remote ad ids are fresh.
 */
object OnboardKitSetup {

    fun configure() {
        val ads = runCatching { AdRemoteConfig.getInstance() }.getOrNull()
        onboardKitConfig {
            splash = SplashConfig(
                logoRes = R.mipmap.ic_launcher,
                appNameRes = R.string.app_name,
            )
            // Same shape as the removed handwritten flow: 4 content pages,
            // full-screen native between pages 3 and 4 (remote-gated via ob_enable_step_ob3)
            steps(
                ContentStepDefinition(
                    StepId.OB1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_1,
                    remoteEnableKey = "ob_enable_step_ob1",
                ),
                ContentStepDefinition(
                    StepId.OB2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_2,
                    remoteEnableKey = "ob_enable_step_ob2",
                ),
                AdFullScreenStepDefinition(
                    StepId.OB3,
                    remoteEnableKey = "ob_enable_step_ob3",
                    autoNextEnabled = true
                ),
                ContentStepDefinition(
                    StepId.OB4,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_4,
                ),
            )
            this.ads = AdsConfig(
                splashInterstitial = ads?.inter_splash.toInterstitial(),
                splashBanner = ads?.banner_splash.toBanner(),
                languageNative = ads?.native_language_1.toNative(),
                languageDupNative = ads?.native_language_2.toNative(),
                contentStepNative = ads?.native_onboarding_1_1.toNative(),
                fullScreenStepNative = ads?.native_onboarding_fullscreen_1_3.toNative(),
                ob5Native = ads?.native_onboarding_fullscreen_1_4.toNative(),
            )
        }
            .onSuccess { config ->
                OnboardingSdk.configure(config)
                    .onFailure { Timber.e(it, "OnboardKit rejected the config") }
            }
            .onFailure { Timber.e(it, "OnboardKit config invalid") }
    }

    private fun AdUnitConfig?.toNative(): NativeAdUnit? =
        this?.takeIf { it.isUsable }
            ?.let { NativeAdUnit(tiers = it.waterfallIds, strategy = it.tierStrategy()) }

    private fun AdUnitConfig?.toInterstitial(): InterstitialAdUnit? =
        this?.takeIf { it.isUsable }
            ?.let { InterstitialAdUnit(tiers = it.waterfallIds, strategy = it.tierStrategy()) }

    /** Banners have no waterfall in the SDK — the top tier is the only id that can be used. */
    private fun AdUnitConfig?.toBanner(): BannerAdUnit? =
        this?.takeIf { it.isUsable }?.let { BannerAdUnit(id = it.waterfallIds.first()) }

    private fun AdUnitConfig.tierStrategy(): AdTierStrategy =
        if (isParallelTiers) AdTierStrategy.PARALLEL else AdTierStrategy.CASCADE
}
