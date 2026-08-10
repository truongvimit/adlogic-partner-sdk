package io.onboardkit.config

/**
 * How a placement spends its ad unit ids when it has more than one.
 *
 * Pick [CASCADE] unless you have a reason not to — it is the default and the safe choice.
 * This is unrelated to [AdLoadStrategy], which only decides *when* the splash starts loading.
 */
enum class AdTierStrategy {
    /**
     * Try one id at a time, top to bottom, moving on only when the current one fails to fill.
     *
     * Costs one request per attempt and keeps each id's match rate honest, but a failed id adds
     * its own timeout to the wait. Default; correct for every placement the user is not staring
     * at a spinner for.
     */
    CASCADE,

    /**
     * Request every id at once and show the highest-priority one that filled.
     *
     * Fastest fill, but it spends a request on every id and drags down the match rate of the
     * ids that lose the race. Reasonable on splash, where the wait is visible; wasteful deeper
     * in the flow.
     */
    PARALLEL,
}

/**
 * The ad unit ids one placement may use, ordered highest floor first.
 *
 * You normally do not build this by hand. Prefer the named constructors, which make the floor
 * order impossible to get backwards:
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

    /**
     * The ids as declared, **highest floor first**. Position is priority: the first entry is
     * requested first (or wins the race, under [AdTierStrategy.PARALLEL]).
     */
    val tiers: List<String>

    val strategy: AdTierStrategy

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
data class NativeAdUnit(
    override val tiers: List<String>,
    override val strategy: AdTierStrategy = AdTierStrategy.CASCADE,
) : AdUnitTiers {

    /** A single ad unit id, no waterfall. */
    constructor(adUnitId: String) : this(listOf(adUnitId))

    companion object {
        /**
         * A waterfall with its floors named, so the priority order cannot be swapped by
         * accident. Arguments read in request order: high → medium → all-price.
         */
        fun waterfall(
            highFloor: String,
            mediumFloor: String? = null,
            allPrice: String,
            strategy: AdTierStrategy = AdTierStrategy.CASCADE,
        ): NativeAdUnit =
            NativeAdUnit(listOfNotNull(highFloor, mediumFloor, allPrice), strategy)
    }
}

/** Ad unit ids for an interstitial placement. See [AdUnitTiers] for how to build one. */
data class InterstitialAdUnit(
    override val tiers: List<String>,
    override val strategy: AdTierStrategy = AdTierStrategy.CASCADE,
) : AdUnitTiers {

    /** A single ad unit id, no waterfall. */
    constructor(adUnitId: String) : this(listOf(adUnitId))

    companion object {
        /**
         * A waterfall with its floors named, so the priority order cannot be swapped by
         * accident. Arguments read in request order: high → medium → all-price.
         */
        fun waterfall(
            highFloor: String,
            mediumFloor: String? = null,
            allPrice: String,
            strategy: AdTierStrategy = AdTierStrategy.CASCADE,
        ): InterstitialAdUnit =
            InterstitialAdUnit(listOfNotNull(highFloor, mediumFloor, allPrice), strategy)
    }
}

/** Banners refresh in place rather than falling through floors, so they take a single id. */
data class BannerAdUnit(val id: String)

/** Which of the SDK native templates a placement renders with by default. */
enum class NativeTemplate { CTA_BOTTOM, CTA_TOP, COMPACT, FULL_SCREEN }

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
    val languageNative: NativeAdUnit? = null,
    /**
     * Separate pool for the second native on the language screen: tapping a language swaps the
     * first native for this one. Same screen, second impression — give it its own ad unit id.
     */
    val languageDupNative: NativeAdUnit? = null,
    val contentStepNative: NativeAdUnit? = null,
    val fullScreenStepNative: NativeAdUnit? = null,
    /** OB5 pool is its own field — the original reused OB3's config by accident. */
    val ob5Native: NativeAdUnit? = null,
    val questionNative: NativeAdUnit? = null,
    val questionInterstitial: InterstitialAdUnit? = null,
    val contentStepTemplate: NativeTemplate = NativeTemplate.CTA_TOP,
    val languageTemplate: NativeTemplate = NativeTemplate.CTA_BOTTOM,
    val questionTemplate: NativeTemplate = NativeTemplate.CTA_BOTTOM,
    /** Premium users skip the steps that contain nothing but a full-screen ad. */
    val skipAdOnlyStepsWhenPremium: Boolean = true,
)
