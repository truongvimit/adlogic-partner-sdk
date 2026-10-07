package com.ads.module.config

import androidx.annotation.Keep
import com.ads.module.ads.AdWaterfall
import com.ads.module.helper.adnative.NativeClickAction

/**
 * One placement's entry in `ad_config.json`: which ad units to request, whether it is on, and how
 * its native template should look. A placement is one key whatever its number of floors; every
 * other field applies to the whole waterfall.
 */
@Keep
data class AdUnitConfig(
    /**
     * The enabled floors of the waterfall, highest first and all-price last; a single-unit
     * placement is a one-floor list. JSON spells each floor `{"id": …, "isEnable": …}` so a floor
     * is switched off without losing its id; only the enabled ones land here.
     */
    val ids: List<String>,
    val isEnable: Boolean,
    val enableUaCheck: Boolean = false,
    val reloadIntervalSeconds: Int? = null,
    val colorCTA: String = "default",
    /** The native card's background color; `"default"` keeps the layout's own background. */
    val colorBackground: String = "default",
    /** The Ad badge's background color; `"default"` falls back to [colorCTA], then the layout. */
    val colorAdBadge: String = "default",
    /** The Ad badge's text color; `"default"` keeps the layout's text color. */
    val colorAdBadgeText: String = "default",
    val heightCTA: Int = DEFAULT_HEIGHT_CTA,
    /**
     * Blocks to show, top to bottom; the only thing that orders a native. Templates that cannot
     * reorder ignore it.
     */
    val components: List<String> = DEFAULT_COMPONENTS,
    /** Extra wait after process ON_STOP before loading the app-resume placement. */
    val appResumeLoadDelayMs: Long = AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS,
    /**
     * What a click on this native does once the user returns; `null` leaves it to the screen's
     * default.
     */
    val clickAction: NativeClickAction? = null,
    /**
     * The SDK native template this placement renders with; `null` keeps the placement's default
     * layout. Which number means which layout is decided by the module that owns the layouts —
     * an unknown number falls back to that default too.
     */
    val templateId: Int? = null,
) {

    /**
     * [ids] as requested: blanks and repeats are dropped by [AdWaterfall.usableIds] — the same
     * rule the loader applies, rather than a second copy of it.
     */
    val waterfallIds: List<String>
        get() = AdWaterfall.usableIds(ids)

    /** True when this unit is switched on and has at least one usable id. */
    val isUsable: Boolean get() = isEnable && waterfallIds.isNotEmpty()

    companion object {
        /** CTA height in dp when a placement declares none: the button height of the SDK templates. */
        const val DEFAULT_HEIGHT_CTA: Int = 44

        /** Every block, CTA last: a placement that declares no order shows its button at the bottom. */
        @JvmField
        val DEFAULT_COMPONENTS: List<String> = listOf("icon_headline", "body", "media", "cta")
    }
}
