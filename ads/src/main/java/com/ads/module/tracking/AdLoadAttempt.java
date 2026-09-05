package com.ads.module.tracking;

import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;

/**
 * Reports one logical ad load, independently of cache consumers and UI waiting budgets.
 * A context may be reused across loads; each attempt has a fresh non-identifying correlation ID.
 * Start at the actual vendor call, then settle each dispatched tier and one final outcome.
 */
public final class AdLoadAttempt {
    private final AdLoadContext context;
    private final String id = UUID.randomUUID().toString();
    private final Set<Integer> reportedTiers = new HashSet<>();
    private boolean started;
    private boolean terminal;
    private long startedAt;
    private String lastUnitId;

    public AdLoadAttempt(AdLoadContext context) {
        this.context = context;
    }

    public AdLoadContext getContext() { return context; }
    public String getId() { return id; }

    /** Repeated tier dispatches share the first request event and its original clock. */
    public synchronized void onRequestStarted(String adUnitId) {
        if (terminal) return;
        lastUnitId = adUnitId;
        if (started) return;
        started = true;
        startedAt = SystemClock.elapsedRealtime();
        if (context.isReportTelemetry()) {
            Tracker.track(new TrackkitEvents.Ad.Request(
                    context.getPlacement(), context.getFormat(), adUnitId, id));
        }
    }

    /** Diagnostics use configured one-based tier positions and never increment placement failures. */
    public synchronized void onTierResult(int tierIndex, String adUnitId, String outcome,
                                          Integer errorCode, long latencyMs) {
        if (!started || terminal || !reportedTiers.add(tierIndex)) return;
        if (context.isReportTelemetry()) {
            Tracker.track(new TrackkitEvents.Ad.TierResult(context.getPlacement(), context.getFormat(),
                    adUnitId, id, tierIndex, outcome, errorCode, Math.max(0L, latencyMs)));
        }
    }

    public synchronized void onLoaded(String adUnitId) {
        if (terminal) return;
        terminal = true;
        if (started && context.isReportTelemetry()) {
            Tracker.track(new TrackkitEvents.Ad.Loaded(context.getPlacement(), context.getFormat(),
                    adUnitId, elapsed(), id));
        }
    }

    public synchronized void onFailed(Integer errorCode) {
        if (terminal) return;
        terminal = true;
        if (started && context.isReportTelemetry()) {
            Tracker.track(new TrackkitEvents.Ad.LoadFailed(context.getPlacement(), context.getFormat(),
                    lastUnitId, errorCode, elapsed(), id));
        }
    }

    private long elapsed() { return Math.max(0L, SystemClock.elapsedRealtime() - startedAt); }
}
