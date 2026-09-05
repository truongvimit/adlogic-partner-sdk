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

    /** The module declined by one of its own frequency rules (interval, show counter). */
    CAPPED_BY_MODULE("capped_by_module"),

    /** The host left the foreground before GMA show; a still-fresh fill can be reused. */
    SHOW_IN_BACKGROUND("show_in_background"),

    /** GMA reported a show failure. */
    FAILED_TO_SHOW("failed_to_show"),
    ;

    override fun toString(): String = key
}
