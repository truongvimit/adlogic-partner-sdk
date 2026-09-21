package com.ads.module.tracking

import io.trackkit.AdFormat

/**
 * Maps an ad unit id back to the format it was requested with.
 *
 * Same out-of-band trick as `PlacementRegistry`, for the other half of the context the SDK
 * cannot supply: AdMob's click and paid callbacks know the ad unit and nothing else. Written once at
 * load time by [TrackingAdCallback], read at click time by `ERainLogEventManager`
 * (ironSource `addImpressionDataListener` does the same for its placement).
 *
 * Lives in `:ads`, not in the frozen port — only this module knows about ad units.
 */
object AdFormatRegistry {

    /**
     * Bounded like PlacementRegistry: an app has a handful of units, a leak here would be silent.
     */
    private const val MAX_ENTRIES = 64

    private val MAP =
        object : LinkedHashMap<String, AdFormat>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AdFormat>?): Boolean =
                size > MAX_ENTRIES
        }

    @JvmStatic
    fun register(adUnitId: String?, format: AdFormat?) {
        if (adUnitId == null || adUnitId.isEmpty() || format == null || format == AdFormat.UNKNOWN) {
            return
        }
        synchronized(MAP) {
            MAP.put(adUnitId, format)
        }
    }

    @JvmStatic
    fun formatOf(adUnitId: String?): AdFormat {
        if (adUnitId == null || adUnitId.isEmpty()) {
            return AdFormat.UNKNOWN
        }
        val format: AdFormat? = synchronized(MAP) {
            MAP[adUnitId]
        }
        return format ?: AdFormat.UNKNOWN
    }

    @JvmStatic
    fun clear() {
        synchronized(MAP) {
            MAP.clear()
        }
    }
}
