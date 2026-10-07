package com.example.app

/** App-owned placement keys. Ad unit IDs and waterfall tiers live in the JSON assets. */
object AppAdPlacement {
    // Standard onboarding flow.
    const val BANNER_SPLASH = "banner_splash"

    /** Alternative occupant of the splash bottom slot; `splash.ads.slot_format` picks it or the banner. */
    const val NATIVE_SPLASH = "native_splash"
    const val INTER_SPLASH = "inter_splash"
    const val NATIVE_LANG = "native_lang"
    const val NATIVE_LANG_ALT = "native_lang_alt"
    const val NATIVE_POPUP_LANG = "native_popup_lang"
    const val NATIVE_OB1 = "native_ob1"
    const val NATIVE_OB2 = "native_ob2"
    const val NATIVE_FS = "native_fs"
    const val NATIVE_FULL1 = "native_full1"
    const val NATIVE_FULL2 = "native_full2"
    const val NATIVE_OB3 = "native_ob3"
    const val NATIVE_OB4 = "native_ob4"
    const val INTER_AFTER_OB3 = "inter_after_ob3"

    // Other placements from the example catalog; keep the ones your app uses.
    const val OPEN_RESUME = "open_resume"
    const val NATIVE_ONBOARDING_FULLSCREEN_1_4 = "native_onboarding_fullscreen_1_4"
    const val NATIVE_PERMISSION = "native_permission"
    const val NATIVE_HOME = "native_home"
    const val INTER_ONBOARDING = "inter_onboarding"
    const val BANNER_HOME = "banner_home"
    const val BANNER_HOME_FIXED = "banner_home_fixed"
    const val NATIVE_SURVEY = "native_survey"
    const val NATIVE_CONFIRM_UNINSTALL = "native_confirm_uninstall"
    const val NATIVE_WELCOME = "native_welcome"

    /** Returning-user Welcome Back screen: slot 1 preloaded by the splash, slot 2 swapped in on the first tap. */
    const val NATIVE_WELCOME1 = "native_welcome1"
    const val NATIVE_WELCOME2 = "native_welcome2"
    const val INTER_WELCOME = "inter_welcome"
    const val REWARD_EXAMPLE = "reward_example"
    const val INTER_BACK = "inter_back"
    const val NATIVE_UNINSTALL = "native_uninstall"
}
