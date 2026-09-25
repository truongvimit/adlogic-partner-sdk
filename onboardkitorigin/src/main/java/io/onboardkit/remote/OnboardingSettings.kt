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

/**
 * Defaults come from onboarding_config.json. Remote (this document, then the legacy `ob_*` keys the
 * backend delivered) > app asset > host option > bundled default, for every field.
 */
object OnboardingSettings {
    val document = SettingsDocument("onboarding_config", BundledOnboarding.VALUES, ::extraDefault)
    val values: SettingsSnapshot get() = document.snapshot

    private fun extraDefault(path: String): Any? {
        if (path.startsWith("onboarding.steps.")) {
            val suffix = path.split('.').drop(3).joinToString(".")
            // Position is the one fullscreen field with no shared onboarding scope: each page
            // owns its own side, so there is no "onboarding.fullscreen.*" default to borrow.
            if (suffix == "fullscreen.skip.position") return FullScreenSkipPosition.RIGHT.name
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
    /** @param adConfigKey the key whose `placement_overrides` apply, when not the placement's own. */
    internal fun behavior(p: AdPlacement, adConfigKey: String? = null): com.ads.module.config.settings.BehaviorValues {
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
        val key = adConfigKey ?: io.onboardkit.OnboardingSdk.configuredPlacementKey(p)
            ?: io.onboardkit.OnboardingSdk.configOrNull()?.ads?.standardKeyFor(p) ?: p.key
        // The exit ad's own wait outranks the placement and format waits.
        val aliases = if (p == AdPlacement.AfterOnboardingInterstitial)
            mapOf("load_and_show.wait_timeout_ms" to "onboarding.exit_interstitial.wait_timeout_ms") else emptyMap()
        return AdBehavior.values(format, key, snapshot, path, base, aliases)
    }

    private data class ResolvedConfig(
        val source: OnboardKitConfig,
        val snapshot: SettingsSnapshot,
        val adUnits: Map<String, com.ads.module.config.AdUnitConfig>,
        val steps: Map<String, Boolean>,
        val value: OnboardKitConfig,
    )
    private data class ResolvedFlags(val source: RemoteFlags, val snapshot: SettingsSnapshot, val value: RemoteFlags)
    @Volatile private var configCache: ResolvedConfig? = null
    @Volatile private var flagsCache: ResolvedFlags? = null

    /** Delivered `ob_enable_step_ob1..4`, by step id; they have no path in this document. */
    @Volatile private var legacySteps: Map<String, Boolean> = emptyMap()

    /**
     * Moves the legacy `ob_*` values the backend actually delivered into the remote tier, so a
     * delivered key outranks the app asset and the host config the same way this document does.
     */
    fun acceptLegacy(f: RemoteFlags) {
        val k = ObRemoteKeys
        val mapped = mutableMapOf<String, Any>()
        fun putMapped(key: RemoteKey<*>, raw: Any, value: Any?, vararg paths: String) {
            if (value != null && f.isSupplied(key, raw)) paths.forEach { mapped[it] = value }
        }
        fun put(key: RemoteKey<*>, raw: Any, path: String) = putMapped(key, raw, raw, path)
        put(k.ENABLE_ALL_ADS, f.enableAllAds, "flow.ads_enabled")
        put(k.ENABLE_STEP_OB5, f.enableStepOb5, "ob5.enabled")
        put(k.ENABLE_QUESTION, f.enableQuestion, "question.enabled")
        put(k.ENABLE_QUESTION_OLD_USER, f.enableQuestionOldUser, "question.old_user_enabled")
        put(k.ENABLE_LANGUAGE_NATIVE_2, f.enableLanguageNative2, "lfo.native2.enabled")
        put(k.SHOW_LANGUAGE_TAP_HINT, f.showLanguageTapHint, "lfo.tap_hint.enabled")
        putMapped(k.LANGUAGE_TAP_HINT_DELAY_SEC, f.languageTapHintDelaySec, f.languageTapHintDelaySec * 1000, "lfo.tap_hint.delay_ms")
        put(k.SHOW_LANGUAGE_CONFIRM_BEFORE_SELECT, f.showLanguageConfirmBeforeSelect, "lfo.confirm_button.visible_before_selection")
        put(k.SHOW_LANGUAGE_CONFIRM_DIALOG, f.showLanguageConfirmDialog, "lfo.confirm_dialog.enabled")
        putMapped(k.LANGUAGE_SUPPORTED_CODES, f.languageSupportedCodes,
            f.languageSupportedCodes.split(',').map(String::trim).filter(String::isNotEmpty), "lfo.languages.supported_codes")
        put(k.REUSE_SPLASH_INTER, f.reuseSplashInter, "lfo.exit.reuse_splash_inter")
        put(k.ADS_AFTER_ONBOARD_INTER, f.adsAfterOnboardInter, "onboarding.exit_interstitial.enabled")
        putMapped(k.SPLASH_LFO_PARALLEL_PRELOAD_ENABLED, f.splashLfoParallelPreloadEnabled,
            if (f.splashLfoParallelPreloadEnabled) "PARALLEL" else "SEQUENTIAL", "splash.load.lfo1_preload_mode")
        put(k.SPLASH_NOTIFICATION_SETTLE_MS, f.splashNotificationSettleMs, "splash.timing.notification_settle_ms")
        // A non-positive legacy minimum always meant "use the local value".
        putMapped(k.SPLASH_MIN_DISPLAY_MS, f.splashMinDisplayMs, f.splashMinDisplayMs.takeIf { it > 0 }, "splash.timing.min_display_ms")
        put(k.SPLASH_AD_BUDGET_MS, f.splashAdBudgetMs, "splash.timing.ad_budget_ms")
        put(k.SPLASH_SLOT_MIN_VISIBLE_MS, f.splashSlotMinVisibleMs, "splash.timing.slot_min_visible_ms")
        putMapped(k.SKIP_BUTTON_DELAY_SEC, f.skipButtonDelaySec, f.skipButtonDelaySec.takeIf { it >= 0 }?.times(1000),
            "onboarding.fullscreen.skip.delay_ms", "ob5.skip.delay_ms")
        putMapped(k.FULLSCREEN_AUTO_DISMISS_SEC, f.fullScreenAutoDismissSec, f.fullScreenAutoDismissSec.coerceAtLeast(5) * 1000, "ob5.auto_dismiss_ms")
        put(k.SHOW_SKIP_OB3, f.showSkipOb3, "onboarding.fullscreen.skip.enabled")
        put(k.SHOW_SKIP_OB5, f.showSkipOb5, "ob5.skip.enabled")
        legacySteps = listOf(k.ENABLE_STEP_OB1 to f.enableStepOb1, k.ENABLE_STEP_OB2 to f.enableStepOb2,
            k.ENABLE_STEP_OB3 to f.enableStepOb3, k.ENABLE_STEP_OB4 to f.enableStepOb4)
            .filter { (key, on) -> f.isSupplied(key, on) }
            .associate { (key, on) -> key.key.removePrefix("ob_enable_step_") to on }
        document.acceptLegacyRemote(mapped)
    }

    @Synchronized fun resolve(c: OnboardKitConfig): OnboardKitConfig {
        val snapshot = values
        val adConfig = AdRemoteConfig.getInstance()
        val steps = legacySteps
        configCache?.takeIf { it.source === c && it.snapshot === snapshot && it.adUnits === adConfig.ads && it.steps === steps }
            ?.let { return it.value }
        return resolveConfig(c, snapshot, adConfig, steps).also {
            configCache = ResolvedConfig(c, snapshot, adConfig.ads, steps, it)
        }
    }

    private fun resolveConfig(c: OnboardKitConfig, v: SettingsSnapshot, adConfig: AdRemoteConfig, legacySteps: Map<String, Boolean>): OnboardKitConfig {
        val ads = resolveAds(c.ads, adConfig, v)
        if (listOf("flow", "splash", "lfo", "onboarding", "ob5", "question").none(v::hasOverride) &&
            ads == c.ads && legacySteps.isEmpty()) return c
        val splash = c.splash.copy(
            minDisplayTimeMs = v.long("splash.timing.min_display_ms", c.splash.minDisplayTimeMs),
            remoteFetchTimeoutMs = v.long("splash.load.remote_fetch_timeout_ms", c.splash.remoteFetchTimeoutMs),
            consentTimeoutMs = v.long("splash.load.consent_hook_timeout_ms", c.splash.consentTimeoutMs),
            billingTimeoutMs = v.long("splash.load.billing_timeout_ms", c.splash.billingTimeoutMs),
            adLoadStrategy = AdLoadStrategy.valueOf(v.string("splash.load.ad_strategy", c.splash.adLoadStrategy.name)),
            noInternetPromptEnabled = v.boolean("splash.permissions.no_internet_prompt_enabled", c.splash.noInternetPromptEnabled),
            notificationPermissionEnabled = v.boolean("splash.permissions.notification_enabled", c.splash.notificationPermissionEnabled),
        )
        // Remote codes pick from the app catalog (remote cannot add a language the app has no
        // strings for); a default that is not on the offered list would preselect a hidden row.
        val listed = v.strings("lfo.languages.supported_codes")
            .mapNotNull { code -> c.language.languages.firstOrNull { it.code == code } }
            .distinctBy { it.code }
        val offered = listed.ifEmpty { c.language.languages }
        fun offers(code: String?) = code != null && offered.any { it.code == code }
        val language = c.language.copy(
            languages = offered,
            secondNativeOnSelectEnabled = v.boolean("lfo.native2.enabled", c.language.secondNativeOnSelectEnabled),
            tapHintEnabled = v.boolean("lfo.tap_hint.enabled", c.language.tapHintEnabled),
            confirmVisibleBeforeSelect = v.boolean("lfo.confirm_button.visible_before_selection", c.language.confirmVisibleBeforeSelect),
            saveButtonOnBackEnabled = v.boolean("lfo.confirm_button.save_on_back", c.language.saveButtonOnBackEnabled),
            confirmDialogOnReselectEnabled = v.boolean("lfo.confirm_dialog.enabled", c.language.confirmDialogOnReselectEnabled),
            defaultCode = v.string("lfo.languages.default_code", "").takeIf(::offers)
                ?: c.language.defaultCode?.takeIf { listed.isEmpty() || offers(it) },
        )
        val behavior = c.behavior.copy(
            lockPagerSwipe = v.boolean("onboarding.navigation.lock_pager_swipe", c.behavior.lockPagerSwipe),
            swipeCompletesLastStep = v.boolean("onboarding.navigation.swipe_completes_last_step", c.behavior.swipeCompletesLastStep),
            backNavigatesBack = v.boolean("onboarding.navigation.back_navigates_back", c.behavior.backNavigatesBack),
            adClickReturnCompletesStep = v.boolean("onboarding.navigation.ad_click_return_completes_step", c.behavior.adClickReturnCompletesStep),
        )
        // A remote order names the pages outright, so a page it lists shows even when the app
        // disabled it. Remote order > delivered legacy step keys > app asset order > the catalog.
        val catalog = c.steps.associateBy { it.id.value }
        fun order(value: Any?) = (value as? List<*>)?.filterIsInstance<String>()?.mapNotNull(catalog::get)
        val selected = order(v.remoteValue("onboarding.order"))
            ?: withLegacySteps((order(v.assetValue("onboarding.order")) ?: c.steps).filter { it.enabled }, c.steps, legacySteps)
        val steps = selected.map { step ->
            if (step !is AdFullScreenStepDefinition) (step as ContentStepDefinition).copy(enabled = true) else {
                val page = step.id.value
                step.copy(
                    showSkipButton = FullScreenSetting.SkipEnabled.on(page, v).boolean(step.showSkipButton),
                    skipButtonDelaySec = (FullScreenSetting.SkipDelayMs.on(page, v)
                        .long(step.skipButtonDelaySec * 1000L) / 1000).toInt(),
                    autoNextEnabled = FullScreenSetting.AutoNextEnabled.on(page, v).boolean(step.autoNextEnabled),
                    autoNextDelayMs = FullScreenSetting.AutoNextDelayMs.on(page, v).long(step.autoNextDelayMs),
                    skipButtonStyle = FullScreenSkipStyle.valueOf(FullScreenSetting.SkipStyle.on(page, v)
                        .string(step.skipButtonStyle?.name ?: ads.fullScreenSkipStyle.name)),
                    skipButtonPosition = FullScreenSkipPosition.valueOf(FullScreenSetting.SkipPosition.on(page, v)
                        .string(step.skipButtonPosition.name)),
                    enabled = true,
                )
            }
        }
        val question = c.question?.let { resolveQuestion(it, v) }
        return OnboardKitConfig(splash, language, steps, question, ads, c.system, behavior)
    }

    /** A legacy step key removes its page or brings back one the app disabled, at its catalog position. */
    private fun withLegacySteps(base: List<StepDefinition>, catalog: List<StepDefinition>, gates: Map<String, Boolean>): List<StepDefinition> {
        if (gates.isEmpty()) return base
        val result = base.filter { gates[it.id.value] != false }.toMutableList()
        catalog.filter { gates[it.id.value] == true && result.none { r -> r.id == it.id } }.forEach { added ->
            val position = catalog.indexOf(added)
            result.add(result.indexOfLast { catalog.indexOf(it) in 0 until position } + 1, added)
        }
        return result
    }

    internal fun ob5SkipStyle(fallback: FullScreenSkipStyle): FullScreenSkipStyle =
        FullScreenSkipStyle.valueOf(values.scoped("ob5.skip.style", "flow.fullscreen_skip_style").string(fallback.name))

    /**
     * The question a run shows: valid remote `ob_question_config` replaces the app's title and
     * options, and null means neither side has one. Gate and screen both ask this, so they agree.
     */
    internal fun questionContent(compiled: QuestionConfig?, remoteJson: String): QuestionConfig? {
        val remote = io.onboardkit.remote.uiconfig.RemoteQuestionParser.parse(remoteJson)
        val base = compiled ?: if (remote != null) QuestionConfig() else return null
        return resolveQuestion(base.copy(
            title = remote?.title?.takeIf { it.isNotBlank() } ?: base.title,
            options = remote?.options ?: base.options,
        ))
    }

    internal fun resolveQuestion(q: QuestionConfig, v: SettingsSnapshot = values): QuestionConfig {
        val mode = SelectionMode.valueOf(v.string("question.selection.mode", q.selectionMode.name))
        val max = if (mode == SelectionMode.SINGLE) 1 else q.options.size.coerceAtLeast(1)
        // Clamped even without a selection override: remote options can leave fewer than the
        // app's minimum, and an unreachable minimum hides the CTA for good.
        return q.copy(
            refreshAdOnSelect = v.boolean("question.native.refresh_on_select", q.refreshAdOnSelect),
            selectionMode = mode,
            minSelection = v.long("question.selection.min_count", q.minSelection.toLong()).toInt().coerceIn(1, max),
        )
    }

    private fun resolveAds(a: AdsConfig, adConfig: AdRemoteConfig, v: SettingsSnapshot): AdsConfig =
        a.resolvePlacements(adConfig).copy(
            // Only remote overrides the host switch; an app asset "off" still reaches the guard via flags.
            enabled = v.remoteValue("flow.ads_enabled") as? Boolean ?: a.enabled,
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
        // Same precedence as the page list: remote order > delivered legacy key > asset order.
        fun listed(value: Any?) = (value as? List<*>)?.filterIsInstance<String>()
        val remoteOrder = listed(v.remoteValue("onboarding.order"))
        val assetOrder = listed(v.assetValue("onboarding.order"))
        fun step(id: String, key: RemoteKey<Boolean>, legacy: Boolean) = remoteOrder?.contains(id)
            ?: legacy.takeIf { f.isSupplied(key, it) } ?: assetOrder?.contains(id) ?: legacy
        return f.copy(
            enableAllAds = v.boolean("flow.ads_enabled", f.enableAllAds),
            languageSupportedCodes = v.strings("lfo.languages.supported_codes", f.languageSupportedCodes.split(',').filter { it.isNotBlank() }).joinToString(","),
            enableStepOb1 = step("ob1", ObRemoteKeys.ENABLE_STEP_OB1, f.enableStepOb1),
            enableStepOb2 = step("ob2", ObRemoteKeys.ENABLE_STEP_OB2, f.enableStepOb2),
            enableStepOb3 = step("ob3", ObRemoteKeys.ENABLE_STEP_OB3, f.enableStepOb3),
            enableStepOb4 = step("ob4", ObRemoteKeys.ENABLE_STEP_OB4, f.enableStepOb4),
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
            splashSlotMinVisibleMs = v.long("splash.timing.slot_min_visible_ms", f.splashSlotMinVisibleMs),
            splashLfoParallelPreloadEnabled = v.string("splash.load.lfo1_preload_mode", if (f.splashLfoParallelPreloadEnabled) "PARALLEL" else "SEQUENTIAL") == "PARALLEL",
        )
    }
}
