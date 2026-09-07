package com.ads.module.admob;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Owns holds and one-shot return snapshots independently of the legacy click flag and policy. */
final class ResumeSuppressionRegistry {
    static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;
    private final LongSupplier clock;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long nextId;
    private long generation;

    ResumeSuppressionRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized ResumeSuppression acquire(String owner, String reason, long timeoutMs, boolean nextReturn) {
        if (owner == null || owner.trim().isEmpty() || reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Resume suppression owner and reason must not be blank");
        }
        if (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("Resume suppression timeout must be between 1 and 600000 ms");
        }
        pruneExpired();
        long id = ++nextId;
        entries.put(id, new Entry(owner, reason, clock.getAsLong() + timeoutMs, nextReturn));
        return () -> release(id);
    }

    private synchronized void release(long id) {
        entries.remove(id);
    }

    @Nullable synchronized String reason() {
        pruneExpired();
        for (Entry entry : entries.values()) return entry.reason;
        return null;
    }

    /** Returns the new capture generation, or zero when no pending one-shot was consumed. */
    synchronized long captureReturn() {
        pruneExpired();
        long captured = 0;
        for (Entry entry : entries.values()) {
            if (entry.nextReturn && entry.capturedAt == 0) {
                if (captured == 0) captured = ++generation;
                entry.capturedAt = captured;
            }
        }
        return captured;
    }

    synchronized void clearCaptured(long captured) {
        entries.values().removeIf(entry -> entry.capturedAt == captured && captured != 0);
    }

    synchronized void clearCaptured() {
        entries.values().removeIf(entry -> entry.capturedAt != 0);
    }

    private void pruneExpired() {
        long now = clock.getAsLong();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().deadline <= now) iterator.remove();
        }
    }

    private static final class Entry {
        final String owner;
        final String reason;
        final long deadline;
        final boolean nextReturn;
        long capturedAt;

        Entry(String owner, String reason, long deadline, boolean nextReturn) {
            this.owner = owner;
            this.reason = reason;
            this.deadline = deadline;
            this.nextReturn = nextReturn;
        }
    }
}
