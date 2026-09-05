package com.ads.module.tracking;

import io.trackkit.AdFormat;
import io.trackkit.PlacementRegistry;

/**
 * Immutable reporting context for a logical load. It is independent of the native cache key and
 * survives queued batches and another placement registering the same vendor ad unit.
 *
 * <p>The two-argument constructor enables reporting. With {@code reportTelemetry=false}, the
 * owner suppresses request, tier-result and load-terminal events only; presentation reporting
 * and callbacks keep their existing behavior. A null/blank placement becomes {@code "unknown"};
 * a null format becomes {@code AdFormat.UNKNOWN}. Cache keys never replace this placement.
 */
public final class AdLoadContext {
    private final String placement;
    private final AdFormat format;
    private final boolean reportTelemetry;

    public AdLoadContext(String placement, AdFormat format) {
        this(placement, format, true);
    }

    public AdLoadContext(String placement, AdFormat format, boolean reportTelemetry) {
        this.placement = placement == null || placement.trim().isEmpty() ? "unknown" : placement;
        this.format = format == null ? AdFormat.UNKNOWN : format;
        this.reportTelemetry = reportTelemetry;
    }

    /** Captures the current legacy registry mapping once, before a load is queued or dispatched. */
    public static AdLoadContext forAdUnit(String adUnitId, AdFormat format) {
        return new AdLoadContext(PlacementRegistry.placementOf(adUnitId), format);
    }

    public String getPlacement() { return placement; }
    public AdFormat getFormat() { return format; }
    public boolean isReportTelemetry() { return reportTelemetry; }
}
