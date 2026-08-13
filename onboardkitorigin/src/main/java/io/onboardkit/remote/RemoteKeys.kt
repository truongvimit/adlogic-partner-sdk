package io.onboardkit.remote

/**
 * Type-safe remote key: the default lives next to the key string, so there is no
 * defaults XML to keep in sync. All keys carry the `ob_` prefix to avoid clashing
 * with the host app's own remote config namespace.
 */
sealed class RemoteKey<T>(val key: String, val default: T) {
    class BoolKey(key: String, default: Boolean) : RemoteKey<Boolean>(key, default)
    class StringKey(key: String, default: String) : RemoteKey<String>(key, default)
    class LongKey(key: String, default: Long) : RemoteKey<Long>(key, default)
    class DoubleKey(key: String, default: Double) : RemoteKey<Double>(key, default)
}

object ObRemoteKeys {
    // Kill switches
    val ENABLE_ALL_ADS = RemoteKey.BoolKey("ob_enable_all_ads", true)
    val ENABLE_UI_CONTENT = RemoteKey.BoolKey("ob_enable_ui_content", true)

    // Step gating — order is fixed in code; remote can only toggle
    val ENABLE_STEP_OB1 = RemoteKey.BoolKey("ob_enable_step_ob1", true)
    val ENABLE_STEP_OB2 = RemoteKey.BoolKey("ob_enable_step_ob2", true)
    val ENABLE_STEP_OB3 = RemoteKey.BoolKey("ob_enable_step_ob3", true)
    val ENABLE_STEP_OB4 = RemoteKey.BoolKey("ob_enable_step_ob4", true)
    val ENABLE_STEP_OB5 = RemoteKey.BoolKey("ob_enable_step_ob5", false)
    val ENABLE_QUESTION = RemoteKey.BoolKey("ob_enable_question", true)
    val ENABLE_QUESTION_OLD_USER = RemoteKey.BoolKey("ob_enable_question_old_user", false)

    // Language flow
    /** Second native shown in-place on the LFO after the first language tap. */
    val ENABLE_LANGUAGE_NATIVE_2 = RemoteKey.BoolKey("ob_enable_language_native_2", true)
    val PASS_LFO_IF_COMPLETED = RemoteKey.BoolKey("ob_pass_lfo_if_completed", true)
    val LANGUAGE_SUPPORTED_CODES = RemoteKey.StringKey("ob_language_supported_codes", "")

    // Per-placement switches. One key per placement, all AND-ed with ENABLE_ALL_ADS by
    // RemoteFlags — a placement can never out-vote the master kill switch.
    val REUSE_SPLASH_INTER = RemoteKey.BoolKey("ob_reuse_splash_inter", true)
    val ADS_SPLASH_BANNER = RemoteKey.BoolKey("ob_ads_splash_banner_enabled", true)
    val ADS_SPLASH_INTER = RemoteKey.BoolKey("ob_ads_splash_inter_enabled", true)
    val ADS_LANGUAGE_NATIVE = RemoteKey.BoolKey("ob_ads_language_native_enabled", true)
    val ADS_CONTENT_NATIVE = RemoteKey.BoolKey("ob_ads_content_native_enabled", true)
    val ADS_FULLSCREEN_NATIVE = RemoteKey.BoolKey("ob_ads_fullscreen_native_enabled", true)
    val ADS_QUESTION_NATIVE = RemoteKey.BoolKey("ob_ads_question_native_enabled", true)
    val ADS_QUESTION_INTER = RemoteKey.BoolKey("ob_ads_question_inter_enabled", true)
    val ADS_APP_RESUME = RemoteKey.BoolKey("ob_ads_app_resume_enabled", true)

    // Splash interstitial id override, split by user segment. Blank falls back to the compiled
    // id, so a partner who never sets these keeps exactly the build-time waterfall.
    val ADS_SPLASH_INTER_ID = RemoteKey.StringKey("ob_ads_splash_inter_id", "")
    val ADS_SPLASH_INTER_ID_OLD_USER = RemoteKey.StringKey("ob_ads_splash_inter_id_old_user", "")

    // Frequency. Both default to off: the ads module applies its own caps, and a second cap
    // silently subtracting impressions is the kind of thing nobody finds for a quarter.
    /** Minimum gap between two interstitial impressions. `0` disables the rule. */
    val INTERSTITIAL_INTERVAL_SEC = RemoteKey.LongKey("ob_ads_interstitial_interval_sec", 0)

