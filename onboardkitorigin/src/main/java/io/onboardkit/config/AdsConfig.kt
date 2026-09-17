package io.onboardkit.config

import io.onboardkit.remote.OnboardingSettings
import com.ads.module.config.AdRemoteConfig
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.core.StepId

/**
 * The ad unit ids one placement may use, ordered highest floor first.
 *
 * The provider walks them one at a time and stops at the first fill, so a lower floor is only
 * requested once the one above it has failed. You normally do not build this by hand — prefer the
 * named constructors, which make the floor order impossible to get backwards:
 *
 * ```
 * // one ad unit, no waterfall — the common case
 * NativeAdUnit("ca-app-pub-…/1111111111")
 *
 * // high floor first, all-price as the fallback
 * NativeAdUnit.waterfall(
 *     highFloor = "ca-app-pub-…/1111111111",
 *     allPrice  = "ca-app-pub-…/2222222222",
 * )
 * ```
 *
 * Sealed on purpose: [NativeAdUnit] and [InterstitialAdUnit] are separate types so a native id
 * can never be handed to an interstitial placement.
 */
sealed interface AdUnitTiers {

    /** The ids as declared, **highest floor first**. Position is request order. */
    val tiers: List<String>

    /** The ids a provider actually requests: [tiers] minus blanks and repeats. */
    val loadOrder: List<String> get() = tiers.filter { it.isNotBlank() }.distinct()

    /** How many ids will really be requested. `1` means there is no waterfall. */
    val tierCount: Int get() = loadOrder.size

    /**
     * The highest-floor id, i.e. the one tried first. Reported by analytics as the id a load was
     * requested for. Blank only for a config that failed validation.
     */
    val topTierId: String get() = loadOrder.firstOrNull().orEmpty()
}

/** Ad unit ids for a native placement. See [AdUnitTiers] for how to build one. */
data class NativeAdUnit(override val tiers: List<String>) : AdUnitTiers {

    /** A single ad unit id, no waterfall. */
    constructor(adUnitId: String) : this(listOf(adUnitId))

    companion object {
        /**
         * A waterfall with its floors named, so the priority order cannot be swapped by accident.
         * Arguments read in request order: high → medium → all-price.
         */
        fun waterfall(
            highFloor: String,
            mediumFloor: String? = null,
            allPrice: String,
        ): NativeAdUnit = NativeAdUnit(listOfNotNull(highFloor, mediumFloor, allPrice))
    }
}

/** Ad unit ids for an interstitial placement. See [AdUnitTiers] for how to build one. */
data class InterstitialAdUnit(override val tiers: List<String>) : AdUnitTiers {

    /** A single ad unit id, no waterfall. */
    constructor(adUnitId: String) : this(listOf(adUnitId))

    companion object {
        /**
         * A waterfall with its floors named, so the priority order cannot be swapped by accident.
         * Arguments read in request order: high → medium → all-price.
         */
        fun waterfall(
            highFloor: String,
            mediumFloor: String? = null,
            allPrice: String,
        ): InterstitialAdUnit = InterstitialAdUnit(listOfNotNull(highFloor, mediumFloor, allPrice))
    }
}

/** Banners refresh in place rather than falling through floors, so they take a single id. */
data class BannerAdUnit(val id: String)

/** Which of the SDK native templates a placement renders with by default. */
enum class NativeTemplate { CTA_BOTTOM, CTA_TOP, COMPACT, FULL_SCREEN, DIALOG }

/**
 * Every ad slot the onboarding flow can fill. Leave a field `null` and that slot simply shows
 * no ad — nothing else in the flow changes.
 *
 * Fields are named after the screen they appear on:
 *
 * | Field | Where it shows |
 * |---|---|
 * | [splashBanner] | banner at the bottom of the splash screen |
 * | [splashInterstitial] | full-screen ad after the splash, before the flow starts |
 * | [languageNative] | native on the language picker (first open) |
 * | [languageDupNative] | native that replaces it on the first language tap |
 * | [languageConfirmNative] | native inside the "Confirm Language" modal |
 * | [contentStepNative] | native on each onboarding content page |
 * | [fullScreenStepNative] | the ad-only onboarding pages |
 * | [ob5Native] | native on the extra onboarding page after OB4 (OB5) |
 * | [questionNative] | native on the survey/question screen |
 * | [questionInterstitial] | full-screen ad after the survey is submitted |
 */
