package com.itg.template.ads

import androidx.annotation.Keep

@Keep
data class AdUnitConfig(
    val id: String,
    val isEnable: Boolean,
    val enableUaCheck: Boolean = false,
    val reloadIntervalSeconds: Int? = null,
    val colorCTA: String = "default",
    val heightCTA: Int = 40,
    val positionCTA: String = "BOTTOM",
    val components: List<String> = listOf("icon_headline", "body", "media", "cta"),
    /**
     * Optional waterfall tiers, ordered highest floor first. Empty means "single tier", i.e.
     * exactly the legacy behaviour of [id] alone, so existing remote payloads keep working.
     */
    val ids: List<String> = emptyList(),
) {

    /**
     * The ad unit ids to request, ordered highest floor first.
     *
     * [id] stays the all-price/last-chance tier: when [ids] carries the high floors, [id] is
     * appended below them unless it is already listed. Blanks and repeats are dropped so a
     * half-filled remote payload cannot open a hole in the waterfall.
     */
    val waterfallIds: List<String>
        get() = (ids + id).filter { it.isNotBlank() }.distinct()

    /** True when this unit is switched on and has at least one usable id. */
    val isUsable: Boolean get() = isEnable && waterfallIds.isNotEmpty()
}
