package com.itg.template.ads

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig

/**
 * This app's placement vocabulary — the keys it expects to find in `ad_config.json`.
 *
 * Optional convenience: the SDK resolves any key with `AdRemoteConfig.getInstance().unit("…")`.
 * Naming them here keeps call sites honest about which placements exist, and a typo becomes a
 * compile error instead of a silently disabled ad.
 */
private fun unit(key: String): AdUnitConfig = AdRemoteConfig.getInstance().unit(key)

val AdRemoteConfig.Companion.inter_splash: AdUnitConfig get() = unit("inter_splash")

val AdRemoteConfig.Companion.banner_splash: AdUnitConfig get() = unit("banner_splash")

val AdRemoteConfig.Companion.open_resume: AdUnitConfig get() = unit("open_resume")

val AdRemoteConfig.Companion.native_onboarding_1_4: AdUnitConfig
    get() = unit("native_onboarding_1_4")

val AdRemoteConfig.Companion.native_onboarding_fullscreen_1_3: AdUnitConfig
    get() = unit("native_onboarding_fullscreen_1_3")

val AdRemoteConfig.Companion.native_onboarding_fullscreen_1_4: AdUnitConfig
    get() = unit("native_onboarding_fullscreen_1_4")

val AdRemoteConfig.Companion.native_home: AdUnitConfig get() = unit("native_home")

val AdRemoteConfig.Companion.native_permission: AdUnitConfig get() = unit("native_permission")

val AdRemoteConfig.Companion.inter_onboarding: AdUnitConfig get() = unit("inter_onboarding")

val AdRemoteConfig.Companion.banner_home: AdUnitConfig get() = unit("banner_home")

val AdRemoteConfig.Companion.banner_home_fixed: AdUnitConfig get() = unit("banner_home_fixed")

val AdRemoteConfig.Companion.native_survey: AdUnitConfig get() = unit("native_survey")

val AdRemoteConfig.Companion.native_confirm_uninstall: AdUnitConfig
    get() = unit("native_confirm_uninstall")

val AdRemoteConfig.Companion.native_welcome: AdUnitConfig get() = unit("native_welcome")

val AdRemoteConfig.Companion.inter_welcome: AdUnitConfig get() = unit("inter_welcome")

val AdRemoteConfig.Companion.reward_example: AdUnitConfig get() = unit("reward_example")
