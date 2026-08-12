package com.ads.module.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The seam other modules use to reach the MMP (Adjust) without importing it.
 *
 * <p>Adjust lives in {@code :ads} because every signal it consumes — ad revenue, purchase revenue,
 * install attribution, session lifecycle — originates from code this module already owns. That
 * placement is deliberate, not accidental. But it left modules like {@code :onboardkitorigin} with
 * no way to report a conversion milestone, because the only route was a static method behind the
 * ads SDK's config object.
 *
 * <p>This class is that route. It is an interface plus a registry, not a Gradle module: partners do
 * not add a dependency, do not edit their {@code Application}, and cannot forget to wire it.
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
     * A destination for MMP-worthy signals. {@link AdjustRelay} is registered by default; add your
     * own to fan out to a second MMP without touching any call site.
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

    private static final CopyOnWriteArrayList<Relay> RELAYS = new CopyOnWriteArrayList<>();

    static {
        RELAYS.add(new AdjustRelay());
    }

    private MmpTracking() {
    }

    public static void addRelay(@NonNull Relay relay) {
        RELAYS.addIfAbsent(relay);
    }

    public static void removeRelay(@NonNull Relay relay) {
        RELAYS.remove(relay);
    }

    /**
     * Drops the built-in Adjust relay — for partners who ship a different MMP.
     */
    public static void clearRelays() {
        RELAYS.clear();
    }

    public static void trackEvent(@Nullable String token) {
        trackEvent(token, null);
    }

    public static void trackEvent(@Nullable String token, @Nullable String callbackId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        for (Relay relay : RELAYS) {
            relay.onEvent(token, callbackId);
        }
    }

    public static void trackRevenue(@Nullable String token, double value, @Nullable String currency) {
        if (token == null || token.isEmpty()) {
            return;
        }
        for (Relay relay : RELAYS) {
            relay.onRevenue(token, value, currency);
        }
    }

    public static void setConsent(boolean analyticsGranted, boolean adsGranted) {
        for (Relay relay : RELAYS) {
            relay.onConsent(analyticsGranted, adsGranted);
        }
    }

    /**
     * Default relay: the Adjust integration that already lives in this module.
     */
    static final class AdjustRelay implements Relay {

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
        public void onConsent(boolean analyticsGranted, boolean adsGranted) {
            ERainAdjust.setConsent(analyticsGranted, adsGranted);
        }
    }
}
