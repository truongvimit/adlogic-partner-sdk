package io.onboardkit.remote

import android.content.Context
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.SettingsDocument
import com.ads.module.config.settings.SettingsRegistry
import com.ads.module.config.settings.SettingsSnapshot
import io.onboardkit.config.*
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.core.StepId

/** Defaults come from onboarding_config.json; only explicit valid overrides replace host options. */
object OnboardingSettings {
    val document = SettingsDocument("onboarding_config", BundledOnboarding.VALUES, ::extraDefault)
    val values: SettingsSnapshot get() = document.snapshot
    private fun extraDefault(path: String): Any? {
        if (path.startsWith("onboarding.steps.")) {
            val suffix = path.split('.').drop(3).joinToString(".")
            if (suffix.startsWith("fullscreen.")) return document.defaultValue("onboarding.$suffix")
            if (!suffix.startsWith("behavior.")) return document.defaultValue("onboarding.steps.ob1.$suffix")
        }
        if (path.contains("behavior.")) {
            val suffix = path.substringAfter("behavior.")
            val format = when {
                path.contains("banner") -> "banner"
                path.contains("interstitial") -> "interstitial"
                path.startsWith("app_resume") -> "app_open"
                else -> "native"
            }
            return if ((format != "interstitial" || suffix in setOf("load.tier_timeout_ms", "load_and_show.wait_timeout_ms")) && AdBehavior.supportsPlacementField(format, suffix)) AdBehavior.document.defaultValue("$format.$suffix") else null
        }
        if (path.startsWith("ui.content.") || path.startsWith("ui.design_tokens.") || path.startsWith("question.content.")) {
            return when (path.substringAfterLast('.')) { "steps", "options", "custom_colors" -> emptyList<Any>(); else -> "" }
        }
        return null
    }
    fun initialize(context: Context) {
        AdBehavior.initialize(context)
        document.initialize(context)
        document.prepareBeforePublish { snapshot ->
            val legacy = io.onboardkit.OnboardingSdk.remoteOrNull()?.flags?.value ?: RemoteFlags()
            snapshot.json("ui.content", legacy.uiContentJson)
            snapshot.json("ui.design_tokens", legacy.uiDesignTokensJson)
            snapshot.json("question.content", legacy.questionConfigJson)
        }
        SettingsRegistry.register(document)
    }
    fun defaultBool(path: String) = document.localSnapshot.boolean(path)
    fun defaultNumber(path: String) = document.localSnapshot.long(path)
    fun defaultText(path: String) = document.localSnapshot.string(path)
    fun bool(path: String) = values.boolean(path)
    fun number(path: String) = values.long(path)
    fun text(path: String) = values.string(path)

    private fun slotPath(p: io.onboardkit.ads.AdPlacement): String = when (p) {
        io.onboardkit.ads.AdPlacement.SplashBanner -> "splash.ads.banner"
        io.onboardkit.ads.AdPlacement.SplashInterstitial -> "splash.ads.interstitial"
        io.onboardkit.ads.AdPlacement.AfterOnboardingInterstitial -> "onboarding.exit_interstitial"
        io.onboardkit.ads.AdPlacement.Language1 -> "lfo.native1"
        io.onboardkit.ads.AdPlacement.Language2 -> "lfo.native2"
        io.onboardkit.ads.AdPlacement.LanguageConfirm -> "lfo.confirm_dialog"
        is io.onboardkit.ads.AdPlacement.StepNative -> "onboarding.steps.${p.stepId.value}"
        is io.onboardkit.ads.AdPlacement.StepFullScreen -> "onboarding.steps.${p.stepId.value}"
        io.onboardkit.ads.AdPlacement.Ob5 -> "ob5.native"
        io.onboardkit.ads.AdPlacement.QuestionNative -> "question.native"
        io.onboardkit.ads.AdPlacement.QuestionInterstitial -> "question.interstitial"
        io.onboardkit.ads.AdPlacement.AppResume -> "app_resume"
    }
    internal fun slotEnabled(p: io.onboardkit.ads.AdPlacement): Boolean {
        val suffix = when (p) {
            is io.onboardkit.ads.AdPlacement.StepNative, is io.onboardkit.ads.AdPlacement.StepFullScreen -> "native_enabled"
            io.onboardkit.ads.AdPlacement.LanguageConfirm -> "native_enabled"
            else -> "enabled"
        }
        return values.boolean("${slotPath(p)}.$suffix", true)
    }
    private fun mappingField(p: io.onboardkit.ads.AdPlacement): String = when (p) {
        is io.onboardkit.ads.AdPlacement.StepNative,
        is io.onboardkit.ads.AdPlacement.StepFullScreen,
        io.onboardkit.ads.AdPlacement.LanguageConfirm -> "native_placement"
        else -> "placement"
    }

