package com.ads.module.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The seam other modules use to reach the MMP (Adjust) without importing it.
 *
 * The registry itself now lives in `io.trackkit.mmp.MmpTracking`, so modules that ship
 * without `:ads` — `:billingkit` above all — can still report purchase revenue. This
 * class stays as the published door for modules that already depend on `:ads`: it delegates
 * every call to the Trackkit seam and contributes the Adjust relay, which remains the single
 * writer of every `Adjust.*` call in the codebase.
 *
 * Usage from a sibling module (which already depends on `:ads`):
 * ```
 * MmpTracking.trackEvent(config.getAdjustTokenOnboardingComplete());
 * ```
 *
 * Send only what an MMP can act on: revenue, and milestones you intend to optimise campaigns
 * against. Funnel and screen telemetry belong in Firebase — Adjust bills by volume and has no
 * funnel reporting.
 */
object MmpTracking {

    /**
     * A destination for MMP-worthy signals. The Adjust relay is registered by default; add your
     * own to fan out to a second MMP without touching any call site.
     *
     * New code should implement `io.trackkit.mmp.MmpTracking.Relay` instead — it also
     * carries purchase revenue, which this frozen interface never received.
     */
    interface Relay {

        /**
         * A conversion milestone, keyed by the vendor's own token.
         */
        fun onEvent(token: String, callbackId: String?)

        /**
         * Revenue attached to a milestone, in real currency units.
         */
        fun onRevenue(token: String, value: Double, currency: String?)

        /**
         * The UMP outcome.
         */
        fun onConsent(analyticsGranted: Boolean, adsGranted: Boolean)
    }

    private val ADJUST_INSTALLED = AtomicBoolean(false)

    /**
     * Legacy relay -> Trackkit wrapper, so removeRelay can find the wrapper addRelay registered.
     */
    private val WRAPPED: ConcurrentHashMap<Relay, io.trackkit.mmp.MmpTracking.Relay> =
        ConcurrentHashMap()

    init {
        ensureInstalled()
    }

    /**
     * Arms the Adjust relay on the Trackkit seam. Idempotent, and once only: after
     * [clearRelays] the relay stays gone, exactly as it always has.
     */
    @JvmStatic
    fun ensureInstalled() {
        if (ADJUST_INSTALLED.compareAndSet(false, true)) {
            io.trackkit.mmp.MmpTracking.addRelay(AdjustRelay())
        }
    }

    @JvmStatic
    fun addRelay(relay: Relay) {
        io.trackkit.mmp.MmpTracking.addRelay(
            WRAPPED.computeIfAbsent(relay) { LegacyRelayAdapter(it) })
    }

    @JvmStatic
    fun removeRelay(relay: Relay) {
        val wrapper: io.trackkit.mmp.MmpTracking.Relay? = WRAPPED.remove(relay)
        if (wrapper != null) {
            io.trackkit.mmp.MmpTracking.removeRelay(wrapper)
        }
    }

    /**
     * Drops every relay, the built-in Adjust one included — for partners who ship a different MMP.
     */
    @JvmStatic
    fun clearRelays() {
        WRAPPED.clear()
        io.trackkit.mmp.MmpTracking.clearRelays()
    }

    @JvmStatic
    fun trackEvent(token: String?) {
        io.trackkit.mmp.MmpTracking.trackEvent(token)
    }

    @JvmStatic
    fun trackEvent(token: String?, callbackId: String?) {
        io.trackkit.mmp.MmpTracking.trackEvent(token, callbackId)
    }

    @JvmStatic
    fun trackRevenue(token: String?, value: Double, currency: String?) {
        io.trackkit.mmp.MmpTracking.trackRevenue(token, value, currency)
    }

    @JvmStatic
    fun setConsent(analyticsGranted: Boolean, adsGranted: Boolean) {
        io.trackkit.mmp.MmpTracking.setConsent(analyticsGranted, adsGranted)
    }

    /**
     * The Adjust integration that lives in this module, spoken to through the Trackkit seam.
     */
    private class AdjustRelay : io.trackkit.mmp.MmpTracking.Relay {

        override fun onEvent(token: String, callbackId: String?) {
            if (callbackId == null) {
                ERainAdjust.onTrackEvent(token)
            } else {
                ERainAdjust.onTrackEvent(token, callbackId)
            }
        }

        override fun onRevenue(token: String, value: Double, currency: String?) {
            ERainAdjust.onTrackRevenue(token, value, currency)
        }

        override fun onPurchaseRevenue(revenueMicros: Double, currency: String?) {
            ERainAdjust.onTrackRevenuePurchase(revenueMicros, currency)
        }

        override fun onConsent(analyticsGranted: Boolean, adsGranted: Boolean) {
            ERainAdjust.setConsent(analyticsGranted, adsGranted)
        }
    }

    /**
     * Presents a legacy [Relay] to the Trackkit seam. Purchase revenue is deliberately not
     * forwarded: the frozen interface never carried it, so legacy relays never saw it.
     */
    private class LegacyRelayAdapter(private val delegate: Relay) :
        io.trackkit.mmp.MmpTracking.Relay {

        override fun onEvent(token: String, callbackId: String?) {
            delegate.onEvent(token, callbackId)
        }

        override fun onRevenue(token: String, value: Double, currency: String?) {
            delegate.onRevenue(token, value, currency)
        }

        override fun onConsent(analyticsGranted: Boolean, adsGranted: Boolean) {
            delegate.onConsent(analyticsGranted, adsGranted)
        }
    }
}
