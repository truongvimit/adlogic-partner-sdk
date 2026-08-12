package com.ads.module.config;

/**
 * Everything the Adjust integration needs, in one object.
 *
 * <p>Adjust identifies events by six-character <em>tokens</em> minted on its dashboard, never by
 * name. Every token field below defaults to empty and an empty token is skipped with a warning
 * rather than sent: {@code AdjustEvent("")} is accepted client-side, dropped server-side, and the
 * revenue disappears without a trace.
 */
public class AdjustConfig {

    /**
     * Adjust's own source keys for the dedicated ad-revenue API.
     */
    public static final String AD_REVENUE_ADMOB = "admob_sdk";
    public static final String AD_REVENUE_APPLOVIN_MAX = "applovin_max_sdk";

    private boolean enableAdjust = false;

    /** App token from the Adjust dashboard. Without it the SDK is never initialised. */
    private String adjustToken = "";

    /** Event token fired when a purchase completes. */
    private String eventNamePurchase = "";

    /**
     * Event token fired on every paid ad impression, in addition to {@code Adjust.trackAdRevenue}.
     * Networks that cannot consume Adjust's ad-revenue API (TikTok, Meta) read this one instead.
     * Leave it empty unless a partner network asked for it — configuring it means the same money
     * reaches Adjust twice, once as ad revenue and once as event revenue.
     */
    private String eventAdImpression = "";

    /**
     * Meta app id. Adjust needs it to forward install and event data to Meta; without it the
     * Meta-attributed campaigns in the Adjust dashboard stay empty. Same value as the
     * {@code facebook_app_id} string resource.
     */
    private String fbAppId = "";

    public AdjustConfig(boolean enableAdjust) {
        this.enableAdjust = enableAdjust;
    }

    public AdjustConfig(boolean enableAdjust, String adjustToken) {
        this.enableAdjust = enableAdjust;
        this.adjustToken = adjustToken;
    }

    public boolean isEnableAdjust() {
        return enableAdjust;
    }

    public void setEnableAdjust(boolean enableAdjust) {
        this.enableAdjust = enableAdjust;
    }

    public String getAdjustToken() {
        return adjustToken;
    }

    public void setAdjustToken(String adjustToken) {
        this.adjustToken = adjustToken;
    }

    public String getEventNamePurchase() {
        return eventNamePurchase;
    }

    public void setEventNamePurchase(String eventNamePurchase) {
        this.eventNamePurchase = eventNamePurchase;
    }

    public String getEventAdImpression() {
        return eventAdImpression;
    }

    public void setEventAdImpression(String eventAdImpression) {
        this.eventAdImpression = eventAdImpression;
    }

    public String getFbAppId() {
        return fbAppId;
    }

    public void setFbAppId(String fbAppId) {
        this.fbAppId = fbAppId;
    }
}
