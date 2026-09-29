package io.onboardkit.ads

/**
 * Why an ad was not shown.
 *
 * One value per distinct cause: "no ad appeared" is a dozen different problems and only the
 * reason says which one. [key] reaches analytics — treat it as a wire format and keep it stable.
 */
enum class AdSkipReason(val key: String) {

    /** Waiting for startup update policy, or a mandatory update prevents ad requests. */
    REQUESTS_HELD("requests_held"),

    /** The user bought the ad-free entitlement. */
    PREMIUM("premium"),

    /**
     * The consent flow has not answered yet, or the user refused.
     *
     * Requesting an ad before that answer is a policy violation, not just a bad impression.
     */
    CONSENT_NOT_GRANTED("consent_not_granted"),

    /** No active onboarding ad configuration is available for the requested policy. */
    ADS_OFF_IN_CONFIG("ads_off_config"),

    /** No [OnboardingAdProvider] was installed. */
    NO_PROVIDER("no_provider"),

    /** The placement has no usable ad unit id. */
    NO_AD_UNIT("no_ad_unit"),

    /** The SDK-wide remote kill switch is off. */
    ADS_OFF_BY_REMOTE("ads_off_remote"),

    /** This placement's own remote flag is off while ads in general are on. */
    PLACEMENT_OFF_BY_REMOTE("placement_off_remote"),

    /** Nothing filled: the whole waterfall failed or the load budget expired. */
    NO_FILL("no_fill"),

    /** Nothing is buffered at the moment the flow needs to show. */
    NOT_READY("not_ready"),

    /**
     * No usable network. Same wire key as the ads module's own reason, so the two report as one.
     */
    OFFLINE("offline"),

    /**
     * The placement's user-acquisition gate declined the request. Same wire key as the ads module.
     */
    UA_GATE("ua_gate"),

    /**
     * The `:ads` module declined at show time with `onNextAction` and no other callback.
     *
     * In practice that is its daily click cap,
     * `ad_behavior_config.interstitial.frequency.max_clicks_per_24h`, with
     * `ERainAd.setMaxClickAdsPerDay` as the host fallback. The interstitial interval never applies
     * to onboarding placements.
     */
    CAPPED_BY_ADS_MODULE("capped_by_module"),

    /** The user purchased at the paywall, so the ad no longer applies. */
    PURCHASED_AT_PAYWALL("purchased_at_paywall"),

    /** The flow is already showing a full-screen ad; a second must not stack on top. */
    SUPPRESSED_BY_FLOW("suppressed_by_flow"),

    /** The user is coming back from an ad they tapped, not starting a new session. */
    RETURNING_FROM_AD_CLICK("returning_from_ad_click"),

    /** The host left the foreground before GMA show; a still-fresh fill can be reused. */
    SHOW_IN_BACKGROUND("show_in_background"),

    /** GMA reported a show failure. */
    FAILED_TO_SHOW("failed_to_show"),

    /**
     * No longer raised. It stood for a module branch that accepted `show()` and then reported
     * nothing; every path through the module answers with an outcome of its own now.
     *
     * Kept because [key] is a wire format and historic analytics still carry it.
     */
    @Deprecated("Nothing produces this any more; the ads module reports on every show path.")
    NO_HANDSHAKE("no_handshake"),
    ;

    override fun toString(): String = key
}
