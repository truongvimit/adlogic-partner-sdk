package io.onboardkit.config

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType

enum class AdLoadStrategy {
    /** Load ads in parallel with remote fetch — faster, ids may be stale. */
    SAME_TIME,

    /** Load ads after remote fetch — ids always fresh. Default. */
    ALTERNATE,
}

data class SplashConfig(
    @LayoutRes val layoutRes: Int = 0,
    @DrawableRes val logoRes: Int = 0,
    @StringRes val appNameRes: Int = 0,
    val minDisplayTimeMs: Long = 3_000,
    val remoteFetchTimeoutMs: Long = 10_000,
    /** Bounds a consent hook that bypasses ConsentCenter; UMP uses ConsentOptions.timeoutMs. */
    val consentTimeoutMs: Long = 20_000,
    val billingTimeoutMs: Long = 5_000,
    val adLoadStrategy: AdLoadStrategy = AdLoadStrategy.ALTERNATE,
    /**
     * Hold the splash behind a prompt until the device has validated internet. The flow cannot
     * run offline — consent, the remote fetch and every ad request need the network — so the
     * prompt has no way past it other than getting one.
     */
    val noInternetPromptEnabled: Boolean = true,
    /**
     * Requests POST_NOTIFICATIONS on Android 13+ after consent, when the splash is foreground.
     * Enabled by default. Authorized splash requests and the minimum display clock may run under
     * this prompt while splash is visible. The ad wait budget and fullscreen presentation require
     * its result and foreground focus. LFO1 follows its remote preload mode. Denial never blocks access.
     * Already granted, older Android/target SDK, or a removed manifest permission skip the prompt.
     * After any result, app preferences remember this automatic request; denial is not re-prompted
     * on later launches. The host may still request permission itself at a relevant user action.
     * Set false when the host owns notification onboarding or does not send notifications.
     */
    val notificationPermissionEnabled: Boolean = true,
)

data class LanguageConfig(
    val languages: List<ObLanguage> = ObLanguages.ALL,
    val defaultCode: String? = null,
    /**
     * On the first language tap, requests a swap to the second native preloaded on entry.
     * The first stays visible until the replacement binds; selection and scroll position remain.
     */
    val secondNativeOnSelectEnabled: Boolean = true,
    /**
     * Animated hand pointing at the row that matches the device language (English when the
     * device language is not on the list), shown only until the user picks something.
     * AND-ed with the `ob_show_language_tap_hint` remote flag. Appears after
     * `ob_language_tap_hint_delay_sec` seconds (default 3); the delay is ignored when disabled.
     */
    val tapHintEnabled: Boolean = true,
    /**
     * Whether the confirm button is on screen before the user has picked anything.
     *
     * `false` (default) hides it until the first tap, which makes selecting a language the only
     * thing the screen offers. `true` keeps it visible but dimmed, so the way out of the screen
     * is obvious from the start. AND-ed with the `ob_show_language_confirm_before_select` remote
     * flag; either side turning it off hides the button.
     */
    val confirmVisibleBeforeSelect: Boolean = false,
    /**
     * Back on the first-open language screen never leaves the flow. When a language is already
     * picked, this also reveals a full-width Save button above the ad — the way out the screen
     * was not otherwise offering. `false` keeps back inert with no button.
     */
    val saveButtonOnBackEnabled: Boolean = true,
    /**
     * Show confirmation from the fourth language-item tap onward, including taps on the
     * selected language. Kept under its original API name for source compatibility.
     * AND-ed with `ob_show_language_confirm_dialog`.
     */
    val confirmDialogOnReselectEnabled: Boolean = true,
    @LayoutRes val layoutRes: Int = 0,
    @LayoutRes val itemLayoutRes: Int = 0,
)

data class SystemBarConfig @JvmOverloads constructor(
    val showStatusBar: Boolean = true,
    val showNavigationBar: Boolean = false,
    val showCaptionBar: Boolean = true,
)

data class BehaviorConfig(
    /** Buttons are the single navigation source; swipe is locked. */
    val lockPagerSwipe: Boolean = true,
    /** Back returns to the previous step; on the first step it exits the app. */
    val backNavigatesBack: Boolean = true,
    /** Compatibility only: a new page visit always consumes an unused ad or waits for a new one. */
    @Deprecated("Native presentations end on page departure; return always starts a new visit.")
    val reloadAdOnStepReturn: Boolean = false,
    /**
     * A forward swipe on the last step completes it exactly like its CTA. Independent of
     * [lockPagerSwipe]: the gesture is read off the window, not the pager, so it works while
     * the pager itself stays locked.
     */
    val swipeCompletesLastStep: Boolean = true,
    /**
     * Default navigation on return from a step ad. Step natives disable click replacement.
     * When enabled, coming back from a step ad's click completes that step exactly like its CTA — the next
     * step on a middle page, the flow exit on the last one. Only clicks on the pager's own
     * step ads count; a click on the language or question screen never moves the pager.
     */
    val adClickReturnCompletesStep: Boolean = true,
    /** Also locks the app splash; configChanges orientation|screenSize stops that recreating it. */
    val lockPortrait: Boolean = true,
)

class ObConfigException(val errors: List<String>) :
    IllegalArgumentException("Invalid OnboardKit config:\n" + errors.joinToString("\n"))

/**
 * Full compile-time configuration. Build via [onboardKitConfig]; validation returns a
 * [Result] instead of throwing from a constructor.
 */
