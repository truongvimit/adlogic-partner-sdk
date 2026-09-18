package io.onboardkit.remote

import android.content.Context
import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.SettingsDocument
import com.ads.module.config.settings.SettingsRegistry
import com.ads.module.config.settings.SettingsSnapshot
import io.onboardkit.config.*
import io.onboardkit.ads.NextScreenTiming
import com.ads.module.helper.adnative.NativeClickAction
import io.onboardkit.ads.AdPlacement

/** Defaults come from onboarding_config.json; only explicit valid overrides replace host options. */
object OnboardingSettings {
    val document = SettingsDocument("onboarding_config", BundledOnboarding.VALUES, ::extraDefault)
    val values: SettingsSnapshot get() = document.snapshot
    private fun extraDefault(path: String): Any? {
        if (path.startsWith("onboarding.steps.")) {
            val suffix = path.split('.').drop(3).joinToString(".")
            if (suffix.startsWith("fullscreen.")) return document.defaultValue("onboarding.$suffix")
            if (suffix == "native_template") return ""
            if (suffix == "behavior") return emptyMap<String, Any>()
            if (!suffix.startsWith("behavior.")) return null
        }
        if (path.contains("behavior.")) {
            val scope = path.substringBefore("behavior.") + "behavior"
            val stepScope = scope.split('.').let { it.size == 4 && it.take(2) == listOf("onboarding", "steps") && it.last() == "behavior" }
            val declaredScope = document.defaultValue(scope) is Map<*, *> ||
                document.defaultValue("$scope.reload.on_ad_click") != null ||
                document.defaultValue("$scope.click.action") != null
            if (!stepScope && !declaredScope) return null
            val suffix = path.substringAfter("behavior.")
            val format = when {
                path.contains("banner") -> "banner"
                path.contains("interstitial") -> "interstitial"
                path.startsWith("app_resume") -> "app_open"
                else -> "native"
            }
            return if ((format != "interstitial" || suffix in setOf("load.tier_timeout_ms", "load_and_show.wait_timeout_ms")) && AdBehavior.supportsPlacementField(format, suffix)) AdBehavior.document.defaultValue("$format.$suffix") else null
        }
        return null
    }
    fun initialize(context: Context) {
        AdBehavior.initialize(context)
        document.initialize(context)
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
        // Not "splash.native" — that scope belongs to the full-screen SplashNative and carries the
        // skip / auto-dismiss settings of a screen, which mean nothing to a bottom slot.
        io.onboardkit.ads.AdPlacement.SplashInlineNative -> "splash.ads.native"
        io.onboardkit.ads.AdPlacement.SplashInterstitial -> "splash.ads.interstitial"
        io.onboardkit.ads.AdPlacement.SplashNative -> "splash.native"
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
    internal fun nativeClickAction(p: AdPlacement): NativeClickAction {
        val behavior = behavior(p)
        // A new action always wins over both legacy switches, even when they conflict.
        NativeClickAction.fromRemote(behavior.string("click.action", ""))?.let { return it }
        if (behavior.hasOverride("reload.on_ad_click")) {
            return if (behavior.boolean("reload.on_ad_click", true)) NativeClickAction.RELOAD else NativeClickAction.NONE
        }
        val path = when (p) {
            is AdPlacement.StepNative -> "onboarding.ads.content_native_behavior.click.action"
            is AdPlacement.StepFullScreen -> "onboarding.ads.fullscreen_native_behavior.click.action"
            else -> slotPath(p) + if (p == AdPlacement.LanguageConfirm) ".native_behavior.click.action" else ".behavior.click.action"
        }
        // Compatibility for hosts using the old step switch. It cannot override click.action.
        if ((p is AdPlacement.StepNative || p is AdPlacement.StepFullScreen) &&
            io.onboardkit.OnboardingSdk.configOrNull()?.behavior?.adClickReturnCompletesStep == false) {
            return NativeClickAction.NONE
        }
        val defaultAction = if (document.defaultValue(path) != null) document.localSnapshot.string(path)
            else AdBehavior.defaultText("native.click.action")
        return NativeClickAction.fromRemote(defaultAction) ?: NativeClickAction.RELOAD
    }
    internal fun behavior(p: AdPlacement): com.ads.module.config.settings.BehaviorValues {
        val format = when (p) {
            AdPlacement.SplashBanner -> "banner"
            AdPlacement.SplashInterstitial, AdPlacement.AfterOnboardingInterstitial,
            AdPlacement.QuestionInterstitial -> "interstitial"
            AdPlacement.AppResume -> "app_open"
            else -> "native"
        }
        val base = when (p) {
            is AdPlacement.StepNative -> "onboarding.ads.content_native_behavior"
            is AdPlacement.StepFullScreen -> "onboarding.ads.fullscreen_native_behavior"
            else -> null
        }
        val path = slotPath(p) + if (p == AdPlacement.LanguageConfirm) ".native_behavior" else ".behavior"
        val snapshot = values
        val key = io.onboardkit.OnboardingSdk.configuredPlacementKey(p) ?: p.key
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
        val ads = resolveAds(c.ads, adConfig, v)
        if (listOf("flow", "splash", "lfo", "onboarding", "ob5", "question").none(v::hasOverride) && ads == c.ads) return c
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
        )
        val catalog = c.steps.associateBy { it.id.value }
        val ordered = if (v.hasOverride("onboarding.order")) {
            v.strings("onboarding.order").mapNotNull(catalog::get)
        } else c.steps
        val steps = ordered.filter { it.enabled }.map { step ->
            if (step !is AdFullScreenStepDefinition) step else {
                val p = "onboarding.steps.${step.id.value}.fullscreen"
                fun b(s: String, local: Boolean) = v.boolean("$p.$s", v.boolean("onboarding.fullscreen.$s", local))
                fun n(s: String, local: Long) = v.long("$p.$s", v.long("onboarding.fullscreen.$s", local))
                step.copy(
                    showSkipButton = b("skip.enabled", step.showSkipButton),
                    skipButtonDelaySec = (n("skip.delay_ms", step.skipButtonDelaySec * 1000L) / 1000).toInt(),
                    autoNextEnabled = b("auto_next.enabled", step.autoNextEnabled),
                    autoNextDelayMs = n("auto_next.delay_ms", step.autoNextDelayMs),
                    skipButtonStyle = FullScreenSkipStyle.valueOf(v.string("$p.skip.style",
                        v.string("onboarding.fullscreen.skip.style", step.skipButtonStyle?.name ?: ads.fullScreenSkipStyle.name))),
                )
            }
        }
        val question = c.question?.let { resolveQuestion(it, v) }
        return OnboardKitConfig(splash, language, steps, question, ads, c.system, behavior)
    }

