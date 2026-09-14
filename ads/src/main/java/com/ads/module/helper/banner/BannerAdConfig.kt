package com.ads.module.helper.banner

import com.ads.module.config.settings.AdBehavior
import com.ads.module.config.settings.BehaviorValues
import com.ads.module.ads.AdWaterfall
import com.ads.module.config.AdRemoteConfig
import com.ads.module.helper.AdGate
import com.ads.module.helper.IAdsConfig

/**
 * Config for one banner placement.
 *
 * [adUnitIds] is the waterfall, **highest floor first** — the helper falls through to the
 * next id only when the one above it failed to fill. A single id means no waterfall.
 *
 * Choose one refresh owner. With AdMob console refresh, set [canReloadAds] and
 * [enableAutoReload] false. SDK refresh requires console refresh disabled for every tier;
 * enable [canReloadAds] for resume reload and optionally [enableAutoReload] for its timer.
 * These flags cannot inspect or change the console setting.
 */
open class BannerAdConfig @JvmOverloads constructor(
    tiers: List<String>,
    canShowAds: Boolean,
    canReloadAds: Boolean,
    bannerType: BannerType = defaultBannerType(),
) : IAdsConfig {

    @JvmOverloads
    constructor(
        idAds: String,
        canShowAds: Boolean,
        canReloadAds: Boolean,
        bannerType: BannerType = defaultBannerType(),
    ) : this(listOf(idAds), canShowAds, canReloadAds, bannerType)

    /**
     * Set by [forPlacement]. While it stands the waterfall and the on/off switch are re-read from
     * `ad_config.json` on every request, so a remote refresh reaches a helper already on screen.
     */
    private var placementKey: String? = null

    var behavior: BehaviorValues? = null
    private val declaredBannerType = bannerType
    val bannerType: BannerType get() = resolveBannerType(declaredBannerType, behaviorValues())
    private val declaredCanReloadAds = canReloadAds
    internal fun behaviorValues() = behavior ?: AdBehavior.values("banner", placementKey)
    override val canReloadAds: Boolean get() = behaviorValues().boolean("reload.allowed", declaredCanReloadAds)

    private val declaredCanShowAds: Boolean = canShowAds
    private val declaredAdUnitIds: List<String> = AdWaterfall.usableIds(tiers)

    override val canShowAds: Boolean
        get() = placementKey?.let { AdGate.placementEnabled(it) } ?: declaredCanShowAds

    /** Declared tiers minus blanks and repeats, in request order. */
    val adUnitIds: List<String>
        get() = placementKey?.let { AdGate.adUnitIds(it) } ?: declaredAdUnitIds

    override val idAds: String get() = adUnitIds.firstOrNull().orEmpty()

    /** Re-load cadence once [enableAutoReload] is on. */
    var autoReloadTime: Long = AdBehavior.defaultNumber("banner.reload.interval_ms")
        get() = behaviorValues().long("reload.interval_ms", field)
        set(value) {
            require(value >= MIN_AUTO_RELOAD_MS) { "Time can not < ${MIN_AUTO_RELOAD_MS}ms" }
            field = value
        }

    /** Adds an SDK timer; false alone does not disable reload-on-resume. */
    var enableAutoReload: Boolean = AdBehavior.defaultBool("banner.reload.auto_enabled")
        get() = behaviorValues().boolean("reload.auto_enabled", field)

    /** Trailing debounce for the reload-on-resume trigger. */
    var timeDebounceResume: Long = AdBehavior.defaultNumber("banner.reload.resume_debounce_ms")
        get() = behaviorValues().long("reload.resume_debounce_ms", field)

    /** Routes the request through the UA/organic gate before it may go out. */
    var forceUaCheck: Boolean = false

    companion object {
        /**
         * The config for [placement], resolved from `ad_config.json`: waterfall, on/off switch and
         * `enable_ua_check`, re-read on every request.
         */
        @JvmStatic
        @JvmOverloads
        fun forPlacement(
            placement: String,
            bannerType: BannerType = defaultBannerType(),
            canReloadAds: Boolean = AdBehavior.defaultBool("banner.reload.allowed"),
        ): BannerAdConfig = BannerAdConfig(emptyList(), true, canReloadAds, bannerType).apply {
            placementKey = placement
            forceUaCheck = AdRemoteConfig.getInstance().ads[placement]?.enableUaCheck == true
        }

        private fun defaultBannerType(): BannerType = when (AdBehavior.defaultText("banner.presentation.type")) {
            "LARGE_ANCHORED" -> BannerType.LargeAnchored
            "COLLAPSIBLE" -> BannerType.Collapsible(AdBehavior.defaultText("banner.presentation.collapsible_gravity").lowercase(java.util.Locale.ROOT))
            "INLINE" -> BannerType.Inline(if (AdBehavior.defaultText("banner.presentation.inline_style") == "SMALL") com.ads.module.admob.Admob.BANNER_INLINE_SMALL_STYLE else com.ads.module.admob.Admob.BANNER_INLINE_LARGE_STYLE)
            "INLINE_MAX_HEIGHT" -> BannerType.InlineMaxHeight(AdBehavior.defaultNumber("banner.presentation.inline_max_height_dp").toInt())
            "FIXED" -> BannerType.Fixed(FixedBannerSize.valueOf(AdBehavior.defaultText("banner.presentation.fixed_size")))
            else -> BannerType.Normal
        }

        internal fun resolveBannerType(local: BannerType, v: BehaviorValues): BannerType {
            val localType = when (local) {
                BannerType.Normal -> "NORMAL"
                BannerType.LargeAnchored -> "LARGE_ANCHORED"
                is BannerType.Collapsible -> "COLLAPSIBLE"
                is BannerType.Inline -> "INLINE"
                is BannerType.InlineMaxHeight -> "INLINE_MAX_HEIGHT"
                is BannerType.Fixed -> "FIXED"
            }
            return when (v.string("presentation.type", localType)) {
                "LARGE_ANCHORED" -> BannerType.LargeAnchored
                "COLLAPSIBLE" -> BannerType.Collapsible(v.string("presentation.collapsible_gravity", (local as? BannerType.Collapsible)?.gravity?.uppercase(java.util.Locale.ROOT) ?: "BOTTOM").lowercase(java.util.Locale.ROOT))
                "INLINE" -> BannerType.Inline(if (v.string("presentation.inline_style", if ((local as? BannerType.Inline)?.style == com.ads.module.admob.Admob.BANNER_INLINE_SMALL_STYLE) "SMALL" else "LARGE") == "SMALL") com.ads.module.admob.Admob.BANNER_INLINE_SMALL_STYLE else com.ads.module.admob.Admob.BANNER_INLINE_LARGE_STYLE)
                "INLINE_MAX_HEIGHT" -> BannerType.InlineMaxHeight(v.long("presentation.inline_max_height_dp", (local as? BannerType.InlineMaxHeight)?.maxHeightDp?.toLong() ?: AdBehavior.number("banner.presentation.inline_max_height_dp")).toInt())
                "FIXED" -> BannerType.Fixed(FixedBannerSize.valueOf(v.string("presentation.fixed_size", (local as? BannerType.Fixed)?.size?.name ?: "BANNER")))
                else -> BannerType.Normal
            }
        }

        const val DEFAULT_AUTO_RELOAD_MS: Long = 15_000L
        const val MIN_AUTO_RELOAD_MS: Long = 1_000L
        const val DEFAULT_TIME_DEBOUNCE_RESUME_MS: Long = 500L
    }
}
