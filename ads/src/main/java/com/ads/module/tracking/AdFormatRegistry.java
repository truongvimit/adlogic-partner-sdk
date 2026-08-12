package com.ads.module.tracking;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import io.trackkit.AdFormat;

/**
 * Maps an ad unit id back to the format it was requested with.
 *
 * <p>Same out-of-band trick as {@code PlacementRegistry}, for the other half of the context the SDK
 * cannot supply: AdMob's click and paid callbacks know the ad unit and nothing else. Written once at
 * load time by {@link TrackingAdCallback}, read at click time by {@code ERainLogEventManager}
 * (ironSource {@code addImpressionDataListener} does the same for its placement).
 *
 * <p>Lives in {@code :ads}, not in the frozen port — only this module knows about ad units.
 */
public final class AdFormatRegistry {

    /**
     * Bounded like PlacementRegistry: an app has a handful of units, a leak here would be silent.
     */
    private static final int MAX_ENTRIES = 64;

    private static final Map<String, AdFormat> MAP =
            new LinkedHashMap<String, AdFormat>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AdFormat> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private AdFormatRegistry() {
    }

    public static void register(@Nullable String adUnitId, @Nullable AdFormat format) {
        if (adUnitId == null || adUnitId.isEmpty() || format == null || format == AdFormat.UNKNOWN) {
            return;
        }
        synchronized (MAP) {
            MAP.put(adUnitId, format);
        }
    }

    public static AdFormat formatOf(@Nullable String adUnitId) {
        if (adUnitId == null || adUnitId.isEmpty()) {
            return AdFormat.UNKNOWN;
        }
        AdFormat format;
        synchronized (MAP) {
            format = MAP.get(adUnitId);
        }
        return format == null ? AdFormat.UNKNOWN : format;
    }

    public static void clear() {
        synchronized (MAP) {
            MAP.clear();
        }
    }
}
