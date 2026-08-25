package io.onboardkit.remote

import io.onboardkit.core.StepId

/** Where a remote value came from. */
interface RemoteValueReader {
    /** Returns null when the backend has no value for [key] — the key default then applies. */
    fun string(key: String): String?
}

/**
 * Immutable snapshot of every remote flag. Built once per sync; UI code reads fields,
 * never SharedPreferences — the original re-hit disk on every getter call.
 */
data class RemoteFlags(
    val enableAllAds: Boolean = ObRemoteKeys.ENABLE_ALL_ADS.default,
    val enableUiContent: Boolean = ObRemoteKeys.ENABLE_UI_CONTENT.default,
    val enableStepOb1: Boolean = ObRemoteKeys.ENABLE_STEP_OB1.default,
    val enableStepOb2: Boolean = ObRemoteKeys.ENABLE_STEP_OB2.default,
    val enableStepOb3: Boolean = ObRemoteKeys.ENABLE_STEP_OB3.default,
    val enableStepOb4: Boolean = ObRemoteKeys.ENABLE_STEP_OB4.default,
    val enableStepOb5: Boolean = ObRemoteKeys.ENABLE_STEP_OB5.default,
    val enableQuestion: Boolean = ObRemoteKeys.ENABLE_QUESTION.default,
    val enableQuestionOldUser: Boolean = ObRemoteKeys.ENABLE_QUESTION_OLD_USER.default,
    val enableLanguageNative2: Boolean = ObRemoteKeys.ENABLE_LANGUAGE_NATIVE_2.default,
    val passLfoIfCompleted: Boolean = ObRemoteKeys.PASS_LFO_IF_COMPLETED.default,
    val showLanguageTapHint: Boolean = ObRemoteKeys.SHOW_LANGUAGE_TAP_HINT.default,
    val showLanguageConfirmBeforeSelect: Boolean =
        ObRemoteKeys.SHOW_LANGUAGE_CONFIRM_BEFORE_SELECT.default,
    val languageSupportedCodes: String = ObRemoteKeys.LANGUAGE_SUPPORTED_CODES.default,
    val reuseSplashInter: Boolean = ObRemoteKeys.REUSE_SPLASH_INTER.default,
    val adsSplashBanner: Boolean = ObRemoteKeys.ADS_SPLASH_BANNER.default,
    val adsSplashInter: Boolean = ObRemoteKeys.ADS_SPLASH_INTER.default,
    val adsLanguageNative: Boolean = ObRemoteKeys.ADS_LANGUAGE_NATIVE.default,
    val adsContentNative: Boolean = ObRemoteKeys.ADS_CONTENT_NATIVE.default,
    val adsFullScreenNative: Boolean = ObRemoteKeys.ADS_FULLSCREEN_NATIVE.default,
    val adsQuestionNative: Boolean = ObRemoteKeys.ADS_QUESTION_NATIVE.default,
    val adsQuestionInter: Boolean = ObRemoteKeys.ADS_QUESTION_INTER.default,
    val adsAppResume: Boolean = ObRemoteKeys.ADS_APP_RESUME.default,
    val splashMinDisplayMs: Long = ObRemoteKeys.SPLASH_MIN_DISPLAY_MS.default,
    val splashAdBudgetMs: Long = ObRemoteKeys.SPLASH_AD_BUDGET_MS.default,
    val splashBannerWaitMs: Long = ObRemoteKeys.SPLASH_BANNER_WAIT_MS.default,
    val skipButtonDelaySec: Long = ObRemoteKeys.SKIP_BUTTON_DELAY_SEC.default,
    val fullScreenAutoDismissSec: Long = ObRemoteKeys.FULLSCREEN_AUTO_DISMISS_SEC.default,
    val showSkipOb3: Boolean = ObRemoteKeys.SHOW_SKIP_OB3.default,
    val showSkipOb5: Boolean = ObRemoteKeys.SHOW_SKIP_OB5.default,
    val uiContentJson: String = ObRemoteKeys.UI_CONTENT_JSON.default,
    val uiDesignTokensJson: String = ObRemoteKeys.UI_DESIGN_TOKENS_JSON.default,
    val questionConfigJson: String = ObRemoteKeys.QUESTION_CONFIG_JSON.default,
    val configVersion: Long = ObRemoteKeys.CONFIG_VERSION.default,
) {

    fun isStepEnabled(stepId: StepId): Boolean = when (stepId) {
        StepId.OB1 -> enableStepOb1
        StepId.OB2 -> enableStepOb2
        StepId.OB3 -> enableStepOb3
        StepId.OB4 -> enableStepOb4
        StepId.OB5 -> enableStepOb5
        StepId.QUESTION -> enableQuestion
        else -> true
    }

    /**
     * One line with every flag that can stop an ad, for the flow log.
     *
     * The point is to see the whole permission surface in a single logcat entry rather than
     * inferring it from which ads failed to appear.
     */
    fun adSummary(): String = "flags allAds=$enableAllAds " +
        "splashBanner=$adsSplashBanner splashInter=$adsSplashInter lang=$adsLanguageNative " +
        "content=$adsContentNative fullScreen=$adsFullScreenNative " +
        "questionNative=$adsQuestionNative questionInter=$adsQuestionInter resume=$adsAppResume " +
        "reuseSplashInter=$reuseSplashInter minDisplayMs=$splashMinDisplayMs " +
        "adBudgetMs=$splashAdBudgetMs bannerWaitMs=$splashBannerWaitMs"

    /** Any tutorial page enabled → the pager flow can show. */
    val anyTutorialStepEnabled: Boolean
        get() = enableStepOb1 || enableStepOb2 || enableStepOb3 || enableStepOb4

    companion object {

        fun from(reader: RemoteValueReader): RemoteFlags {
            fun bool(k: RemoteKey.BoolKey): Boolean =
                reader.string(k.key)?.trim()?.lowercase()
                    ?.let { it == "true" || it == "1" || it == "yes" }
                    ?: k.default

            fun str(k: RemoteKey.StringKey): String =
                reader.string(k.key)?.takeIf { it.isNotBlank() } ?: k.default

            fun long(k: RemoteKey.LongKey): Long =
                reader.string(k.key)?.trim()?.toLongOrNull() ?: k.default

            return RemoteFlags(
                enableAllAds = bool(ObRemoteKeys.ENABLE_ALL_ADS),
                enableUiContent = bool(ObRemoteKeys.ENABLE_UI_CONTENT),
                enableStepOb1 = bool(ObRemoteKeys.ENABLE_STEP_OB1),
                enableStepOb2 = bool(ObRemoteKeys.ENABLE_STEP_OB2),
                enableStepOb3 = bool(ObRemoteKeys.ENABLE_STEP_OB3),
                enableStepOb4 = bool(ObRemoteKeys.ENABLE_STEP_OB4),
                enableStepOb5 = bool(ObRemoteKeys.ENABLE_STEP_OB5),
                enableQuestion = bool(ObRemoteKeys.ENABLE_QUESTION),
                enableQuestionOldUser = bool(ObRemoteKeys.ENABLE_QUESTION_OLD_USER),
                enableLanguageNative2 = bool(ObRemoteKeys.ENABLE_LANGUAGE_NATIVE_2),
                passLfoIfCompleted = bool(ObRemoteKeys.PASS_LFO_IF_COMPLETED),
                showLanguageTapHint = bool(ObRemoteKeys.SHOW_LANGUAGE_TAP_HINT),
                showLanguageConfirmBeforeSelect =
                    bool(ObRemoteKeys.SHOW_LANGUAGE_CONFIRM_BEFORE_SELECT),
                languageSupportedCodes = reader.string(ObRemoteKeys.LANGUAGE_SUPPORTED_CODES.key)
                    ?: ObRemoteKeys.LANGUAGE_SUPPORTED_CODES.default,
                reuseSplashInter = bool(ObRemoteKeys.REUSE_SPLASH_INTER),
                adsSplashBanner = bool(ObRemoteKeys.ADS_SPLASH_BANNER),
                adsSplashInter = bool(ObRemoteKeys.ADS_SPLASH_INTER),
                adsLanguageNative = bool(ObRemoteKeys.ADS_LANGUAGE_NATIVE),
                adsContentNative = bool(ObRemoteKeys.ADS_CONTENT_NATIVE),
                adsFullScreenNative = bool(ObRemoteKeys.ADS_FULLSCREEN_NATIVE),
                adsQuestionNative = bool(ObRemoteKeys.ADS_QUESTION_NATIVE),
                adsQuestionInter = bool(ObRemoteKeys.ADS_QUESTION_INTER),
                adsAppResume = bool(ObRemoteKeys.ADS_APP_RESUME),
                splashMinDisplayMs = long(ObRemoteKeys.SPLASH_MIN_DISPLAY_MS),
                splashAdBudgetMs = long(ObRemoteKeys.SPLASH_AD_BUDGET_MS),
                splashBannerWaitMs = long(ObRemoteKeys.SPLASH_BANNER_WAIT_MS),
                skipButtonDelaySec = long(ObRemoteKeys.SKIP_BUTTON_DELAY_SEC),
                fullScreenAutoDismissSec = long(ObRemoteKeys.FULLSCREEN_AUTO_DISMISS_SEC),
                showSkipOb3 = bool(ObRemoteKeys.SHOW_SKIP_OB3),
                showSkipOb5 = bool(ObRemoteKeys.SHOW_SKIP_OB5),
                uiContentJson = reader.string(ObRemoteKeys.UI_CONTENT_JSON.key)
                    ?: ObRemoteKeys.UI_CONTENT_JSON.default,
                uiDesignTokensJson = reader.string(ObRemoteKeys.UI_DESIGN_TOKENS_JSON.key)
                    ?: ObRemoteKeys.UI_DESIGN_TOKENS_JSON.default,
                questionConfigJson = reader.string(ObRemoteKeys.QUESTION_CONFIG_JSON.key)
                    ?: ObRemoteKeys.QUESTION_CONFIG_JSON.default,
                configVersion = long(ObRemoteKeys.CONFIG_VERSION),
            )
        }
    }
}
