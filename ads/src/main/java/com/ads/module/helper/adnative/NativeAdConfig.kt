package com.ads.module.helper.adnative

import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.settings.BehaviorValues
import androidx.annotation.LayoutRes
import com.ads.module.ads.AdWaterfall
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.AdGate
import com.ads.module.helper.IAdsConfig

/**
 * Config for one native placement.
 *
 * [adUnitIds] is the waterfall, **highest floor first** — the next id is only requested
 * once the one above it failed to fill. A single-element list means no waterfall.
 */
open class NativeAdConfig(
    tiers: List<String>,
    canShowAds: Boolean,
    canReloadAds: Boolean,
    @LayoutRes val layoutId: Int,
) : IAdsConfig {

    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        @LayoutRes layoutId: Int,
    ) : this(listOf(idAds), canShowAds, canReloadAds, layoutId)

    /**
     * Set by [forPlacement]. While it stands the waterfall and the on/off switch are re-read from
     * `ad_config.json` on every request, so a remote refresh reaches a helper already on screen.
     */
    private var placementKey: String? = null

    var behavior: BehaviorValues? = null
    private val declaredCanReloadAds = canReloadAds
    internal fun behaviorValues() = behavior ?: AdBehavior.values("native", placementKey)
    override val canReloadAds: Boolean get() = behaviorValues().boolean("reload.allowed", declaredCanReloadAds)

    private val declaredCanShowAds: Boolean = canShowAds
    private val declaredAdUnitIds: List<String> = AdWaterfall.usableIds(tiers)

    override val canShowAds: Boolean
        get() = placementKey?.let { AdGate.placementEnabled(it) } ?: declaredCanShowAds

    /** Declared tiers minus blanks and repeats, in request order. */
    val adUnitIds: List<String>
        get() = placementKey?.let { AdGate.adUnitIds(it) } ?: declaredAdUnitIds

    override val idAds: String get() = adUnitIds.firstOrNull().orEmpty()

    /**
     * Derive the loading skeleton from [layoutId] automatically when the helper was given
     * no explicit shimmer ([NativeAdHelper.setShimmerLayoutView] / [NativeAdHelper.setShimmerLayout]).
     */
    var autoShimmer: Boolean = AdBehavior.defaultBool("native.presentation.auto_shimmer")
        get() = behaviorValues().boolean("presentation.auto_shimmer", field)

    /**
     * Preload an unused replacement immediately on click/open, then show it on return (or
     * wait for the in-flight load). Independent of [canReloadAds]. Disable for slots whose
     * host navigates away on click-return, such as onboarding steps.
     */
    var reloadOnAdClick: Boolean = AdBehavior.defaultBool("native.reload.on_ad_click")
        get() = behaviorValues().boolean("reload.on_ad_click", field)

    /** Trailing debounce for the reload-on-resume trigger. */
    var timeDebounceResume: Long = AdBehavior.defaultNumber("native.reload.resume_debounce_ms")
        get() = behaviorValues().long("reload.resume_debounce_ms", field)

    /** Routes the request through the UA/organic gate before it may go out. */
    var forceUaCheck: Boolean = false

    /** How long one waterfall tier may take before the next floor is tried. */
    var tierTimeoutMs: Long = AdBehavior.defaultNumber("native.load.tier_timeout_ms")
        get() = behaviorValues().long("load.tier_timeout_ms", field)

    companion object {
        const val DEFAULT_TIME_DEBOUNCE_RESUME_MS: Long = 500L

        /**
         * The config for [placement], resolved from `ad_config.json`: waterfall, on/off switch and
         * `enable_ua_check`, re-read on every request.
         */
        @JvmStatic
        @JvmOverloads
        fun forPlacement(
            placement: String,
            @LayoutRes layoutId: Int,
            canReloadAds: Boolean = AdBehavior.defaultBool("native.reload.allowed"),
        ): NativeAdConfig = NativeAdConfig(emptyList(), true, canReloadAds, layoutId).apply {
            placementKey = placement
            forceUaCheck = AdRemoteConfig.getInstance().ads[placement]?.enableUaCheck == true
        }
    }
}
