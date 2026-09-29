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
    val heightCTA: Int = 40,
    /**
     * Where the CTA sits, for the screens that pick a dedicated layout per position — the
     * onboarding flow does. `null` means no opinion, and then [components] decides the order.
     */
    val positionCTA: String? = null,
    val components: List<String> = listOf("icon_headline", "body", "media", "cta"),
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
}