    /** Clicks per ad unit per 24 h before the placement is skipped. `0` disables the rule. */
    val CLICK_CAP_PER_DAY = RemoteKey.LongKey("ob_ads_click_cap_per_day", 0)

    // Timing
    val SPLASH_MIN_DISPLAY_MS = RemoteKey.LongKey("ob_splash_min_display_ms", 3_000)

    /**
     * How long the splash waits for its full-screen ad before giving up and moving on.
     *
     * `60 s` is the audited whole-waterfall budget (`LOAD_AD_TIMEOUT`), which is what this has to
     * cover: at 30 s per ad unit anything lower silently denies the lower floors their turn. The
     * audit hard-coded it; here it is remote-tunable per app.
     */
    val SPLASH_AD_BUDGET_MS = RemoteKey.LongKey("ob_splash_ad_budget_ms", 60_000)

    /**
     * How long the splash holds for its banner/native slot to render before the full-screen ad
     * is allowed to cover it. `0` means do not wait, which is what the audited build shipped.
     */
    val SPLASH_BANNER_WAIT_MS = RemoteKey.LongKey("ob_splash_banner_wait_ms", 0)

    val SKIP_BUTTON_DELAY_SEC = RemoteKey.LongKey("ob_skip_button_delay_sec", 3)
    val FULLSCREEN_AUTO_DISMISS_SEC = RemoteKey.LongKey("ob_fullscreen_auto_dismiss_sec", 15)

    // Skip buttons on ad-only screens
    val SHOW_SKIP_OB3 = RemoteKey.BoolKey("ob_show_skip_ob3", true)
    val SHOW_SKIP_OB5 = RemoteKey.BoolKey("ob_show_skip_ob5", true)

    // Native templates per placement: cta_top | cta_bottom | compact
    val TEMPLATE_CONTENT = RemoteKey.StringKey("ob_native_template_content", "cta_top")
    val TEMPLATE_LANGUAGE = RemoteKey.StringKey("ob_native_template_language", "cta_bottom")
    val TEMPLATE_QUESTION = RemoteKey.StringKey("ob_native_template_question", "cta_bottom")

    // Server-driven UI payloads
    val UI_CONTENT_JSON = RemoteKey.StringKey("ob_ui_content", "")
    val UI_DESIGN_TOKENS_JSON = RemoteKey.StringKey("ob_ui_design_tokens", "")
    val QUESTION_CONFIG_JSON = RemoteKey.StringKey("ob_question_config", "")

    /** Version stamp: when it changes, the local cache is cleared before syncing. */
    val CONFIG_VERSION = RemoteKey.LongKey("ob_config_version", 0)

    val ALL: List<RemoteKey<*>> = listOf(
        ENABLE_ALL_ADS, ENABLE_UI_CONTENT,
        ENABLE_STEP_OB1, ENABLE_STEP_OB2, ENABLE_STEP_OB3, ENABLE_STEP_OB4, ENABLE_STEP_OB5,
        ENABLE_QUESTION, ENABLE_QUESTION_OLD_USER,
        ENABLE_LANGUAGE_NATIVE_2, PASS_LFO_IF_COMPLETED, LANGUAGE_SUPPORTED_CODES,
        REUSE_SPLASH_INTER, ADS_SPLASH_BANNER, ADS_SPLASH_INTER, ADS_LANGUAGE_NATIVE,
        ADS_CONTENT_NATIVE, ADS_FULLSCREEN_NATIVE, ADS_QUESTION_NATIVE, ADS_QUESTION_INTER,
        ADS_APP_RESUME, ADS_SPLASH_INTER_ID, ADS_SPLASH_INTER_ID_OLD_USER,
        INTERSTITIAL_INTERVAL_SEC, CLICK_CAP_PER_DAY,
        SPLASH_MIN_DISPLAY_MS, SPLASH_AD_BUDGET_MS, SPLASH_BANNER_WAIT_MS,
        SKIP_BUTTON_DELAY_SEC, FULLSCREEN_AUTO_DISMISS_SEC,
        SHOW_SKIP_OB3, SHOW_SKIP_OB5,
        TEMPLATE_CONTENT, TEMPLATE_LANGUAGE, TEMPLATE_QUESTION,
        UI_CONTENT_JSON, UI_DESIGN_TOKENS_JSON, QUESTION_CONFIG_JSON, CONFIG_VERSION,
    )
}
