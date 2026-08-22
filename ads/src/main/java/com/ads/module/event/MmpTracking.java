package com.ads.module.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The seam other modules use to reach the MMP (Adjust) without importing it.
 *
 * <p>The registry itself now lives in {@code io.trackkit.mmp.MmpTracking}, so modules that ship
 * without {@code :ads} — {@code :billingkit} above all — can still report purchase revenue. This
 * class stays as the published door for modules that already depend on {@code :ads}: it delegates
 * every call to the Trackkit seam and contributes the Adjust relay, which remains the single
 * writer of every {@code Adjust.*} call in the codebase.
 *
 * <p>Usage from a sibling module (which already depends on {@code :ads}):
 * <pre>
 * MmpTracking.trackEvent(config.getAdjustTokenOnboardingComplete());
 * </pre>
 *
 * <p>Send only what an MMP can act on: revenue, and milestones you intend to optimise campaigns
 * against. Funnel and screen telemetry belong in Firebase — Adjust bills by volume and has no
 * funnel reporting.
 */
public final class MmpTracking {

    /**
     * A destination for MMP-worthy signals. The Adjust relay is registered by default; add your
     * own to fan out to a second MMP without touching any call site.
     *
     * <p>New code should implement {@code io.trackkit.mmp.MmpTracking.Relay} instead — it also
     * carries purchase revenue, which this frozen interface never received.
     */
    public interface Relay {

        /**
         * A conversion milestone, keyed by the vendor's own token.
         */
        void onEvent(@NonNull String token, @Nullable String callbackId);

        /**
         * Revenue attached to a milestone, in real currency units.
         */
        void onRevenue(@NonNull String token, double value, @Nullable String currency);

        /**
         * The UMP outcome.
         */
        void onConsent(boolean analyticsGranted, boolean adsGranted);
    }

    private static final AtomicBoolean ADJUST_INSTALLED = new AtomicBoolean(false);

    /**
     * Legacy relay -> Trackkit wrapper, so removeRelay can find the wrapper addRelay registered.
     */
    private static final Map<Relay, io.trackkit.mmp.MmpTracking.Relay> WRAPPED =
            new ConcurrentHashMap<>();

    static {
        ensureInstalled();
    }

    private MmpTracking() {
    }

    /**
     * Arms the Adjust relay on the Trackkit seam. Idempotent, and once only: after
     * {@link #clearRelays()} the relay stays gone, exactly as it always has.
     */
    public static void ensureInstalled() {
        if (ADJUST_INSTALLED.compareAndSet(false, true)) {
            io.trackkit.mmp.MmpTracking.addRelay(new AdjustRelay());
        }
    }

    public static void addRelay(@NonNull Relay relay) {
        io.trackkit.mmp.MmpTracking.addRelay(
                WRAPPED.computeIfAbsent(relay, LegacyRelayAdapter::new));
    }

    public static void removeRelay(@NonNull Relay relay) {
        io.trackkit.mmp.MmpTracking.Relay wrapper = WRAPPED.remove(relay);
        if (wrapper != null) {
            io.trackkit.mmp.MmpTracking.removeRelay(wrapper);
        }
    }

    /**
     * Drops every relay, the built-in Adjust one included — for partners who ship a different MMP.
     */
    public static void clearRelays() {
        WRAPPED.clear();
        io.trackkit.mmp.MmpTracking.clearRelays();
    }

    public static void trackEvent(@Nullable String token) {
        io.trackkit.mmp.MmpTracking.trackEvent(token);
    }

    public static void trackEvent(@Nullable String token, @Nullable String callbackId) {
        io.trackkit.mmp.MmpTracking.trackEvent(token, callbackId);
    }

    public static void trackRevenue(@Nullable String token, double value, @Nullable String currency) {
        io.trackkit.mmp.MmpTracking.trackRevenue(token, value, currency);
    }

    public static void setConsent(boolean analyticsGranted, boolean adsGranted) {
        io.trackkit.mmp.MmpTracking.setConsent(analyticsGranted, adsGranted);
    }

    /**
     * The Adjust integration that lives in this module, spoken to through the Trackkit seam.
     */
    static final class AdjustRelay implements io.trackkit.mmp.MmpTracking.Relay {

        @Override
        public void onEvent(@NonNull String token, @Nullable String callbackId) {
            if (callbackId == null) {
                ERainAdjust.onTrackEvent(token);
            } else {
                ERainAdjust.onTrackEvent(token, callbackId);
            }
        }

        @Override
        public void onRevenue(@NonNull String token, double value, @Nullable String currency) {
            ERainAdjust.onTrackRevenue(token, value, currency);
        }

        @Override
        public void onPurchaseRevenue(double revenueMicros, @Nullable String currency) {
            ERainAdjust.onTrackRevenuePurchase(revenueMicros, currency);
        }

        @Override
        public void onConsent(boolean analyticsGranted, boolean adsGranted) {
            ERainAdjust.setConsent(analyticsGranted, adsGranted);
        }
    }

    /**
     * Presents a legacy {@link Relay} to the Trackkit seam. Purchase revenue is deliberately not
     * forwarded: the frozen interface never carried it, so legacy relays never saw it.
     */
    private static final class LegacyRelayAdapter implements io.trackkit.mmp.MmpTracking.Relay {

        private final Relay delegate;

        LegacyRelayAdapter(Relay delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onEvent(@NonNull String token, @Nullable String callbackId) {
            delegate.onEvent(token, callbackId);
        }

        @Override
        public void onRevenue(@NonNull String token, double value, @Nullable String currency) {
            delegate.onRevenue(token, value, currency);
        }

        @Override
        public void onConsent(boolean analyticsGranted, boolean adsGranted) {
            delegate.onConsent(analyticsGranted, adsGranted);
        }
    }
}