    internal fun ob5SkipStyle(fallback: FullScreenSkipStyle): FullScreenSkipStyle =
        FullScreenSkipStyle.valueOf(values.string("ob5.skip.style", fallback.name))

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

    private fun resolveAds(a: AdsConfig, adConfig: AdRemoteConfig, v: SettingsSnapshot): AdsConfig =
        a.resolvePlacements(adConfig).copy(
            afterOnboardingInterstitialEnabled = v.boolean("onboarding.exit_interstitial.enabled", a.afterOnboardingInterstitialEnabled),
            skipAdOnlyStepsWhenPremium = v.boolean("flow.skip_ad_only_steps_when_premium", a.skipAdOnlyStepsWhenPremium),
            fullScreenSkipStyle = FullScreenSkipStyle.valueOf(v.string("flow.fullscreen_skip_style", a.fullScreenSkipStyle.name)),
            languageTemplate = NativeTemplate.valueOf(v.string("lfo.native_template", a.languageTemplate.name)),
            contentStepTemplate = NativeTemplate.valueOf(v.string("onboarding.ads.content_template", a.contentStepTemplate.name)),
            questionTemplate = NativeTemplate.valueOf(v.string("question.native.template", a.questionTemplate.name)),
            afterOnboardingInterstitialTiming = NextScreenTiming.valueOf(v.string("onboarding.exit_interstitial.next_screen_timing", a.afterOnboardingInterstitialTiming.name)),
        )

    @Synchronized fun resolveFlags(f: RemoteFlags): RemoteFlags {
        val snapshot = values
        flagsCache?.takeIf { it.source == f && it.snapshot === snapshot }?.let { return it.value }
        return resolveFlags(f, snapshot).also { flagsCache = ResolvedFlags(f, snapshot, it) }
    }

    private fun resolveFlags(f: RemoteFlags, v: SettingsSnapshot): RemoteFlags {
        // An explicit order owns pager membership; old scalar flags cannot hide selected pages.
        val order = v.strings("onboarding.order").takeIf { v.hasOverride("onboarding.order") }
        return f.copy(
            enableAllAds = v.boolean("flow.ads_enabled", f.enableAllAds),
            languageSupportedCodes = v.strings("lfo.languages.supported_codes", f.languageSupportedCodes.split(',').filter { it.isNotBlank() }).joinToString(","),
            enableStepOb1 = order?.contains("ob1") ?: f.enableStepOb1,
            enableStepOb2 = order?.contains("ob2") ?: f.enableStepOb2,
            enableStepOb3 = order?.contains("ob3") ?: f.enableStepOb3,
            enableStepOb4 = order?.contains("ob4") ?: f.enableStepOb4,
            enableStepOb5 = v.boolean("ob5.enabled", f.enableStepOb5),
            enableQuestion = v.boolean("question.enabled", f.enableQuestion),
            enableQuestionOldUser = v.boolean("question.old_user_enabled", f.enableQuestionOldUser),
            enableLanguageNative2 = v.boolean("lfo.native2.enabled", f.enableLanguageNative2),
            showLanguageTapHint = v.boolean("lfo.tap_hint.enabled", f.showLanguageTapHint),
            showLanguageConfirmBeforeSelect = v.boolean("lfo.confirm_button.visible_before_selection", f.showLanguageConfirmBeforeSelect),
            showLanguageConfirmDialog = v.boolean("lfo.confirm_dialog.enabled", f.showLanguageConfirmDialog),
            reuseSplashInter = v.boolean("lfo.exit.reuse_splash_inter", f.reuseSplashInter),
            adsAfterOnboardInter = v.boolean("onboarding.exit_interstitial.enabled", f.adsAfterOnboardInter),
            showSkipOb3 = v.boolean("onboarding.fullscreen.skip.enabled", f.showSkipOb3),
            showSkipOb5 = v.boolean("ob5.skip.enabled", f.showSkipOb5),
            splashNotificationSettleMs = v.long("splash.timing.notification_settle_ms", f.splashNotificationSettleMs),
            splashMinDisplayMs = v.long("splash.timing.min_display_ms", f.splashMinDisplayMs),
            splashAdBudgetMs = v.long("splash.timing.ad_budget_ms", f.splashAdBudgetMs),
            splashBannerWaitMs = v.long("splash.timing.banner_wait_ms", f.splashBannerWaitMs),
            splashLfoParallelPreloadEnabled = v.string("splash.load.lfo1_preload_mode", if (f.splashLfoParallelPreloadEnabled) "PARALLEL" else "SEQUENTIAL") == "PARALLEL",
        )
    }
}
