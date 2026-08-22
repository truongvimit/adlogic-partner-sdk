package com.ads.module.funtion;

/**
 * Billing events, delivered to every registered callback instead of the single
 * {@link PurchaseListener} slot a paywall screen could overwrite.
 *
 * <p>An abstract class rather than an interface so later events can be added without breaking
 * implementers; every method is a no-op by default.
 */
public abstract class PurchaseCallback {

    public void onProductPurchased(String orderId, String originalJson) {
    }

    public void onPurchasePending(String productId) {
    }

    public void onAlreadyOwned(String productId) {
    }

    /**
     * @param responseCode a {@code BillingClient.BillingResponseCode} value
     */
    public void onPurchaseError(int responseCode, String message) {
    }

    public void onUserCancelBilling() {
    }
}
