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
    @LayoutRes open val layoutId: Int,
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

    private var overridesKey: String? = null

    /** Lets a containing flow forbid the replacement preloads that setEnablePreload asks for. */
    open val canPreloadReplacement: Boolean get() = true

    var behavior: BehaviorValues? = null
    private val declaredCanReloadAds = canReloadAds
    internal fun behaviorValues() =
        behavior ?: AdBehavior.values("native", placementKey ?: overridesKey)
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
     * Code default for a click; `ad_config.<key>.click_action` outranks it. RELOAD and
     * RELOAD_WATERFALL preload an unused replacement on click/open and show it on return,
     * independent of [canReloadAds]. AUTO_NEXT navigation belongs to the host.
     */
    var clickAction: NativeClickAction = NativeClickAction.RELOAD
    open val resolvedClickAction: NativeClickAction
        get() = (placementKey ?: overridesKey)?.let { AdRemoteConfig.getInstance().ads[it]?.clickAction }
            ?: clickAction

    /** Trailing debounce for the reload-on-resume trigger. */
    var timeDebounceResume: Long = AdBehavior.defaultNumber("native.reload.resume_debounce_ms")
        get() = behaviorValues().long("reload.resume_debounce_ms", field)

    /** Routes the request through the UA/organic gate before it may go out. */
    private var explicitForceUaCheck: Boolean? = null
    var forceUaCheck: Boolean
        get() = explicitForceUaCheck ?: (placementKey ?: overridesKey)?.let { uaCheckFor(it) } ?: false
        set(value) {
            explicitForceUaCheck = value
        }

    /** Reads a placement's current UA gate; raw configs keep their explicit value. */
    internal fun shouldForceUaCheck(): Boolean = forceUaCheck

    /** The helper binds a ready fill or joins a pending load; only reloads and clicks request. */
    var joinOnly: Boolean = false

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
        }

        /**
         * Explicit [tiers]; [singleFill] never refills or reloads by itself, though a click still
         * follows its click action. Lambdas serve an embedding SDK.
         */
        @JvmStatic
        @JvmOverloads
        fun forUnits(
            tiers: List<String>,
            @LayoutRes layoutId: Int,
            adConfigKey: String? = null,
            joinOnly: Boolean = false,
            singleFill: Boolean = false,
            liveLayoutId: (() -> Int)? = null,
            liveClickAction: (() -> NativeClickAction)? = null,
        ): NativeAdConfig {
            val config: NativeAdConfig =
                UnitsConfig(tiers, layoutId, singleFill, liveLayoutId, liveClickAction)
            config.overridesKey = adConfigKey
            config.joinOnly = joinOnly
            return config
        }

        private fun uaCheckFor(key: String?): Boolean =
            key?.let { AdRemoteConfig.getInstance().ads[it]?.enableUaCheck } == true
    }

    private class UnitsConfig(
        tiers: List<String>,
        @LayoutRes layoutId: Int,
        private val singleFill: Boolean,
        private val liveLayoutId: (() -> Int)?,
        private val liveClickAction: (() -> NativeClickAction)?,
    ) : NativeAdConfig(tiers, true, false, layoutId) {
        override val layoutId: Int get() = liveLayoutId?.invoke() ?: super.layoutId
        override val canPreloadReplacement: Boolean get() = !singleFill
        override val canReloadAds: Boolean get() = !singleFill && super.canReloadAds
        override val resolvedClickAction: NativeClickAction
            get() = liveClickAction?.invoke() ?: super.resolvedClickAction
    }
}
