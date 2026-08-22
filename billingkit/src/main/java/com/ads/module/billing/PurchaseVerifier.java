package com.ads.module.billing;

/**
 * Optional server-side receipt check run on purchases that arrive from a billing flow, before the
 * entitlement is granted. Purchases restored by a startup sweep are not routed through it.
 *
 * <p>The SDK ships the seam only — the HTTP call and any API key stay in the host app. Leaving it
 * unset treats every purchase as verified.
 */
public interface PurchaseVerifier {

    /**
     * @param callback must be invoked exactly once, on any thread
     */
    void verify(String productId, String purchaseToken, String originalJson, Callback callback);

    interface Callback {
        /**
         * @param reason cause when {@code verified} is false; may be null
         */
        void onResult(boolean verified, String reason);
    }
}
