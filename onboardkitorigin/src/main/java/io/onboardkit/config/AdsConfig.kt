package io.onboardkit.config

import io.onboardkit.ads.AdPlacement
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
 * | [fullScreenStepNative] | the ad-only onboarding page (OB3) |
 * | [ob5Native] | native on the extra onboarding page after OB4 (OB5) |
 * | [questionNative] | native on the survey/question screen |
 * | [questionInterstitial] | full-screen ad after the survey is submitted |
 */
data class AdsConfig(
    /** Master switch. `false` disables every placement below without unsetting them. */
    val enabled: Boolean = true,
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
    val contentStepTemplate: NativeTemplate = NativeTemplate.CTA_TOP,
    val languageTemplate: NativeTemplate = NativeTemplate.CTA_BOTTOM,
    val questionTemplate: NativeTemplate = NativeTemplate.CTA_BOTTOM,
    /** Premium users skip the steps that contain nothing but a full-screen ad. */
    val skipAdOnlyStepsWhenPremium: Boolean = true,
) {

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
