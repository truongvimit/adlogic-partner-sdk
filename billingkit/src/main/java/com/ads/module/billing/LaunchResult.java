package com.ads.module.billing;

/**
 * Outcome of a launch-billing-flow call. The String-returning purchase/subscribe methods collapse
 * success, "not ready" and dev mode onto the same empty string; this enum keeps them apart.
 */
public enum LaunchResult {

    LAUNCHED("Billing flow launched"),
    BILLING_NOT_READY("Billing client is not ready"),
    PRODUCT_NOT_FOUND("Product ID invalid"),
    NO_OFFER("No available offers for this subscription"),
    OFFER_TOKEN_REQUIRED("Offer token is required"),
    ITEM_ALREADY_OWNED("Selected item is already owned"),
    NETWORK_ERROR("Network error"),
    USER_CANCELED("Request canceled"),
    DEV_MODE("Dev variant: purchase simulated, no Play flow launched"),
    ERROR("Error completing request");

    private final String message;

    LaunchResult(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
