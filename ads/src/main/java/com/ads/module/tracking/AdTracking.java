package com.ads.module.tracking;

import androidx.annotation.Nullable;

import com.ads.module.funtion.AdCallback;

import io.trackkit.AdFormat;
import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;
import io.trackkit.PlacementRegistry;

/**
 * What the host app still needs to say about ads that {@code ERainAd} cannot know by itself:
 * which placement an ad unit belongs to, and when a show opportunity was declined by policy.
 *
 * <p>The lifecycle itself is no longer the app's business — {@code ERainAd} attaches
 * {@link TrackingAdCallback} to every callback it hands to the SDK.
 *
 * <pre>
 * AdTracking.registerPlacement(unitId, "splash");   // once, at init
 * </pre>
 */
public final class AdTracking {

    private AdTracking() {
    }

    /**
     * Binds an ad unit to a placement. Call it once at init, before any load — the ironSource
     * {@code addImpressionDataListener} pattern: context the SDK cannot know, supplied out-of-band
     * instead of at every call site.
     */
    public static void registerPlacement(String adUnitId, String placement) {
        PlacementRegistry.register(adUnitId, placement);
    }

    /**
     * Emits `ad_request` for load paths that do not go through a wrapped callback.
     */
    public static void request(String placement, AdFormat format, String adUnitId) {
        PlacementRegistry.register(adUnitId, placement);
        Tracker.track(new TrackkitEvents.Ad.Request(placement, format, adUnitId));
    }

    /**
     * A show opportunity policy declined — purchased user, remote flag off, no fill.
     */
    public static void skipped(String placement, AdFormat format, String reason) {
        Tracker.track(new TrackkitEvents.Ad.Skipped(placement, format, reason));
    }
}
