package com.ads.module.event

import android.content.Context
import android.util.Log
import com.ads.module.engine.InterstitialEngine
import com.ads.module.funtion.AdType
import com.ads.module.tracking.AdFormatRegistry
import com.google.android.gms.ads.AdValue
import io.trackkit.AdFormat
import io.trackkit.AdImpression
import io.trackkit.PlacementRegistry
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents

/**
 * The bridge from AdMob's vendor callbacks into `Tracker`.
 *
 * Impressions and clicks enter here and leave through Trackkit; the vendor fan-out lives in
 * the sinks. Adjust is an MMP, not a sink, so revenue reaches it through the MmpTracking seam,
 * whose Adjust relay is the single writer of every `Adjust.*` call.
 */
object ERainLogEventManager {

    private const val TAG = "ERainLogEventManager"

    /**
     * Only used when the SDK reports no currency code at all.
     */
    private const val DEFAULT_CURRENCY = "USD"

    /**
     * Mirrors `AppPurchase.TYPE_IAP.SUBSCRIPTION`; the billing engine lives in
     * `:billingkit` now, so its constants cannot be referenced from here.
     */
    private const val TYPE_IAP_SUBSCRIPTION = 2

    /**
     * AdMob paid impression. Currency and precision come from the SDK — never hardcoded — and the
     * placement is resolved from the ad unit id registered by `TrackingAdCallback`.
     */
    @JvmStatic
    fun logPaidAdImpression(context: Context?, adValue: AdValue?, adUnitId: String?,
                            mediationAdapterClassName: String?, adType: AdType?) {
        if (adValue == null) {
            return
        }
        val unitId = orEmpty(adUnitId)
        val placement = PlacementRegistry.placementOf(unitId)
        Tracker.adRevenue(AdImpression(
            placement,
            toAdFormat(adType),
            unitId,
            AdImpression.PLATFORM_ADMOB,
            mediationAdapterClassName,
            adValue.valueMicros,
            orDefault(adValue.currencyCode, DEFAULT_CURRENCY),
            adValue.precisionType))
        // Trackkit fans out to Firebase / Meta; the MMP is Adjust's own trackAdRevenue API,
        // keyed by a source string rather than an event token, so it needs no configuration.
        ERainAdjust.pushTrackEventAdmob(adValue, unitId, mediationAdapterClassName, placement)
    }

    /**
     * Sole emitter of `ad_click`. It sits on the vendor callback, so it fires once per real
     * click on every path — including the AppOpenManager resume/splash flows, which have no
     * `AdCallback` to decorate. `TrackingAdCallback` therefore only forwards clicks.
     */
    @JvmStatic
    fun logClickAdsEvent(context: Context?, adUnitId: String?) {
        Log.d(TAG, String.format("User click ad for ad unit %s.", adUnitId))
        val unitId = orEmpty(adUnitId)
        if (com.ads.module.config.settings.AdBehavior.bool("diagnostics.ads_telemetry_enabled")) Tracker.track(TrackkitEvents.Ad.Click(
            PlacementRegistry.placementOf(unitId), AdFormatRegistry.formatOf(unitId), unitId))
        // Being the sole click emitter makes this the only place the daily cap can count from
        // without a new ad format silently escaping it. No-op while the cap is off.
        InterstitialEngine.recordAdClick(context, unitId)
    }

    /**
     * @param revenueMicros price in micros — that is what Billing's
     *                      `getPriceAmountMicros()` returns. Converted to currency units
     *                      here so the taxonomy stays in real money.
     */
    @Deprecated("the billing engine in `:billingkit` reports its own purchases through " +
            "`Tracker` and `io.trackkit.mmp.MmpTracking`; call those directly instead.")
    @JvmStatic
    fun onTrackRevenuePurchase(revenueMicros: Double, currency: String?, idPurchase: String?, typeIAP: Int) {
        Tracker.track(TrackkitEvents.Iap.Success(
            orEmpty(idPurchase),
            revenueMicros / 1_000_000.0,
            orDefault(currency, DEFAULT_CURRENCY),
            if (typeIAP == TYPE_IAP_SUBSCRIPTION) "subscription" else "purchase"))
        // The seam converts nothing: Adjust takes micros and divides internally.
        MmpTracking.ensureInstalled()
        io.trackkit.mmp.MmpTracking.trackPurchaseRevenue(revenueMicros, currency)
    }

    /**
     * @param revenueMicros price in micros
     */
    @Deprecated("a float carries ~7 significant digits, so a VND/IDR/KRW price in micros loses " +
            "precision. Use onTrackRevenuePurchase(double, String, String, int).")
    @JvmStatic
    fun onTrackRevenuePurchase(revenueMicros: Float, currency: String?, idPurchase: String?, typeIAP: Int) {
        onTrackRevenuePurchase(revenueMicros.toDouble(), currency, idPurchase, typeIAP)
    }

    /**
     * A purchase flow that ended in a Billing error, so paywall views can be reconciled against
     * something other than successes alone.
     *
     * @param responseCode Play Billing's `BillingResponseCode`
     */
    @Deprecated("the billing engine in `:billingkit` reports its own failures; track " +
            "`TrackkitEvents.Iap.Fail` directly instead.")
    @JvmStatic
    fun onTrackPurchaseFail(productId: String?, responseCode: Int) {
        Tracker.track(TrackkitEvents.Iap.Fail(productId, responseCode))
    }

    /**
     * Optional token-keyed impression event, on top of `Adjust.trackAdRevenue`. Networks that
     * cannot consume Adjust's ad-revenue API (TikTok, Meta) read this token instead.
     *
     * Set the token on `ERainAdConfig.adjustConfig.eventAdImpression` — the single door.
     * Leaving it blank is the normal case and skips the event silently: this fires on every
     * impression, so a warning would be per-impression log spam. It is never sent as
     * `AdjustEvent("")`, which Adjust drops server-side with no client-side signal.
     */
    @JvmStatic
    fun logPaidAdjustWithToken(adValue: AdValue?, adUnitId: String?) {
        if (adValue == null) {
            return
        }
        // No isEnabled() pre-gate: MmpTracking exists so a partner can swap Adjust out, and the
        // Adjust relay re-checks the switch itself, so nothing leaks when it is off.
        MmpTracking.trackRevenue(ERainAdjust.adImpressionToken(),
            adValue.valueMicros / 1_000_000.0, adValue.currencyCode)
    }

    /**
     * Maps the wrapper's [AdType] onto the Trackkit taxonomy.
     */
    @JvmStatic
    fun toAdFormat(adType: AdType?): AdFormat {
        if (adType == null) {
            return AdFormat.UNKNOWN
        }
        return when (adType) {
            AdType.BANNER -> AdFormat.BANNER
            AdType.INTERSTITIAL -> AdFormat.INTERSTITIAL
            AdType.NATIVE -> AdFormat.NATIVE
            AdType.REWARDED -> AdFormat.REWARDED
            AdType.APP_OPEN -> AdFormat.APP_OPEN
            else -> AdFormat.UNKNOWN
        }
    }

    private fun orEmpty(value: String?): String {
        return if (value == null) "" else value
    }

    private fun orDefault(value: String?, fallback: String): String {
        return if (value == null || value.isEmpty()) fallback else value
    }
}
