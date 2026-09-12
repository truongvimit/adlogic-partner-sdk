package com.itg.template.ads

import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.AdUnitConfig

/**
 * Typed reads of a placement's configured *flags* — `isEnable`, `enableUaCheck`, `id` — for the
 * debug dashboard and the resume-entry rule.
 *
 * Not needed to load or show an ad: every entry point takes an [AppAdPlacement] key and resolves
 * its own config. This is only for screens that want to display or branch on what the payload says.
 */
private fun unit(key: String): AdUnitConfig = AdRemoteConfig.getInstance().unit(key)

val AdRemoteConfig.Companion.open_resume: AdUnitConfig get() = unit(AppAdPlacement.OPEN_RESUME)

val AdRemoteConfig.Companion.native_onboarding_1_4: AdUnitConfig
    get() = unit(AppAdPlacement.NATIVE_ONBOARDING_1_4)

val AdRemoteConfig.Companion.native_onboarding_fullscreen_1_3: AdUnitConfig
    get() = unit(AppAdPlacement.NATIVE_ONBOARDING_FULLSCREEN_1_3)

val AdRemoteConfig.Companion.native_onboarding_fullscreen_1_4: AdUnitConfig
    get() = unit(AppAdPlacement.NATIVE_ONBOARDING_FULLSCREEN_1_4)

val AdRemoteConfig.Companion.native_home: AdUnitConfig get() = unit(AppAdPlacement.NATIVE_HOME)

val AdRemoteConfig.Companion.native_permission: AdUnitConfig get() = unit(AppAdPlacement.NATIVE_PERMISSION)

val AdRemoteConfig.Companion.inter_onboarding: AdUnitConfig get() = unit(AppAdPlacement.INTER_ONBOARDING)

val AdRemoteConfig.Companion.native_welcome: AdUnitConfig get() = unit(AppAdPlacement.NATIVE_WELCOME)

val AdRemoteConfig.Companion.inter_welcome: AdUnitConfig get() = unit(AppAdPlacement.INTER_WELCOME)

