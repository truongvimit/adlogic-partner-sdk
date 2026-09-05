package com.ads.module.helper

/**
 * Why an ad was not loaded or shown.
 *
 * [key] reaches analytics through `AdTracking.skipped` — treat it as a wire format and keep
 * the values stable.
 */
enum class AdSkipReason(val key: String) {

    /** The placement is disabled in config or has no usable ad unit id. */
    DISABLED_CONFIG("disabled_config"),

    /** The user bought the ad-free entitlement. */
    PURCHASED("purchased"),

    /** No network — the request would only burn the waterfall's timeout budget. */
    OFFLINE("offline"),

    /** Blocked by the UA/organic gate ([com.ads.module.ads.ERainAd.shouldDisplayForUa]). */
    UA_GATE("ua_gate"),

    /** Neither UMP nor the explicitly configured host consent source permits requests. */
    CONSENT_NOT_GRANTED("consent_not_granted"),

    /** Consent UI currently owns the screen. */
    CONSENT_FORM_SHOWING("consent_form_showing"),

    /** Nothing usable is buffered at the moment the caller wants to show. */
    NOT_READY("not_ready"),

    /** The captured ad or its presentation reservation expired before vendor invocation. */
    EXPIRED("expired"),

    /** The host left RESUMED before the vendor was called. */
    HOST_NOT_RESUMED("host_not_resumed"),

    /** The application is not in the foreground. */
    PROCESS_NOT_RESUMED("process_not_resumed"),

    /** The host must be a live Activity compatible with the entry point. */
    INVALID_HOST("invalid_host"),

    /** Vendor callback setup failed before show was invoked. */
    PREPARATION_FAILED("preparation_failed"),

    /** Another presentation owns this opportunity. */
    PRESENTATION_BUSY("presentation_busy"),

    /** The active host or flow suppresses this placement. */
    SUPPRESSED_BY_FLOW("suppressed_by_flow"),

    /** One return was suppressed after the host explicitly marked an ad click/action. */
    RETURNING_FROM_AD_CLICK("returning_from_ad_click"),

    /** The configured time interval has not elapsed. */
    INTERVAL("interval"),

    /** The ad unit's daily click cap was reached. */
    CLICK_CAP("click_cap"),

    /** The module declined by one of its own frequency rules (interval, show counter). */
    CAPPED_BY_MODULE("capped_by_module"),

    /** GMA reported a show failure. */
    FAILED_TO_SHOW("failed_to_show"),
    ;

    override fun toString(): String = key
}
