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

    // Ads behavior
    val REUSE_SPLASH_INTER = RemoteKey.BoolKey("ob_reuse_splash_inter", true)
    val ADS_LANGUAGE_NATIVE = RemoteKey.BoolKey("ob_ads_language_native_enabled", true)
    val ADS_CONTENT_NATIVE = RemoteKey.BoolKey("ob_ads_content_native_enabled", true)
    val ADS_FULLSCREEN_NATIVE = RemoteKey.BoolKey("ob_ads_fullscreen_native_enabled", true)
    val ADS_QUESTION_NATIVE = RemoteKey.BoolKey("ob_ads_question_native_enabled", true)
    val ADS_QUESTION_INTER = RemoteKey.BoolKey("ob_ads_question_inter_enabled", true)
    val ADS_SPLASH_INTER_ID = RemoteKey.StringKey("ob_ads_splash_inter_id", "")

    // Timing
    val SPLASH_MIN_DISPLAY_MS = RemoteKey.LongKey("ob_splash_min_display_ms", 3_000)
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
        REUSE_SPLASH_INTER, ADS_LANGUAGE_NATIVE, ADS_CONTENT_NATIVE, ADS_FULLSCREEN_NATIVE,
        ADS_QUESTION_NATIVE, ADS_QUESTION_INTER, ADS_SPLASH_INTER_ID,
        SPLASH_MIN_DISPLAY_MS, SKIP_BUTTON_DELAY_SEC, FULLSCREEN_AUTO_DISMISS_SEC,
        SHOW_SKIP_OB3, SHOW_SKIP_OB5,
        TEMPLATE_CONTENT, TEMPLATE_LANGUAGE, TEMPLATE_QUESTION,
        UI_CONTENT_JSON, UI_DESIGN_TOKENS_JSON, QUESTION_CONFIG_JSON, CONFIG_VERSION,
    )
}
