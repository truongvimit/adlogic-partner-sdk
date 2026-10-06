package com.ads.module.config

import androidx.annotation.Keep
import com.ads.module.ads.AdWaterfall
import com.ads.module.helper.adnative.NativeClickAction

/**
 * One placement's entry in `ad_config.json`: which ad unit to request, whether it is on, and how
 * its native template should look.
 */
@Keep
data class AdUnitConfig(
    val id: String,
    val isEnable: Boolean,
    val enableUaCheck: Boolean = false,
    val reloadIntervalSeconds: Int? = null,
    val colorCTA: String = "default",
    val heightCTA: Int = DEFAULT_HEIGHT_CTA,
    /**
     * Blocks to show, top to bottom; the only thing that orders a native. Templates that cannot
     * reorder ignore it.
     */
    val components: List<String> = DEFAULT_COMPONENTS,
    /**
     * Optional waterfall tiers, ordered highest floor first. Empty means "single tier", i.e.
     * exactly the behaviour of [id] alone, so a payload that declares no tiers keeps working.
     */
    val ids: List<String> = emptyList(),
    /** Extra wait after process ON_STOP before loading the app-resume placement. */
    val appResumeLoadDelayMs: Long = AdRemoteConfig.DEFAULT_APP_RESUME_LOAD_DELAY_MS,
    /**
     * What a click on this native does once the user returns; `null` leaves it to the screen's
     * default. Read from the base key only, never from its `_high` floors.
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
     * The ad unit ids to request, ordered highest floor first.
     *
     * [id] stays the all-price/last-chance tier: when [ids] carries the high floors, [id] is
     * appended below them unless it is already listed. Blanks and repeats are dropped by
     * [AdWaterfall.usableIds] — the same rule the loader applies, rather than a second copy of it.
     */
    val waterfallIds: List<String>
        get() = AdWaterfall.usableIds(ids + id)

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