data class AdsConfig(
    /** Master switch. `false` disables every placement below without unsetting them. */
    val enabled: Boolean = OnboardingSettings.defaultBool("flow.ads_enabled"),
    val splashBanner: BannerAdUnit? = null,
    val splashInterstitial: InterstitialAdUnit? = null,
    /**
     * Splash interstitial for a returning user, who is worth a different floor than a first-open
     * one. Unset falls back to [splashInterstitial], so segmenting is opt-in.
     */
    val splashInterstitialOldUser: InterstitialAdUnit? = null,
    val languageNative: NativeAdUnit? = null,
    /**
     * Separate pool for the second native on the language screen: tapping a language swaps the
     * first native for this one. Same screen, second impression — give it its own ad unit id.
     */
    val languageDupNative: NativeAdUnit? = null,
    /**
     * Native inside the "Confirm Language" modal — the prompt raised when the user taps the
     * language they already have selected.
     *
     * Left `null` the modal still opens, just without an ad; it is a confirmation first and an
     * ad slot second, so an unfilled placement must never cost the user their way forward.
     */
    val languageConfirmNative: NativeAdUnit? = null,
    /** Shared by every content page that has no entry in [stepNatives]. */
    val contentStepNative: NativeAdUnit? = null,
    val fullScreenStepNative: NativeAdUnit? = null,
    /**
     * Per-page ad units, for apps that sell each onboarding page separately.
     *
     * A page with no entry here falls back to [contentStepNative] / [fullScreenStepNative], so
     * declaring one page does not force you to declare them all.
     *
     * ```
     * stepNatives = mapOf(
     *     StepId.OB1 to NativeAdUnit.waterfall(highFloor = "…/1111", allPrice = "…/2222"),
     *     StepId.OB2 to NativeAdUnit("…/3333"),
     * )
     * ```
     */
    val stepNatives: Map<StepId, NativeAdUnit> = emptyMap(),
    /** OB5 pool is its own field — the original reused OB3's config by accident. */
    val ob5Native: NativeAdUnit? = null,
    val questionNative: NativeAdUnit? = null,
    val questionInterstitial: InterstitialAdUnit? = null,
    /** App-resume / app-open ad, shown when the app returns to the foreground. */
    val appResume: InterstitialAdUnit? = null,
    val contentStepTemplate: NativeTemplate = NativeTemplate.valueOf(OnboardingSettings.defaultText("onboarding.ads.content_template")),
    val languageTemplate: NativeTemplate = NativeTemplate.valueOf(OnboardingSettings.defaultText("lfo.native_template")),
    val questionTemplate: NativeTemplate = NativeTemplate.valueOf(OnboardingSettings.defaultText("question.native.template")),
    /** Premium users skip the steps that contain nothing but a full-screen ad. */
    val skipAdOnlyStepsWhenPremium: Boolean = OnboardingSettings.defaultBool("flow.skip_ad_only_steps_when_premium"),
    /** Preloaded on pager entry; load-and-show on completion with an eight-second fill wait. */
    val afterOnboardingInterstitial: InterstitialAdUnit? = null,
    /** False disables both preload and presentation by the onboarding flow. */
    val afterOnboardingInterstitialEnabled: Boolean = OnboardingSettings.defaultBool("onboarding.exit_interstitial.enabled"),
    /** UNDER_AD starts the next screen under this ad; only an entry launch waits for close. */
    val afterOnboardingInterstitialTiming: NextScreenTiming = NextScreenTiming.valueOf(OnboardingSettings.defaultText("onboarding.exit_interstitial.next_screen_timing")),
    /** Shared Skip/X appearance for fullscreen steps and standalone OB5. */
    val fullScreenSkipStyle: FullScreenSkipStyle = FullScreenSkipStyle.valueOf(OnboardingSettings.defaultText("flow.fullscreen_skip_style")),
    /** App-owned association with ad_config keys; never duplicated in behavior JSON. */
    val placementKeys: Map<AdPlacement, String> = emptyMap(),
    /** Optional full-screen native between the splash interstitial and LFO; absent means off. */
    val splashNative: NativeAdUnit? = null,
) {
    companion object {
        /** Standard partner keys. Override only associations whose names differ in your app. */
        @JvmStatic
        @JvmOverloads
        fun fromAdConfig(placements: Map<AdPlacement, String> = emptyMap()): AdsConfig = AdsConfig(
            placementKeys = mapOf(
                AdPlacement.SplashBanner to "banner_splash",
                AdPlacement.SplashInterstitial to "inter_splash",
                AdPlacement.SplashNative to "native_fs",
                AdPlacement.AfterOnboardingInterstitial to "inter_after_ob3",
                AdPlacement.Language1 to "native_lang",
                AdPlacement.Language2 to "native_lang_alt",
                AdPlacement.LanguageConfirm to "native_popup_lang",
                AdPlacement.StepNative(StepId.OB1) to "native_ob1",
                AdPlacement.StepNative(StepId.OB2) to "native_ob2",
                AdPlacement.StepNative(StepId.OB3) to "native_ob3",
                AdPlacement.StepNative(StepId.OB4) to "native_ob4",
                AdPlacement.StepFullScreen(StepId.FULL1) to "native_full1",
                AdPlacement.StepFullScreen(StepId.FULL2) to "native_full2",
                AdPlacement.Ob5 to "native_onboarding_fullscreen_1_4",
                AdPlacement.QuestionNative to "native_question",
                AdPlacement.QuestionInterstitial to "inter_question",
                AdPlacement.AppResume to "open_resume",
            ) + placements,
        )
    }

    internal fun placementKeyFor(placement: AdPlacement): String? = placementKeys[placement]

    /** Resolve from the current ad document for both flow eligibility and ad requests. */
    internal fun resolvePlacements(config: AdRemoteConfig): AdsConfig {
        if (placementKeys.isEmpty()) return this
        fun tiers(p: AdPlacement): List<String>? = placementKeys[p]?.takeIf(config::declares)?.let(config::tiersFor)
        fun native(p: AdPlacement, local: NativeAdUnit?) = tiers(p)?.let(::NativeAdUnit) ?: local
        fun inter(p: AdPlacement, local: InterstitialAdUnit?) = tiers(p)?.let(::InterstitialAdUnit) ?: local
        val oldSplashKey = placementKeys[AdPlacement.SplashInterstitial]?.plus("_o")
        return copy(
            splashBanner = tiers(AdPlacement.SplashBanner)?.let { BannerAdUnit(it.firstOrNull().orEmpty()) } ?: splashBanner,
            splashInterstitial = inter(AdPlacement.SplashInterstitial, splashInterstitial),
            splashNative = native(AdPlacement.SplashNative, splashNative),
            splashInterstitialOldUser = oldSplashKey?.takeIf(config::declares)?.let { InterstitialAdUnit(config.tiersFor(it)) } ?: splashInterstitialOldUser,
            languageNative = native(AdPlacement.Language1, languageNative),
            languageDupNative = native(AdPlacement.Language2, languageDupNative),
            languageConfirmNative = native(AdPlacement.LanguageConfirm, languageConfirmNative),
            stepNatives = stepNatives.toMutableMap().apply {
                placementKeys.keys.forEach { p ->
                    val id = when (p) {
                        is AdPlacement.StepNative -> p.stepId
                        is AdPlacement.StepFullScreen -> p.stepId
                        else -> null
                    }
                    if (id != null) tiers(p)?.let { put(id, NativeAdUnit(it)) }
                }
            },
            ob5Native = native(AdPlacement.Ob5, ob5Native),
            questionNative = native(AdPlacement.QuestionNative, questionNative),
            questionInterstitial = inter(AdPlacement.QuestionInterstitial, questionInterstitial),
            afterOnboardingInterstitial = inter(AdPlacement.AfterOnboardingInterstitial, afterOnboardingInterstitial),
            appResume = inter(AdPlacement.AppResume, appResume),
        )
    }

    /** [unitFor] narrowed to the native placements, so a screen cannot ask for the wrong type. */
    fun nativeUnitFor(placement: AdPlacement): NativeAdUnit? = unitFor(placement) as? NativeAdUnit

    /**
     * The ad units a placement may spend, or `null` when the partner left the slot empty.
     *
     * One mapping for the whole SDK: the gate, the preload chain and the screens used to each
     * reach into a different field, which is how OB5 ended up silently reusing OB3's pool.
     */
    fun unitFor(placement: AdPlacement): AdUnitTiers? = when (placement) {
        AdPlacement.SplashBanner -> splashBanner?.let { NativeAdUnit(it.id) }
        AdPlacement.SplashInterstitial -> splashInterstitial
        AdPlacement.SplashNative -> splashNative
        AdPlacement.AfterOnboardingInterstitial -> afterOnboardingInterstitial
        AdPlacement.Language1 -> languageNative
        AdPlacement.Language2 -> languageDupNative ?: languageNative
        AdPlacement.LanguageConfirm -> languageConfirmNative
        is AdPlacement.StepNative -> stepNatives[placement.stepId] ?: contentStepNative
        is AdPlacement.StepFullScreen -> stepNatives[placement.stepId] ?: fullScreenStepNative
        AdPlacement.Ob5 -> ob5Native ?: stepNatives[StepId.OB5] ?: fullScreenStepNative
        AdPlacement.QuestionNative -> questionNative
        AdPlacement.QuestionInterstitial -> questionInterstitial
        AdPlacement.AppResume -> appResume
    }
}
