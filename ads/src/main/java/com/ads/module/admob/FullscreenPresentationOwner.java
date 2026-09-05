package com.ads.module.admob;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import io.trackkit.AdFormat;

/**
 * Process-wide exclusion for SDK vendor fullscreen presentations, independent of dialogs and
 * caches. A reservation expires after 90 seconds of elapsed time. Once vendor invocation starts,
 * only that invocation's terminal can release it; elapsed time does not prove an ad has closed.
 * Adapters own resource cleanup, eligibility, callback ordering and lifecycle recovery.
 */
final class FullscreenPresentationOwner {
    private static final long RESERVATION_TIMEOUT_MS = 90_000;
    private static final FullscreenPresentationOwner INSTANCE = new FullscreenPresentationOwner();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Lease current;
    private boolean legacyInterstitialSuppressed;
    private Runnable legacyTimeout;

    private FullscreenPresentationOwner() {}

    static FullscreenPresentationOwner getInstance() { return INSTANCE; }

    /** Atomically reserves the screen. Rejected callers own no timer or cleanup callback. */
    @Nullable
    synchronized Lease tryAcquire(AdFormat format, Runnable onReservationExpired) {
        if (format != AdFormat.APP_OPEN && format != AdFormat.INTERSTITIAL
                && format != AdFormat.REWARDED && format != AdFormat.REWARDED_INTERSTITIAL) {
            throw new IllegalArgumentException("Not a vendor fullscreen format: " + format);
        }
        if (current != null || legacyInterstitialSuppressed) return null;
        Lease lease = new Lease(format, onReservationExpired);
        current = lease;
        handler.postDelayed(lease.timeout, RESERVATION_TIMEOUT_MS);
        return lease;
    }

    synchronized boolean isBusy() { return current != null || legacyInterstitialSuppressed; }

    synchronized boolean isAppOpenBusy() {
        return current != null && current.format == AdFormat.APP_OPEN;
    }

    synchronized boolean isInterstitialBusy() {
        return legacyInterstitialSuppressed || (current != null && current.format != AdFormat.APP_OPEN);
    }

    /** Compatibility suppression has no authority over an SDK lease, including on timeout. */
    synchronized void setLegacyInterstitialSuppressed(boolean suppressed) {
        if (legacyTimeout != null) handler.removeCallbacks(legacyTimeout);
        legacyTimeout = null;
        legacyInterstitialSuppressed = suppressed;
        if (!suppressed) return;
        legacyTimeout = new Runnable() {
            @Override public void run() {
                synchronized (FullscreenPresentationOwner.this) {
                    if (legacyTimeout != this) return;
                    legacyInterstitialSuppressed = false;
                    legacyTimeout = null;
                }
            }
        };
        handler.postDelayed(legacyTimeout, RESERVATION_TIMEOUT_MS);
    }

    private enum State { RESERVED, DISPATCHED, PRESENTED, ENDED }

    /** Captured identity; every legal transition succeeds at most once. */
    final class Lease {
        private final AdFormat format;
        private final long deadline = SystemClock.elapsedRealtime() + RESERVATION_TIMEOUT_MS;
        private final Runnable timeout = this::expireReservation;
        private Runnable onExpired;
        private State state = State.RESERVED;

        private Lease(AdFormat format, Runnable onExpired) {
            this.format = format;
            this.onExpired = onExpired;
        }

        boolean start() {
            synchronized (FullscreenPresentationOwner.this) {
                if (current != this || state != State.RESERVED) return false;
                if (SystemClock.elapsedRealtime() < deadline) {
                    state = State.DISPATCHED;
                    cancelReservation();
                    return true;
                }
            }
            // A Handler uses uptime. Deep sleep can exhaust the elapsed deadline before its
            // runnable executes; cleanup remains outside the owner lock in either case.
            expireReservation();
            return false;
        }

        boolean presented() {
            synchronized (FullscreenPresentationOwner.this) {
                if (current != this || state != State.DISPATCHED) return false;
                state = State.PRESENTED;
                return true;
            }
        }

        boolean finish() {
            synchronized (FullscreenPresentationOwner.this) {
                if (current != this || state == State.ENDED) return false;
                current = null;
                state = State.ENDED;
                cancelReservation();
                return true;
            }
        }

        boolean isCurrent() {
            synchronized (FullscreenPresentationOwner.this) {
                return current == this && state != State.ENDED;
            }
        }

        private void expireReservation() {
            Runnable cleanup;
            synchronized (FullscreenPresentationOwner.this) {
                if (current != this || state != State.RESERVED
                        || SystemClock.elapsedRealtime() < deadline) return;
                current = null;
                state = State.ENDED;
                cleanup = onExpired;
                cancelReservation();
            }
            if (cleanup != null) cleanup.run();
        }

        private void cancelReservation() {
            handler.removeCallbacks(timeout);
            onExpired = null;
        }
    }
}
