package com.ads.module.helper.adnative

import androidx.annotation.LayoutRes
import com.ads.module.ads.AdWaterfall
import com.ads.module.helper.IAdsConfig

/**
 * Config for one native placement.
 *
 * [adUnitIds] is the waterfall, **highest floor first** — the next id is only requested
 * once the one above it failed to fill. A single-element list means no waterfall.
 */
open class NativeAdConfig(
    tiers: List<String>,
    override val canShowAds: Boolean,
    override val canReloadAds: Boolean,
    @LayoutRes val layoutId: Int,
) : IAdsConfig {

    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        @LayoutRes layoutId: Int,
    ) : this(listOf(idAds), canShowAds, canReloadAds, layoutId)

    /** Declared tiers minus blanks and repeats, in request order. */
    val adUnitIds: List<String> = AdWaterfall.usableIds(tiers)

    override val idAds: String get() = adUnitIds.firstOrNull().orEmpty()

    /** Trailing debounce for the reload-on-resume trigger. */
    var timeDebounceResume: Long = DEFAULT_TIME_DEBOUNCE_RESUME_MS

    /** Routes the request through the UA/organic gate before it may go out. */
    var forceUaCheck: Boolean = false

    /** How long one waterfall tier may take before the next floor is tried. */
    var tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS

    companion object {
        const val DEFAULT_TIME_DEBOUNCE_RESUME_MS: Long = 500L
    }
}