    internal fun nativeClickDefault(p: io.onboardkit.ads.AdPlacement): Boolean {
        val path = when (p) {
        is io.onboardkit.ads.AdPlacement.StepNative -> "onboarding.ads.content_native_behavior.reload.on_ad_click"
        is io.onboardkit.ads.AdPlacement.StepFullScreen -> "onboarding.ads.fullscreen_native_behavior.reload.on_ad_click"
        io.onboardkit.ads.AdPlacement.Ob5 -> "ob5.native.behavior.reload.on_ad_click"
        else -> null
        }
        return path?.let { document.localSnapshot.boolean(it) } ?: AdBehavior.defaultBool("native.reload.on_ad_click")
    }
    internal fun behavior(p: io.onboardkit.ads.AdPlacement): com.ads.module.config.settings.BehaviorValues {
        val format = when (p) {
            io.onboardkit.ads.AdPlacement.SplashBanner -> "banner"
            io.onboardkit.ads.AdPlacement.SplashInterstitial, io.onboardkit.ads.AdPlacement.AfterOnboardingInterstitial,
            io.onboardkit.ads.AdPlacement.QuestionInterstitial -> "interstitial"
            io.onboardkit.ads.AdPlacement.AppResume -> "app_open"
            else -> "native"
        }
        val base = when (p) {
            is io.onboardkit.ads.AdPlacement.StepNative -> "onboarding.ads.content_native_behavior"
            is io.onboardkit.ads.AdPlacement.StepFullScreen -> "onboarding.ads.fullscreen_native_behavior"
            else -> null
        }
        val path = slotPath(p) + if (p == io.onboardkit.ads.AdPlacement.LanguageConfirm) ".native_behavior" else ".behavior"
        val snapshot = values
        val mapping = snapshot.string("${slotPath(p)}.${mappingField(p)}", "")
        val groupMapping = when (p) {
            is io.onboardkit.ads.AdPlacement.StepNative -> "onboarding.ads.content_native_placement"
            is io.onboardkit.ads.AdPlacement.StepFullScreen -> "onboarding.ads.fullscreen_native_placement"
            else -> null
        }
        val key = mapping.ifBlank { groupMapping?.let { snapshot.string(it, "") }.orEmpty() }.ifBlank { p.key }
        return AdBehavior.values(format, key, snapshot, path, base)
    }

    private data class ResolvedConfig(
        val source: OnboardKitConfig,
        val snapshot: SettingsSnapshot,
        val adUnits: Map<String, com.ads.module.config.AdUnitConfig>,
        val value: OnboardKitConfig,
    )
    private data class ResolvedFlags(val source: RemoteFlags, val snapshot: SettingsSnapshot, val value: RemoteFlags)
    @Volatile private var configCache: ResolvedConfig? = null
    @Volatile private var flagsCache: ResolvedFlags? = null

    @Synchronized fun resolve(c: OnboardKitConfig): OnboardKitConfig {
        val snapshot = values
        val adConfig = AdRemoteConfig.getInstance()
        configCache?.takeIf { it.source === c && it.snapshot === snapshot && it.adUnits === adConfig.ads }
            ?.let { return it.value }
        return resolveConfig(c, snapshot, adConfig).also {
            configCache = ResolvedConfig(c, snapshot, adConfig.ads, it)
        }
    }

