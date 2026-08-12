package com.ads.module.event;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ads.module.admob.Admob;
import com.ads.module.billing.AppPurchase;
import com.ads.module.funtion.AdType;
import com.ads.module.tracking.AdFormatRegistry;
import com.google.android.gms.ads.AdValue;

import io.trackkit.AdFormat;
import io.trackkit.AdImpression;
import io.trackkit.PlacementRegistry;
import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;

/**
 * The bridge from AdMob's vendor callbacks into {@code Tracker}.
 *
 * <p>Impressions, clicks and purchase revenue enter here and leave through Trackkit; the vendor
 * fan-out lives in the sinks. Adjust is the exception — it is an MMP, not a sink, so the two
 * revenue calls below hand off to {@link ERainAdjust} directly.
 */
public class ERainLogEventManager {

    private static final String TAG = "ERainLogEventManager";

    /**
     * Only used when the SDK reports no currency code at all.
     */
    private static final String DEFAULT_CURRENCY = "USD";

    // -----------------------------------------------------------------------
    // Paid impressions
    // -----------------------------------------------------------------------

    /**
     * AdMob paid impression. Currency and precision come from the SDK — never hardcoded — and the
     * placement is resolved from the ad unit id registered by {@code TrackingAdCallback}.
     */
    public static void logPaidAdImpression(Context context, AdValue adValue, String adUnitId,
                                           String mediationAdapterClassName, AdType adType) {
        if (adValue == null) {
            return;
        }
        String unitId = orEmpty(adUnitId);
        String placement = PlacementRegistry.placementOf(unitId);
        Tracker.adRevenue(new AdImpression(
                placement,
                toAdFormat(adType),
                unitId,
                AdImpression.PLATFORM_ADMOB,
                mediationAdapterClassName,
                adValue.getValueMicros(),
                orDefault(adValue.getCurrencyCode(), DEFAULT_CURRENCY),
                adValue.getPrecisionType()));
        // Trackkit fans out to Firebase / Meta; the MMP is Adjust's own trackAdRevenue API,
        // keyed by a source string rather than an event token, so it needs no configuration.
        ERainAdjust.pushTrackEventAdmob(adValue, unitId, mediationAdapterClassName, placement);
    }

    // -----------------------------------------------------------------------
    // Ad interaction
    // -----------------------------------------------------------------------

    /**
     * Sole emitter of {@code ad_click}. It sits on the vendor callback, so it fires once per real
     * click on every path — including the AppOpenManager resume/splash flows, which have no
     * {@code AdCallback} to decorate. {@code TrackingAdCallback} therefore only forwards clicks.
     */
    public static void logClickAdsEvent(Context context, String adUnitId) {
        Log.d(TAG, String.format("User click ad for ad unit %s.", adUnitId));
        String unitId = orEmpty(adUnitId);
        Tracker.track(new TrackkitEvents.Ad.Click(
                PlacementRegistry.placementOf(unitId), AdFormatRegistry.formatOf(unitId), unitId));
        // Being the sole click emitter makes this the only place the daily cap can count from
        // without a new ad format silently escaping it. No-op while the cap is off.
        Admob.getInstance().recordAdClick(context, unitId);
    }

    // -----------------------------------------------------------------------
    // Purchase revenue
    // -----------------------------------------------------------------------

    /**
     * @param revenueMicros price in micros — that is what Billing's
     *                      {@code getPriceAmountMicros()} returns and what {@code AppPurchase.handlePurchase}
     *                      forwards. Converted to currency units here so the taxonomy stays in real money.
     */
    public static void onTrackRevenuePurchase(float revenueMicros, String currency, String idPurchase, int typeIAP) {
        Tracker.track(new TrackkitEvents.Iap.Success(
                orEmpty(idPurchase),
                revenueMicros / 1_000_000d,
                orDefault(currency, DEFAULT_CURRENCY),
                typeIAP == AppPurchase.TYPE_IAP.SUBSCRIPTION ? "subscription" : "purchase"));
        // Adjust takes micros and converts internally, next to the javadoc that documents the unit.
        ERainAdjust.onTrackRevenuePurchase(revenueMicros, currency);
    }

    // -----------------------------------------------------------------------
    // Adjust impression token
    // -----------------------------------------------------------------------

    /**
     * Optional token-keyed impression event, on top of {@code Adjust.trackAdRevenue}. Networks that
     * cannot consume Adjust's ad-revenue API (TikTok, Meta) read this token instead.
     *
     * <p>Set the token on {@code ERainAdConfig.adjustConfig.eventAdImpression} — the single door.
     * Leaving it blank is the normal case and skips the event silently: this fires on every
     * impression, so a warning would be per-impression log spam. It is never sent as
     * {@code AdjustEvent("")}, which Adjust drops server-side with no client-side signal.
     */
    public static void logPaidAdjustWithToken(AdValue adValue, String adUnitId) {
        if (adValue == null) {
            return;
        }
        // No ERainAdjust.isEnabled() pre-gate here: MmpTracking exists so a partner can swap
        // Adjust for another MMP, and gating the seam on Adjust's own switch silenced every other
        // relay. The Adjust relay re-checks isEnabled() itself, so nothing leaks when it is off.
        MmpTracking.trackRevenue(ERainAdjust.adImpressionToken(),
                adValue.getValueMicros() / 1_000_000d, adValue.getCurrencyCode());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Maps the wrapper's {@link AdType} onto the Trackkit taxonomy.
     */
    public static AdFormat toAdFormat(@Nullable AdType adType) {
        if (adType == null) {
            return AdFormat.UNKNOWN;
        }
        switch (adType) {
            case BANNER:
                return AdFormat.BANNER;
            case INTERSTITIAL:
                return AdFormat.INTERSTITIAL;
            case NATIVE:
                return AdFormat.NATIVE;
            case REWARDED:
                return AdFormat.REWARDED;
            case APP_OPEN:
                return AdFormat.APP_OPEN;
            default:
                return AdFormat.UNKNOWN;
        }
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(@Nullable String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
