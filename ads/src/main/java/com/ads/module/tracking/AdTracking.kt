package com.ads.module.tracking

import com.ads.module.config.settings.AdBehavior

import io.trackkit.AdFormat
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import io.trackkit.PlacementRegistry

/**
 * What the host app still needs to say about ads that `ERainAd` cannot know by itself:
 * which placement an ad unit belongs to, and when a show opportunity was declined by policy.
 *
 * The lifecycle itself is no longer the app's business — `ERainAd` attaches
 * [TrackingAdCallback] to every callback it hands to the SDK.
 *
 * ```
 * AdTracking.registerPlacement(unitId, "splash");   // once, at init
 * ```
 */
object AdTracking {

    /**
     * Binds an ad unit to a placement. Call it once at init, before any load — the ironSource
     * `addImpressionDataListener` pattern: context the SDK cannot know, supplied out-of-band
     * instead of at every call site.
     */
    @JvmStatic
    fun registerPlacement(adUnitId: String?, placement: String?) {
        PlacementRegistry.register(adUnitId, placement)
    }

    /**
     * Emits `ad_request` for load paths that do not go through a wrapped callback.
     */
    @JvmStatic
    fun request(placement: String?, format: AdFormat?, adUnitId: String?) {
        PlacementRegistry.register(adUnitId, placement)
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) {
            Tracker.track(TrackkitEvents.Ad.Request(placement!!, format!!, adUnitId))
        }
    }

    /**
     * A show opportunity policy declined — purchased user, remote flag off, no fill.
     */
    @JvmStatic
    fun skipped(placement: String?, format: AdFormat?, reason: String?) {
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) {
            Tracker.track(TrackkitEvents.Ad.Skipped(placement!!, format!!, reason!!))
        }
    }
}
