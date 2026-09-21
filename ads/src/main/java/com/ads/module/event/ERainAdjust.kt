package com.ads.module.event

import android.text.TextUtils
import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.AdjustThirdPartySharing
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdjustConfig
import com.google.android.gms.ads.AdValue

/**
 * The single writer to the Adjust SDK.
 *
 * Adjust is an MMP, not a product-analytics tool: it answers "which campaign produced this
 * install" and "how much money did that user make". Everything it receives is therefore revenue or
 * a conversion milestone — funnel and screen telemetry belong in Firebase, not here.
 *
 * Adjust is keyed by six-character event *tokens* minted on its dashboard, not by event
 * names. An `AdjustEvent("")` is not a no-op: Adjust accepts the object, fails server-side and
 * the revenue disappears with no client-side signal. Every method below therefore refuses to send
 * when the token is missing, and says so in the log.
 */
object ERainAdjust {

    private const val TAG = "ERainAdjust"

    /**
     * True once `Adjust.initSdk` has been called with a config that passed validation.
     */
    @Volatile
    private var initialized = false

    /**
     * Called by `ERainAd` after a successful `Adjust.initSdk`.
     */
    @JvmStatic
    fun markInitialized() {
        initialized = true
    }

    /**
     * True when an event sent right now would actually reach Adjust: the SDK is up, and the partner
     * has not turned the integration off.
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        val config = config()
        return initialized && config != null && config.isEnableAdjust
    }

    // -----------------------------------------------------------------------
    // Ad revenue
    // -----------------------------------------------------------------------

    /**
     * AdMob paid impression, with the breakdown Adjust can slice ROAS by.
     *
     * The wrapper knows the unit, the mediation adapter that filled it and the screen the unit is
     * registered to, so all three are forwarded. Apero sent these for AppLovin only and left the
     * AdMob payload as a bare revenue figure, which is why its Adjust dashboard could not tell a
     * splash interstitial from a home banner.
     */
    @JvmStatic
    fun pushTrackEventAdmob(adValue: AdValue?, adUnitId: String?,
                            network: String?, placement: String?) {
        if (!isEnabled() || adValue == null) {
            return
        }
        val adRevenue = AdjustAdRevenue(AdjustConfig.AD_REVENUE_ADMOB)
        adRevenue.setRevenue(adValue.valueMicros / 1_000_000.0, adValue.currencyCode)
        if (!TextUtils.isEmpty(network)) {
            adRevenue.setAdRevenueNetwork(network)
        }
        if (!TextUtils.isEmpty(adUnitId)) {
            adRevenue.setAdRevenueUnit(adUnitId)
        }
        if (!TextUtils.isEmpty(placement)) {
            adRevenue.setAdRevenuePlacement(placement)
        }
        Adjust.trackAdRevenue(adRevenue)
    }

    /**
     * Configured impression token, used when a call site does not pass one of its own.
     */
    @JvmStatic
    @JvmName("adImpressionToken")
    internal fun adImpressionToken(): String? {
        val config = config()
        return if (config == null) null else config.eventAdImpression
    }

    // -----------------------------------------------------------------------
    // Purchase revenue
    // -----------------------------------------------------------------------

    /**
     * In-app purchase.
     *
     * @param revenueMicros price in micros — that is what Play Billing's
     *                      `getPriceAmountMicros()` returns and what
     *                      `AppPurchase.handlePurchase` forwards. Converted here so the
     *                      conversion sits next to the thing that documents the unit, instead of
     *                      being buried in the generic [onTrackRevenue] helper where a
     *                      caller passing real currency units would silently lose a factor of 1e6.
     */
    @JvmStatic
    fun onTrackRevenuePurchase(revenueMicros: Double, currency: String?) {
        val config = config()
        if (!isEnabled() || config == null) {
            return
        }
        val token = config.eventNamePurchase
        if (TextUtils.isEmpty(token)) {
            warnMissingToken("eventNamePurchase")
            return
        }
        onTrackRevenue(token, revenueMicros / 1_000_000.0, currency)
    }

    // -----------------------------------------------------------------------
    // Generic passthrough — for conversion milestones outside the ad flow
    // -----------------------------------------------------------------------

    /**
     * Fires a bare conversion event. Prefer [MmpTracking] from modules outside `:ads`.
     */
    @JvmStatic
    fun onTrackEvent(token: String?) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return
        }
        Adjust.trackEvent(AdjustEvent(token))
    }

    @JvmStatic
    fun onTrackEvent(token: String?, callbackId: String?) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return
        }
        val event = AdjustEvent(token)
        event.setCallbackId(callbackId)
        Adjust.trackEvent(event)
    }

    /**
     * @param revenue value in real currency units. The previous implementation divided by 1e6 here,
     *                which was only correct because its single caller happened to pass micros —
     *                any partner calling it with a real price under-reported by a million.
     */
    @JvmStatic
    fun onTrackRevenue(token: String?, revenue: Double, currency: String?) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return
        }
        val event = AdjustEvent(token)
        event.setRevenue(revenue, orDefault(currency))
        Adjust.trackEvent(event)
    }

    // -----------------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------------

    /**
     * Relays the UMP outcome. Call it from the app's consent callback — `ERainAd` used to
     * declare third-party sharing unconditionally at init, before the form had even been shown.
     */
    @JvmStatic
    fun setConsent(analyticsGranted: Boolean, adsGranted: Boolean) {
        if (!isEnabled()) {
            return
        }
        Adjust.trackMeasurementConsent(analyticsGranted)
        Adjust.trackThirdPartySharing(AdjustThirdPartySharing(adsGranted))
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private const val DEFAULT_CURRENCY = "USD"

    private fun config(): AdjustConfig? {
        val instance = ERainAd.getInstance()
        if (instance == null || instance.adConfig == null) {
            return null
        }
        return instance.adConfig.adjustConfig
    }

    /**
     * A blank token is a configuration mistake, not a quiet opt-out — say so once per call site.
     */
    private fun rejectBlankToken(token: String?): Boolean {
        if (!TextUtils.isEmpty(token)) {
            return false
        }
        Log.w(TAG, "Adjust event skipped: no event token. " +
                "Mint one on the Adjust dashboard; an empty token is dropped server-side.")
        return true
    }

    private fun orDefault(currency: String?): String? {
        return if (TextUtils.isEmpty(currency)) DEFAULT_CURRENCY else currency
    }

    private fun warnMissingToken(field: String) {
        Log.w(TAG, "AdjustConfig." + field + " is empty — event skipped. " +
                "Mint the token on the Adjust dashboard and set it on ERainAdConfig.adjustConfig; " +
                "sending an empty token loses the revenue silently.")
    }
}
