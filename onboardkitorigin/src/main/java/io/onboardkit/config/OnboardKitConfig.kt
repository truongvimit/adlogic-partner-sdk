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
    val consentTimeoutMs: Long = 15_000,
    val billingTimeoutMs: Long = 5_000,
    val adLoadStrategy: AdLoadStrategy = AdLoadStrategy.ALTERNATE,
)

data class LanguageConfig(
    val languages: List<ObLanguage> = ObLanguages.ALL,
    val defaultCode: String? = null,
    /**
     * On the first language tap, swaps the LFO's first native for a second one preloaded on
     * entry. Same screen, second impression — no duplicated Activity, no lost scroll position.
     */
    val secondNativeOnSelectEnabled: Boolean = true,
    @LayoutRes val layoutRes: Int = 0,
    @LayoutRes val itemLayoutRes: Int = 0,
)

data class SystemBarConfig(
    val showStatusBar: Boolean = true,
    val showNavigationBar: Boolean = true,
)

data class BehaviorConfig(
    /** Buttons are the single navigation source; swipe is locked. */
    val lockPagerSwipe: Boolean = true,
    /** Back returns to the previous step; on the first step it exits the app. */
    val backNavigatesBack: Boolean = true,
    /** Re-request the native when the user returns to a step. Off: avoids impression farming. */
    val reloadAdOnStepReturn: Boolean = false,
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
        checkTiers("contentStepNative", ads.contentStepNative)
        checkTiers("fullScreenStepNative", ads.fullScreenStepNative)
        checkTiers("ob5Native", ads.ob5Native)
        checkTiers("questionNative", ads.questionNative)
        checkTiers("splashInterstitial", ads.splashInterstitial)
        checkTiers("questionInterstitial", ads.questionInterstitial)
        val banner = ads.splashBanner
        if (banner != null && banner.id.isBlank()) {
            errors += "[ads] splashBanner.id must not be blank"
        }
    }
}

fun onboardKitConfig(block: OnboardKitConfigBuilder.() -> Unit): Result<OnboardKitConfig> =
    OnboardKitConfigBuilder().apply(block).build()
