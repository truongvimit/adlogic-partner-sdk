package io.trackkit.mmp

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The vendor-free seam for MMP-worthy signals (conversion milestones, revenue, consent).
 *
 * It lives in the port so a module with no `:ads` dependency — `:billingkit` above all — can still
 * report purchase revenue to whichever MMP the app ships. `:ads` registers the Adjust relay;
 * a second MMP is one more [Relay] and zero call-site changes. With no relay registered every
 * call is a no-op, which is the correct behaviour for an app that ships no MMP at all.
 *
 * Send only what an MMP can act on: revenue, and milestones campaigns are optimised against.
 * Funnel and screen telemetry belong in [io.trackkit.Tracker] sinks.
 */
object MmpTracking {

    /**
     * A destination for MMP-worthy signals. Every method defaults to a no-op so a relay
     * implements only the signals its vendor consumes.
     */
    interface Relay {

        /** A conversion milestone, keyed by the vendor's own token. */
        fun onEvent(token: String, callbackId: String?) {}

        /** Revenue attached to a milestone, in real currency units. */
        fun onRevenue(token: String, value: Double, currency: String?) {}

        /**
         * A confirmed in-app purchase. Token-less on purpose: the vendor config that knows the
         * purchase event token lives with the relay, not with the billing engine reporting it.
         *
         * @param revenueMicros price in micros, exactly as Play Billing reports it
         */
        fun onPurchaseRevenue(revenueMicros: Double, currency: String?) {}

        /** The UMP outcome. */
        fun onConsent(analyticsGranted: Boolean, adsGranted: Boolean) {}
    }

    private val relays = CopyOnWriteArrayList<Relay>()

    @JvmStatic
    fun addRelay(relay: Relay) {
        relays.addIfAbsent(relay)
    }

    @JvmStatic
    fun removeRelay(relay: Relay) {
        relays.remove(relay)
    }

    /** Drops every registered relay, the built-in Adjust one included. */
    @JvmStatic
    fun clearRelays() {
        relays.clear()
    }

    @JvmStatic
    @JvmOverloads
    fun trackEvent(token: String?, callbackId: String? = null) {
        if (token.isNullOrEmpty()) return
        for (relay in relays) relay.onEvent(token, callbackId)
    }

    @JvmStatic
    fun trackRevenue(token: String?, value: Double, currency: String?) {
        if (token.isNullOrEmpty()) return
        for (relay in relays) relay.onRevenue(token, value, currency)
    }

    /**
     * Reports a confirmed purchase to every relay.
     *
     * @param revenueMicros price in micros, as Play Billing's `getPriceAmountMicros()` returns it
     */
    @JvmStatic
    fun trackPurchaseRevenue(revenueMicros: Double, currency: String?) {
        for (relay in relays) relay.onPurchaseRevenue(revenueMicros, currency)
    }

    @JvmStatic
    fun setConsent(analyticsGranted: Boolean, adsGranted: Boolean) {
        for (relay in relays) relay.onConsent(analyticsGranted, adsGranted)
    }
}