    private fun resolveConfig(c: OnboardKitConfig, v: SettingsSnapshot, adConfig: AdRemoteConfig): OnboardKitConfig {
        if (listOf("flow", "splash", "lfo", "onboarding", "ob5", "question").none(v::hasOverride)) return c
        val splash = c.splash.copy(
            minDisplayTimeMs = v.long("splash.timing.min_display_ms", c.splash.minDisplayTimeMs),
            remoteFetchTimeoutMs = v.long("splash.load.remote_fetch_timeout_ms", c.splash.remoteFetchTimeoutMs),
            consentTimeoutMs = v.long("splash.load.consent_hook_timeout_ms", c.splash.consentTimeoutMs),
            billingTimeoutMs = v.long("splash.load.billing_timeout_ms", c.splash.billingTimeoutMs),
            adLoadStrategy = AdLoadStrategy.valueOf(v.string("splash.load.ad_strategy", c.splash.adLoadStrategy.name)),
            noInternetPromptEnabled = v.boolean("splash.permissions.no_internet_prompt_enabled", c.splash.noInternetPromptEnabled),
            notificationPermissionEnabled = v.boolean("splash.permissions.notification_enabled", c.splash.notificationPermissionEnabled),
        )
        val language = c.language.copy(
            secondNativeOnSelectEnabled = v.boolean("lfo.native2.enabled", c.language.secondNativeOnSelectEnabled),
            tapHintEnabled = v.boolean("lfo.tap_hint.enabled", c.language.tapHintEnabled),
            confirmVisibleBeforeSelect = v.boolean("lfo.confirm_button.visible_before_selection", c.language.confirmVisibleBeforeSelect),
            saveButtonOnBackEnabled = v.boolean("lfo.confirm_button.save_on_back", c.language.saveButtonOnBackEnabled),
            confirmDialogOnReselectEnabled = v.boolean("lfo.confirm_dialog.enabled", c.language.confirmDialogOnReselectEnabled),
            defaultCode = v.string("lfo.languages.default_code", c.language.defaultCode.orEmpty())
                .takeIf { candidate -> candidate.isNotBlank() && c.language.languages.any { it.code == candidate } }
                ?: c.language.defaultCode,
        )
        val behavior = c.behavior.copy(
            lockPagerSwipe = v.boolean("onboarding.navigation.lock_pager_swipe", c.behavior.lockPagerSwipe),
            swipeCompletesLastStep = v.boolean("onboarding.navigation.swipe_completes_last_step", c.behavior.swipeCompletesLastStep),
            backNavigatesBack = v.boolean("onboarding.navigation.back_navigates_back", c.behavior.backNavigatesBack),
            adClickReturnCompletesStep = v.boolean("onboarding.navigation.ad_click_return_completes_step", c.behavior.adClickReturnCompletesStep),
            lockPortrait = v.boolean("flow.lock_portrait", c.behavior.lockPortrait),
        )
        val legacySteps = setOf(StepId.OB1, StepId.OB2, StepId.OB3, StepId.OB4, StepId.OB5, StepId.QUESTION)
        val steps = c.steps.filter { step ->
            step.id in legacySteps || v.boolean("onboarding.steps.${step.id.value}.enabled", true)
        }.map { step ->
            if (step !is AdFullScreenStepDefinition) step else {
                val p = "onboarding.steps.${step.id.value}.fullscreen"
                fun b(s: String, local: Boolean) = v.boolean("$p.$s", v.boolean("onboarding.fullscreen.$s", local))
                fun n(s: String, local: Long) = v.long("$p.$s", v.long("onboarding.fullscreen.$s", local))
                step.copy(
                    showSkipButton = b("skip.enabled", step.showSkipButton),
                    skipButtonDelaySec = (n("skip.delay_ms", step.skipButtonDelaySec * 1000L) / 1000).toInt(),
                    autoNextEnabled = b("auto_next.enabled", step.autoNextEnabled),
                    autoNextDelayMs = n("auto_next.delay_ms", step.autoNextDelayMs),
                    skipButtonStyle = FullScreenSkipStyle.valueOf(v.string("$p.skip.style", v.string("onboarding.fullscreen.skip.style", step.skipButtonStyle?.name ?: v.string("flow.fullscreen_skip_style", c.ads.fullScreenSkipStyle.name)))),
                )
            }
        }
        val ads = resolveAds(c.ads, c.steps, v, adConfig)
        val system = c.system.copy(
            showStatusBar = v.boolean("flow.system_bars.show_status", c.system.showStatusBar),
            showNavigationBar = v.boolean("flow.system_bars.show_navigation", c.system.showNavigationBar),
            showCaptionBar = v.boolean("flow.system_bars.show_caption", c.system.showCaptionBar),
        )
        val question = c.question?.let { resolveQuestion(it, v) }
        return OnboardKitConfig(splash, language, steps, question, ads, system, behavior)
    }

