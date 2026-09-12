package com.itg.template.ads

/**
 * Every ad position this app owns, by the key it carries in `ad_config.json`.
 *
 * The placement key is the only thing that tells two positions apart. Ad unit ids do not:
 * this app's JSON declares 45 placements across 8 distinct units — one native test unit
 * serves 25 of them — and production payloads reuse a unit across screens just as freely.
 * Everything the SDK keys by placement — the interstitial cache, the frequency clock, the
 * auto-buffer group, native preload, and every `ad_request` / `ad_impression` / `ad_skipped`
 * the dashboard slices — keys by this string.
 *
 * So it is spelled once, here. A raw string at a call site that drifts from the JSON does not
 * fail: [com.ads.module.config.AdRemoteConfig.unit] logs a warning and returns a disabled
 * placeholder, and the slot silently never fills. A constant makes the same mistake a
 * compile error.
 *
 * Tiers are not listed: the SDK resolves `<key>_high`, `_high1`… from the base key itself.
 */
object AppAdPlacement {
    const val BANNER_HOME = "banner_home"
    const val BANNER_HOME_FIXED = "banner_home_fixed"
    const val BANNER_SPLASH = "banner_splash"
    const val INTER_AFTER_OB3 = "inter_after_ob3"
    const val INTER_BACK = "inter_back"
    const val INTER_ONBOARDING = "inter_onboarding"
    const val INTER_SPLASH = "inter_splash"

    /** Optional floor for returning users; absent from the payload, the splash uses [INTER_SPLASH]. */
    const val INTER_SPLASH_OLD_USER = "inter_splash_old_user"

    const val INTER_WELCOME = "inter_welcome"
    const val NATIVE_CONFIRM_UNINSTALL = "native_confirm_uninstall"
    const val NATIVE_FS = "native_fs"
    const val NATIVE_HOME = "native_home"
    const val NATIVE_LANG = "native_lang"
    const val NATIVE_LANG_ALT = "native_lang_alt"
    const val NATIVE_LANGUAGE_1 = "native_language_1"
    const val NATIVE_LANGUAGE_1_CLICK = "native_language_1_click"
    const val NATIVE_LANGUAGE_2 = "native_language_2"
    const val NATIVE_LANGUAGE_2_CLICK = "native_language_2_click"
    const val NATIVE_OB1 = "native_ob1"
    const val NATIVE_OB2 = "native_ob2"
    const val NATIVE_OB3 = "native_ob3"
    const val NATIVE_ONBOARDING_1_1 = "native_onboarding_1_1"
    const val NATIVE_ONBOARDING_1_4 = "native_onboarding_1_4"
    const val NATIVE_ONBOARDING_2_1 = "native_onboarding_2_1"
    const val NATIVE_ONBOARDING_2_4 = "native_onboarding_2_4"
    const val NATIVE_ONBOARDING_FULLSCREEN_1_3 = "native_onboarding_fullscreen_1_3"
    const val NATIVE_ONBOARDING_FULLSCREEN_1_4 = "native_onboarding_fullscreen_1_4"
    const val NATIVE_ONBOARDING_FULLSCREEN_2_3 = "native_onboarding_fullscreen_2_3"
    const val NATIVE_ONBOARDING_FULLSCREEN_2_4 = "native_onboarding_fullscreen_2_4"
    const val NATIVE_PERMISSION = "native_permission"
    const val NATIVE_POPUP_LANG = "native_popup_lang"
    const val NATIVE_SURVEY = "native_survey"
    const val NATIVE_UNINSTALL = "native_uninstall"
    const val NATIVE_WELCOME = "native_welcome"
    const val OPEN_RESUME = "open_resume"
    const val REWARD_EXAMPLE = "reward_example"
}
