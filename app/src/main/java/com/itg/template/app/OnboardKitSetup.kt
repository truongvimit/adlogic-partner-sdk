package com.itg.template.app

import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdUnitConfig
import io.onboardkit.OnboardingSdk
import io.onboardkit.config.AdFullScreenStepDefinition
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

    /**
     * The pages of this flow, by role rather than by number.
     *
     * Two numbering schemes meet here and they do not line up: remote config counts **content
     * pages** (`native_ob1..3`) while [StepId] counts **positions in the flow**, and the ad-only
     * page sits at position 3 between them. So content page 3 is `StepId.OB4`, and `StepId.OB3`
     * is the ad page — which reads like a typo everywhere except here.
     *
     * | Position | StepId | Ad unit key | What the user sees |
     * |---|---|---|---|
     * | 1 | `OB1` | `native_ob1` | content |
     * | 2 | `OB2` | `native_ob2` | content |
     * | 3 | `OB3` | `native_fs`  | **ad only, full screen** |
     * | 4 | `OB4` | `native_ob3` | content |
     *
     * Remote on/off still follows the position: `ob_enable_step_ob3` hides the ad page.
     */
    private object Page {
        val CONTENT_1 = StepId.OB1
        val CONTENT_2 = StepId.OB2
        val AD_FULL_SCREEN = StepId.OB3
        val CONTENT_3 = StepId.OB4
    }

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
                    Page.CONTENT_1,
                    titleRes = R.string.onboarding_title_1,
                    subtitleRes = R.string.onboarding_des_1,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_1,
                ),
                ContentStepDefinition(
                    Page.CONTENT_2,
                    titleRes = R.string.onboarding_title_2,
                    subtitleRes = R.string.onboarding_des_2,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_2,
                ),
                AdFullScreenStepDefinition(Page.AD_FULL_SCREEN, autoNextEnabled = true),
                ContentStepDefinition(
                    Page.CONTENT_3,
                    titleRes = R.string.onboarding_title_3,
                    subtitleRes = R.string.onboarding_des_3,
                    imageRes = io.onboardkit.R.drawable.ob_img_onboard_sample_4,
                ),
            )
            // ── Which screen spends which remote key ─────────────────────────────────────────
            // This block is the whole mapping; there is no other place a screen picks an ad unit.
            //
            // Each name below is a *base* key. How many ids it actually spends is decided by how
            // many floors exist in remote config for that name — `<key>_high`, `<key>_high1`, …,
            // `<key>` — resolved in that order by AdRemoteConfig.tiersFor. Giving a placement one
            // more floor is a remote-config change, never a code change.
            this.ads = AdsConfig(
                splashBanner = ads?.banner_splash.toBanner(),
                splashInterstitial = ads.interstitial("inter_splash"),
                languageNative = ads.native("native_lang"),
                languageDupNative = ads.native("native_lang_alt"),
                stepNatives = listOfNotNull(
                    ads.native("native_ob1")?.let { Page.CONTENT_1 to it },
                    ads.native("native_ob2")?.let { Page.CONTENT_2 to it },
                    ads.native("native_ob3")?.let { Page.CONTENT_3 to it },
                    ads.native("native_fs")?.let { Page.AD_FULL_SCREEN to it },
                ).toMap(),
                // Used by any page with no key of its own in the map above
                contentStepNative = ads.native("native_ob1"),
                fullScreenStepNative = ads.native("native_fs"),
                ob5Native = ads?.native_onboarding_fullscreen_1_4.toNative(),
                // Declared so app-resume is judged by the same gate as every other placement;
                // leaving it null makes the gate report no_ad_unit instead of staying silent.
                appResume = ads?.open_resume.toInterstitial(),
            )
        }
            .onSuccess { config ->
                OnboardingSdk.configure(config)
                    .onFailure { Timber.e(it, "OnboardKit rejected the config") }
            }
            .onFailure { Timber.e(it, "OnboardKit config invalid") }
    }

    /** `<baseKey>_high` then `<baseKey>`; null when neither floor is configured or enabled. */
    private fun AdRemoteConfig?.native(baseKey: String): NativeAdUnit? =
        this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { NativeAdUnit(tiers = it) }

    private fun AdRemoteConfig?.interstitial(baseKey: String): InterstitialAdUnit? =
        this?.tiersFor(baseKey)?.takeIf { it.isNotEmpty() }?.let { InterstitialAdUnit(tiers = it) }

    private fun AdUnitConfig?.toNative(): NativeAdUnit? =
        this?.takeIf { it.isUsable }?.let { NativeAdUnit(tiers = it.waterfallIds) }

    private fun AdUnitConfig?.toInterstitial(): InterstitialAdUnit? =
        this?.takeIf { it.isUsable }?.let { InterstitialAdUnit(tiers = it.waterfallIds) }

    /** Banners have no waterfall in the SDK — the top tier is the only id that can be used. */
    private fun AdUnitConfig?.toBanner(): BannerAdUnit? =
        this?.takeIf { it.isUsable }?.let { BannerAdUnit(id = it.waterfallIds.first()) }
}