    internal fun resolveQuestion(q: QuestionConfig, v: SettingsSnapshot = values): QuestionConfig {
        val mode = SelectionMode.valueOf(v.string("question.selection.mode", q.selectionMode.name))
        val max = if (mode == SelectionMode.SINGLE) 1 else q.options.size.coerceAtLeast(1)
        return q.copy(
            refreshAdOnSelect = v.boolean("question.native.refresh_on_select", q.refreshAdOnSelect),
            selectionMode = mode,
            minSelection = if (v.hasOverride("question.selection.min_count") || v.hasOverride("question.selection.mode"))
                v.long("question.selection.min_count", q.minSelection.toLong()).toInt().coerceIn(1, max)
                else q.minSelection,
        )
    }

    private fun resolveAds(a: AdsConfig, steps: List<StepDefinition>, v: SettingsSnapshot, adConfig: AdRemoteConfig): AdsConfig {
        fun ids(path: String) = v.string(path, "").takeIf { it.isNotBlank() }
            ?.let(adConfig::tiersFor)
        fun native(path: String, local: NativeAdUnit?): NativeAdUnit? = ids(path)?.let(::NativeAdUnit) ?: local
        fun inter(path: String, local: InterstitialAdUnit?): InterstitialAdUnit? = ids(path)?.let(::InterstitialAdUnit) ?: local
        return a.copy(
            splashBanner = ids("splash.ads.banner.placement")?.let { BannerAdUnit(it.firstOrNull().orEmpty()) } ?: a.splashBanner,
            splashInterstitial = inter("splash.ads.interstitial.placement", a.splashInterstitial),
            splashInterstitialOldUser = inter("splash.ads.interstitial.old_user_placement", a.splashInterstitialOldUser),
            languageNative = native("lfo.native1.placement", a.languageNative),
            languageDupNative = native("lfo.native2.placement", a.languageDupNative),
            languageConfirmNative = native("lfo.confirm_dialog.native_placement", a.languageConfirmNative),
            contentStepNative = native("onboarding.ads.content_native_placement", a.contentStepNative),
            fullScreenStepNative = native("onboarding.ads.fullscreen_native_placement", a.fullScreenStepNative),
            ob5Native = native("ob5.native.placement", a.ob5Native),
            questionNative = native("question.native.placement", a.questionNative),
            questionInterstitial = inter("question.interstitial.placement", a.questionInterstitial),
            afterOnboardingInterstitial = inter("onboarding.exit_interstitial.placement", a.afterOnboardingInterstitial),
            stepNatives = a.stepNatives.toMutableMap().apply { (steps.map { it.id } + io.onboardkit.core.StepId.OB5).distinct().forEach { id ->
                ids("onboarding.steps.${id.value}.native_placement")?.let { put(id, NativeAdUnit(it)) }
            } },
            afterOnboardingInterstitialEnabled = v.boolean("onboarding.exit_interstitial.enabled", a.afterOnboardingInterstitialEnabled),
            skipAdOnlyStepsWhenPremium = v.boolean("flow.skip_ad_only_steps_when_premium", a.skipAdOnlyStepsWhenPremium),
            fullScreenSkipStyle = FullScreenSkipStyle.valueOf(v.string("flow.fullscreen_skip_style", a.fullScreenSkipStyle.name)),
            afterOnboardingInterstitialTiming = NextScreenTiming.valueOf(v.string("onboarding.exit_interstitial.next_screen_timing", a.afterOnboardingInterstitialTiming.name)),
            languageTemplate = NativeTemplate.valueOf(v.string("lfo.native_template", a.languageTemplate.name)),
            contentStepTemplate = NativeTemplate.valueOf(v.string("onboarding.ads.content_template", a.contentStepTemplate.name)),
            questionTemplate = NativeTemplate.valueOf(v.string("question.native.template", a.questionTemplate.name)),
        )
    }