class OnboardKitConfig internal constructor(
    val splash: SplashConfig,
    val language: LanguageConfig,
    val steps: List<StepDefinition>,
    val question: QuestionConfig?,
    val ads: AdsConfig,
    val system: SystemBarConfig,
    val behavior: BehaviorConfig,
) {
    fun stepById(id: StepId): StepDefinition? = steps.firstOrNull { it.id == id }

    val contentSteps: List<StepDefinition> get() = steps.filter { it.type == StepType.CONTENT }
}

class OnboardKitConfigBuilder internal constructor() {
    var splash: SplashConfig = SplashConfig()
    var language: LanguageConfig = LanguageConfig()
    var question: QuestionConfig? = null
    var ads: AdsConfig = AdsConfig()
    var system: SystemBarConfig = SystemBarConfig()
    var behavior: BehaviorConfig = BehaviorConfig()

    private val stepList = mutableListOf<StepDefinition>()

    fun steps(vararg definitions: StepDefinition) {
        stepList += definitions
    }

    fun step(definition: StepDefinition) {
        stepList += definition
    }

    /** The classic OB1..OB4 template: three content steps and one full-screen ad step. */
    fun defaultSteps() {
        stepList += listOf(
            ContentStepDefinition(StepId.OB1),
            ContentStepDefinition(StepId.OB2),
            AdFullScreenStepDefinition(StepId.OB3),
            ContentStepDefinition(StepId.OB4),
        )
    }

    internal fun build(): Result<OnboardKitConfig> {
        val errors = mutableListOf<String>()

        val duplicated = stepList.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            errors += "[steps] Duplicated step ids: ${duplicated.joinToString { it.value }}"
        }
        question?.let { q ->
            if (q.minSelection < 1) errors += "[question] minSelection must be >= 1"
            val dupOptions = q.options.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupOptions.isNotEmpty()) {
                errors += "[question] Duplicated option ids: ${dupOptions.joinToString()}"
            }
        }
        if (language.languages.isEmpty()) {
            errors += "[language] Language list must not be empty"
        }
        validateAdIds(errors)
        validateCustomLayouts(errors)

        return if (errors.isEmpty()) {
            Result.success(
                OnboardKitConfig(
                    splash = splash,
                    language = language,
                    steps = stepList.toList(),
                    question = question,
                    ads = ads,
                    system = system,
                    behavior = behavior,
                ),
            )
        } else {
            Result.failure(ObConfigException(errors))
        }
    }

    /**
     * Rejects the `layoutRes` knobs no screen reads yet.
     *
     * Only [SplashConfig.layoutRes] and [ContentStepDefinition.layoutRes] are wired to a screen.
     * The rest were declared ahead of the screens that would honour them, so setting one used to
     * do nothing at all — the app shipped its own design, saw the SDK's, and had no way to tell
     * why. Failing here says so at `configure()`, which is the only moment the answer is cheap.
     */
    private fun validateCustomLayouts(errors: MutableList<String>) {
        fun reject(name: String, value: Int) {
            if (value == 0) return
            errors += "[layout] $name is not honoured by any screen yet — remove it. " +
                "Custom layouts are supported on SplashConfig.layoutRes and " +
                "ContentStepDefinition.layoutRes only."
        }
        reject("LanguageConfig.layoutRes", language.layoutRes)
        reject("LanguageConfig.itemLayoutRes", language.itemLayoutRes)
        question?.let {
            reject("QuestionConfig.layoutRes", it.layoutRes)
            reject("QuestionConfig.optionLayoutRes", it.optionLayoutRes)
        }
        stepList.filterIsInstance<AdFullScreenStepDefinition>().forEach { step ->
            reject("AdFullScreenStepDefinition(${step.id.value}).layoutRes", step.layoutRes)
        }
    }

    private fun validateAdIds(errors: MutableList<String>) {
        // A tier list is checked as a whole: an all-blank list leaves nothing to request, and a
        // duplicated id would make the same unit lose twice instead of falling through a floor.
        fun checkTiers(name: String, unit: AdUnitTiers?) {
            if (unit == null) return
            if (unit.loadOrder.isEmpty()) {
                errors += "[ads] $name must declare at least one non-blank ad unit id"
                return
            }
            if (unit.tiers.any { it.isBlank() }) {
                errors += "[ads] $name has a blank id in tiers=${unit.tiers} " +
                    "— remove it instead of shipping a hole in the waterfall"
            }
            val duplicates = unit.tiers.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                errors += "[ads] $name repeats ad unit id(s) $duplicates across tiers"
            }
        }
        checkTiers("languageNative", ads.languageNative)
        checkTiers("languageDupNative", ads.languageDupNative)
        checkTiers("languageConfirmNative", ads.languageConfirmNative)
        checkTiers("contentStepNative", ads.contentStepNative)
        checkTiers("fullScreenStepNative", ads.fullScreenStepNative)
        checkTiers("ob5Native", ads.ob5Native)
        checkTiers("questionNative", ads.questionNative)
        checkTiers("splashInterstitial", ads.splashInterstitial)
        checkTiers("questionInterstitial", ads.questionInterstitial)
        checkTiers("afterOnboardingInterstitial", ads.afterOnboardingInterstitial)
        val banner = ads.splashBanner
        if (banner != null && banner.id.isBlank()) {
            errors += "[ads] splashBanner.id must not be blank"
        }
    }
}

fun onboardKitConfig(block: OnboardKitConfigBuilder.() -> Unit): Result<OnboardKitConfig> =
    OnboardKitConfigBuilder().apply(block).build()
