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
    private val snapshotReaders = linkedMapOf<RemoteKey<*>, (RemoteFlags) -> String>()

    private fun <T, K : RemoteKey<T>> K.bind(read: (RemoteFlags) -> T): K {
        snapshotReaders[this] = { snapshot -> read(snapshot).toString() }
        return this
    }

    // Kill switches
    val ENABLE_ALL_ADS = RemoteKey.BoolKey("ob_enable_all_ads", true).bind(RemoteFlags::enableAllAds)
    val ENABLE_UI_CONTENT = RemoteKey.BoolKey("ob_enable_ui_content", true).bind(RemoteFlags::enableUiContent)

    // Step gating — order is fixed in code; remote can only toggle
    val ENABLE_STEP_OB1 = RemoteKey.BoolKey("ob_enable_step_ob1", true).bind(RemoteFlags::enableStepOb1)
    val ENABLE_STEP_OB2 = RemoteKey.BoolKey("ob_enable_step_ob2", true).bind(RemoteFlags::enableStepOb2)
    val ENABLE_STEP_OB3 = RemoteKey.BoolKey("ob_enable_step_ob3", true).bind(RemoteFlags::enableStepOb3)
    val ENABLE_STEP_OB4 = RemoteKey.BoolKey("ob_enable_step_ob4", true).bind(RemoteFlags::enableStepOb4)
    val ENABLE_STEP_OB5 = RemoteKey.BoolKey("ob_enable_step_ob5", false).bind(RemoteFlags::enableStepOb5)
    val ENABLE_QUESTION = RemoteKey.BoolKey("ob_enable_question", true).bind(RemoteFlags::enableQuestion)
    val ENABLE_QUESTION_OLD_USER = RemoteKey.BoolKey("ob_enable_question_old_user", false).bind(RemoteFlags::enableQuestionOldUser)

    // Language flow
    /** Second native shown in-place on the LFO after the first language tap. */
    val ENABLE_LANGUAGE_NATIVE_2 = RemoteKey.BoolKey("ob_enable_language_native_2", true).bind(RemoteFlags::enableLanguageNative2)
    val PASS_LFO_IF_COMPLETED = RemoteKey.BoolKey("ob_pass_lfo_if_completed", true).bind(RemoteFlags::passLfoIfCompleted)

    /**
     * The "Confirm Language" modal, raised when the user taps the language already selected.
     *
     * Separate from [ADS_LANGUAGE_CONFIRM_NATIVE]: this one governs the prompt itself, that one
     * only its ad. Turning the ad off leaves a plain confirmation; turning this off removes the
     * re-tap behaviour entirely and a second tap goes back to being inert.
     */
    val SHOW_LANGUAGE_CONFIRM_DIALOG =
        RemoteKey.BoolKey("ob_show_language_confirm_dialog", true).bind(RemoteFlags::showLanguageConfirmDialog)

    /**
     * Animated hand nudging the row the device locale points at, while nothing is selected yet.
     * Purely a UX nudge, so it is safe to switch off remotely without touching the flow.
     */
    val SHOW_LANGUAGE_TAP_HINT = RemoteKey.BoolKey("ob_show_language_tap_hint", true).bind(RemoteFlags::showLanguageTapHint)

    /**
     * Whether the LFO confirm button is on screen before a language is picked. Off hides it until
     * the first tap; the button always comes back once there is a selection, so the screen can
     * never be left without a way out.
     */
    val SHOW_LANGUAGE_CONFIRM_BEFORE_SELECT =
        RemoteKey.BoolKey("ob_show_language_confirm_before_select", true).bind(RemoteFlags::showLanguageConfirmBeforeSelect)
    val LANGUAGE_SUPPORTED_CODES = RemoteKey.StringKey("ob_language_supported_codes", "").bind(RemoteFlags::languageSupportedCodes)

    // Per-placement switches. One key per placement, all AND-ed with ENABLE_ALL_ADS by
    // RemoteFlags — a placement can never out-vote the master kill switch.
    val REUSE_SPLASH_INTER = RemoteKey.BoolKey("ob_reuse_splash_inter", true).bind(RemoteFlags::reuseSplashInter)
    val ADS_SPLASH_BANNER = RemoteKey.BoolKey("ob_ads_splash_banner_enabled", true).bind(RemoteFlags::adsSplashBanner)
    val ADS_SPLASH_INTER = RemoteKey.BoolKey("ob_ads_splash_inter_enabled", true).bind(RemoteFlags::adsSplashInter)
    val ADS_LANGUAGE_NATIVE = RemoteKey.BoolKey("ob_ads_language_native_enabled", true).bind(RemoteFlags::adsLanguageNative)
    val ADS_LANGUAGE_CONFIRM_NATIVE =
        RemoteKey.BoolKey("ob_ads_language_confirm_native_enabled", true).bind(RemoteFlags::adsLanguageConfirmNative)
    val ADS_CONTENT_NATIVE = RemoteKey.BoolKey("ob_ads_content_native_enabled", true).bind(RemoteFlags::adsContentNative)
    val ADS_FULLSCREEN_NATIVE = RemoteKey.BoolKey("ob_ads_fullscreen_native_enabled", true).bind(RemoteFlags::adsFullScreenNative)
    val ADS_QUESTION_NATIVE = RemoteKey.BoolKey("ob_ads_question_native_enabled", true).bind(RemoteFlags::adsQuestionNative)
    val ADS_QUESTION_INTER = RemoteKey.BoolKey("ob_ads_question_inter_enabled", true).bind(RemoteFlags::adsQuestionInter)
    val ADS_APP_RESUME = RemoteKey.BoolKey("ob_ads_app_resume_enabled", true).bind(RemoteFlags::adsAppResume)

    // Splash interstitial ids — the returning-user segment and the SplashEntry keys
    // (`inter_noti` / `inter_widget` / `inter_uninstall`) included — come from the ads config
    // (`inter_splash` / `inter_splash_old_user` / the entry keys) so each keeps its full waterfall.
    // Interstitial interval and click cap belong to the ads module, which owns the counters they
    // read; a second cap over the same store silently subtracted impressions nobody could attribute.

    // Timing
    val SPLASH_MIN_DISPLAY_MS = RemoteKey.LongKey("ob_splash_min_display_ms", 3_000).bind(RemoteFlags::splashMinDisplayMs)

    /**
     * How long the splash waits for its full-screen ad before giving up and moving on.
     *
     * `60 s` is the audited whole-waterfall budget (`LOAD_AD_TIMEOUT`), which is what this has to
     * cover: at 30 s per ad unit anything lower silently denies the lower floors their turn. The
     * audit hard-coded it; here it is remote-tunable per app.
     */
    val SPLASH_AD_BUDGET_MS = RemoteKey.LongKey("ob_splash_ad_budget_ms", 60_000).bind(RemoteFlags::splashAdBudgetMs)

    /**
     * How long the splash holds for its banner/native slot to render before the full-screen ad
     * is allowed to cover it. `0` means do not wait, which is what the audited build shipped.
     */
    val SPLASH_BANNER_WAIT_MS = RemoteKey.LongKey("ob_splash_banner_wait_ms", 0).bind(RemoteFlags::splashBannerWaitMs)

    val SKIP_BUTTON_DELAY_SEC = RemoteKey.LongKey("ob_skip_button_delay_sec", 3).bind(RemoteFlags::skipButtonDelaySec)
    val FULLSCREEN_AUTO_DISMISS_SEC = RemoteKey.LongKey("ob_fullscreen_auto_dismiss_sec", 15).bind(RemoteFlags::fullScreenAutoDismissSec)

    // Skip buttons on ad-only screens
    val SHOW_SKIP_OB3 = RemoteKey.BoolKey("ob_show_skip_ob3", true).bind(RemoteFlags::showSkipOb3)
    val SHOW_SKIP_OB5 = RemoteKey.BoolKey("ob_show_skip_ob5", true).bind(RemoteFlags::showSkipOb5)

    // Server-driven UI payloads
    val UI_CONTENT_JSON = RemoteKey.StringKey("ob_ui_content", "").bind(RemoteFlags::uiContentJson)
    val UI_DESIGN_TOKENS_JSON = RemoteKey.StringKey("ob_ui_design_tokens", "").bind(RemoteFlags::uiDesignTokensJson)
    val QUESTION_CONFIG_JSON = RemoteKey.StringKey("ob_question_config", "").bind(RemoteFlags::questionConfigJson)

    /** Version stamp persisted with the complete snapshot when the local cache is replaced. */
    val CONFIG_VERSION = RemoteKey.LongKey("ob_config_version", 0).bind(RemoteFlags::configVersion)

    /** Every declared key, registered together with its snapshot value for cache persistence. */
    val ALL: List<RemoteKey<*>> = snapshotReaders.keys.toList()

    internal fun cacheValues(snapshot: RemoteFlags): Map<String, String> =
        snapshotReaders.entries.associate { (key, read) -> key.key to read(snapshot) }
}