    @Synchronized fun resolveFlags(f: RemoteFlags): RemoteFlags {
        val snapshot = values
        flagsCache?.takeIf { it.source == f && it.snapshot === snapshot }?.let { return it.value }
        return resolveFlags(f, snapshot).also { flagsCache = ResolvedFlags(f, snapshot, it) }
    }

    private fun resolveFlags(f: RemoteFlags, v: SettingsSnapshot): RemoteFlags = f.copy(
            enableAllAds = v.boolean("flow.ads_enabled", f.enableAllAds),
            enableUiContent = v.boolean("ui.enabled", f.enableUiContent),
            enableStepOb1 = v.boolean("onboarding.steps.ob1.enabled", f.enableStepOb1),
            enableStepOb2 = v.boolean("onboarding.steps.ob2.enabled", f.enableStepOb2),
            enableStepOb3 = v.boolean("onboarding.steps.ob3.enabled", f.enableStepOb3),
            enableStepOb4 = v.boolean("onboarding.steps.ob4.enabled", f.enableStepOb4),
            enableStepOb5 = v.boolean("ob5.enabled", f.enableStepOb5),
            enableQuestion = v.boolean("question.enabled", f.enableQuestion),
            enableQuestionOldUser = v.boolean("question.old_user_enabled", f.enableQuestionOldUser),
            enableLanguageNative2 = v.boolean("lfo.native2.enabled", f.enableLanguageNative2),
            showLanguageTapHint = v.boolean("lfo.tap_hint.enabled", f.showLanguageTapHint),
            showLanguageConfirmBeforeSelect = v.boolean("lfo.confirm_button.visible_before_selection", f.showLanguageConfirmBeforeSelect),
            showLanguageConfirmDialog = v.boolean("lfo.confirm_dialog.enabled", f.showLanguageConfirmDialog),
            reuseSplashInter = v.boolean("lfo.exit.reuse_splash_inter", f.reuseSplashInter),
            adsSplashBanner = v.boolean("splash.ads.banner.enabled", f.adsSplashBanner),
            adsSplashInter = v.boolean("splash.ads.interstitial.enabled", f.adsSplashInter),
            adsAfterOnboardInter = v.boolean("onboarding.exit_interstitial.enabled", f.adsAfterOnboardInter),
            adsLanguageNative = v.boolean("lfo.ads_enabled", f.adsLanguageNative),
            adsLanguageConfirmNative = v.boolean("lfo.confirm_dialog.native_enabled", f.adsLanguageConfirmNative),
            adsContentNative = v.boolean("onboarding.ads.content_native_enabled", f.adsContentNative),
            adsFullScreenNative = v.boolean("onboarding.ads.fullscreen_native_enabled", f.adsFullScreenNative),
            adsQuestionNative = v.boolean("question.native.enabled", f.adsQuestionNative),
            adsQuestionInter = v.boolean("question.interstitial.enabled", f.adsQuestionInter),
            adsAppResume = v.boolean("app_resume.enabled", f.adsAppResume),
            showSkipOb3 = v.boolean("onboarding.fullscreen.skip.enabled", f.showSkipOb3),
            showSkipOb5 = v.boolean("ob5.skip.enabled", f.showSkipOb5),
            splashNotificationSettleMs = v.long("splash.timing.notification_settle_ms", f.splashNotificationSettleMs),
            splashMinDisplayMs = v.long("splash.timing.min_display_ms", f.splashMinDisplayMs),
            splashAdBudgetMs = v.long("splash.timing.ad_budget_ms", f.splashAdBudgetMs),
            splashBannerWaitMs = v.long("splash.timing.banner_wait_ms", f.splashBannerWaitMs),
            splashLfoParallelPreloadEnabled = v.string("splash.load.lfo1_preload_mode", if (f.splashLfoParallelPreloadEnabled) "PARALLEL" else "SEQUENTIAL") == "PARALLEL",
            languageSupportedCodes = v.strings("lfo.languages.supported_codes", f.languageSupportedCodes.split(',').filter { it.isNotBlank() }).joinToString(","),
            uiContentJson = v.json("ui.content", f.uiContentJson),
            uiDesignTokensJson = v.json("ui.design_tokens", f.uiDesignTokensJson),
            questionConfigJson = v.json("question.content", f.questionConfigJson),
        )
}
