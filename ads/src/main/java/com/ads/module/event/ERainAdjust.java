package com.ads.module.event;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.adjust.sdk.Adjust;
import com.adjust.sdk.AdjustAdRevenue;
import com.adjust.sdk.AdjustEvent;
import com.adjust.sdk.AdjustThirdPartySharing;
import com.ads.module.ads.ERainAd;
import com.ads.module.config.AdjustConfig;
import com.google.android.gms.ads.AdValue;

/**
 * The single writer to the Adjust SDK.
 *
 * <p>Adjust is an MMP, not a product-analytics tool: it answers "which campaign produced this
 * install" and "how much money did that user make". Everything it receives is therefore revenue or
 * a conversion milestone — funnel and screen telemetry belong in Firebase, not here.
 *
 * <p>Adjust is keyed by six-character event <em>tokens</em> minted on its dashboard, not by event
 * names. An {@code AdjustEvent("")} is not a no-op: Adjust accepts the object, fails server-side and
 * the revenue disappears with no client-side signal. Every method below therefore refuses to send
 * when the token is missing, and says so in the log.
 */
public class ERainAdjust {

    private static final String TAG = "ERainAdjust";

    /**
     * True once {@code Adjust.initSdk} has been called with a config that passed validation.
     */
    private static volatile boolean initialized = false;

    private ERainAdjust() {
    }

    /**
     * Called by {@code ERainAd} after a successful {@code Adjust.initSdk}.
     */
    public static void markInitialized() {
        initialized = true;
    }

    /**
     * True when an event sent right now would actually reach Adjust: the SDK is up, and the partner
     * has not turned the integration off.
     */
    public static boolean isEnabled() {
        AdjustConfig config = config();
        return initialized && config != null && config.isEnableAdjust();
    }

    // -----------------------------------------------------------------------
    // Ad revenue
    // -----------------------------------------------------------------------

    /**
     * AdMob paid impression, with the breakdown Adjust can slice ROAS by.
     *
     * <p>The wrapper knows the unit, the mediation adapter that filled it and the screen the unit is
     * registered to, so all three are forwarded. Apero sent these for AppLovin only and left the
     * AdMob payload as a bare revenue figure, which is why its Adjust dashboard could not tell a
     * splash interstitial from a home banner.
     */
    public static void pushTrackEventAdmob(AdValue adValue, @Nullable String adUnitId,
                                           @Nullable String network, @Nullable String placement) {
        if (!isEnabled() || adValue == null) {
            return;
        }
        AdjustAdRevenue adRevenue = new AdjustAdRevenue(AdjustConfig.AD_REVENUE_ADMOB);
        adRevenue.setRevenue(adValue.getValueMicros() / 1_000_000d, adValue.getCurrencyCode());
        if (!TextUtils.isEmpty(network)) {
            adRevenue.setAdRevenueNetwork(network);
        }
        if (!TextUtils.isEmpty(adUnitId)) {
            adRevenue.setAdRevenueUnit(adUnitId);
        }
        if (!TextUtils.isEmpty(placement)) {
            adRevenue.setAdRevenuePlacement(placement);
        }
        Adjust.trackAdRevenue(adRevenue);
    }

    /**
     * Configured impression token, used when a call site does not pass one of its own.
     */
    static String adImpressionToken() {
        AdjustConfig config = config();
        return config == null ? null : config.getEventAdImpression();
    }

    // -----------------------------------------------------------------------
    // Purchase revenue
    // -----------------------------------------------------------------------

    /**
     * In-app purchase.
     *
     * @param revenueMicros price in micros — that is what Play Billing's
     *                      {@code getPriceAmountMicros()} returns and what
     *                      {@code AppPurchase.handlePurchase} forwards. Converted here so the
     *                      conversion sits next to the thing that documents the unit, instead of
     *                      being buried in the generic {@link #onTrackRevenue} helper where a
     *                      caller passing real currency units would silently lose a factor of 1e6.
     */
    public static void onTrackRevenuePurchase(double revenueMicros, String currency) {
        AdjustConfig config = config();
        if (!isEnabled() || config == null) {
            return;
        }
        String token = config.getEventNamePurchase();
        if (TextUtils.isEmpty(token)) {
            warnMissingToken("eventNamePurchase");
            return;
        }
        onTrackRevenue(token, revenueMicros / 1_000_000d, currency);
    }

    // -----------------------------------------------------------------------
    // Generic passthrough — for conversion milestones outside the ad flow
    // -----------------------------------------------------------------------

    /**
     * Fires a bare conversion event. Prefer {@link MmpTracking} from modules outside {@code :ads}.
     */
    public static void onTrackEvent(String token) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return;
        }
        Adjust.trackEvent(new AdjustEvent(token));
    }

    public static void onTrackEvent(String token, String callbackId) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return;
        }
        AdjustEvent event = new AdjustEvent(token);
        event.setCallbackId(callbackId);
        Adjust.trackEvent(event);
    }

    /**
     * @param revenue value in real currency units. The previous implementation divided by 1e6 here,
     *                which was only correct because its single caller happened to pass micros —
     *                any partner calling it with a real price under-reported by a million.
     */
    public static void onTrackRevenue(String token, double revenue, String currency) {
        if (!isEnabled() || rejectBlankToken(token)) {
            return;
        }
        AdjustEvent event = new AdjustEvent(token);
        event.setRevenue(revenue, orDefault(currency));
        Adjust.trackEvent(event);
    }

    // -----------------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------------

    /**
     * Relays the UMP outcome. Call it from the app's consent callback — {@code ERainAd} used to
     * declare third-party sharing unconditionally at init, before the form had even been shown.
     */
    public static void setConsent(boolean analyticsGranted, boolean adsGranted) {
        if (!isEnabled()) {
            return;
        }
        Adjust.trackMeasurementConsent(analyticsGranted);
        Adjust.trackThirdPartySharing(new AdjustThirdPartySharing(adsGranted));
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static final String DEFAULT_CURRENCY = "USD";

    private static AdjustConfig config() {
        ERainAd instance = ERainAd.getInstance();
        if (instance == null || instance.getAdConfig() == null) {
            return null;
        }
        return instance.getAdConfig().getAdjustConfig();
    }

    /**
     * A blank token is a configuration mistake, not a quiet opt-out — say so once per call site.
     */
    private static boolean rejectBlankToken(String token) {
        if (!TextUtils.isEmpty(token)) {
            return false;
        }
        Log.w(TAG, "Adjust event skipped: no event token. "
                + "Mint one on the Adjust dashboard; an empty token is dropped server-side.");
        return true;
    }

    private static String orDefault(String currency) {
        return TextUtils.isEmpty(currency) ? DEFAULT_CURRENCY : currency;
    }

    private static void warnMissingToken(String field) {
        Log.w(TAG, "AdjustConfig." + field + " is empty — event skipped. "
                + "Mint the token on the Adjust dashboard and set it on ERainAdConfig.adjustConfig; "
                + "sending an empty token loses the revenue silently.");
    }
}
