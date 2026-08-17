package com.ads.module.helper.banner

import com.ads.module.ads.AdWaterfall
import com.ads.module.helper.IAdsConfig

/**
 * Config for one banner placement.
 *
 * [adUnitIds] is the waterfall, **highest floor first** — the helper falls through to the
 * next id only when the one above it failed to fill. A single id means no waterfall.
 */
open class BannerAdConfig @JvmOverloads constructor(
    tiers: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean,
    val bannerType: BannerType = BannerType.Normal,
) : IAdsConfig {

    @JvmOverloads
    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        bannerType: BannerType = BannerType.Normal,
    ) : this(listOf(idAds), canShowAds, canReloadAds, bannerType)

    /** Declared tiers minus blanks and repeats, in request order. */
    val adUnitIds: List<String> = AdWaterfall.usableIds(tiers)

    override val idAds: String get() = adUnitIds.firstOrNull().orEmpty()

    /** Re-load cadence once [enableAutoReload] is on. */
    var autoReloadTime: Long = DEFAULT_AUTO_RELOAD_MS
        set(value) {
            require(value >= MIN_AUTO_RELOAD_MS) { "Time can not < ${MIN_AUTO_RELOAD_MS}ms" }
            field = value
        }

    var enableAutoReload: Boolean = false

    /** Trailing debounce for the reload-on-resume trigger. */
    var timeDebounceResume: Long = DEFAULT_TIME_DEBOUNCE_RESUME_MS

    /** Routes the request through the UA/organic gate before it may go out. */
    var forceUaCheck: Boolean = false

    companion object {
        const val DEFAULT_AUTO_RELOAD_MS: Long = 15_000L
        const val MIN_AUTO_RELOAD_MS: Long = 1_000L
        const val DEFAULT_TIME_DEBOUNCE_RESUME_MS: Long = 500L
    }
}
