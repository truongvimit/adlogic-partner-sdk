package com.ads.module.billing

import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import io.trackkit.mmp.MmpTracking

/**
 * Purchase telemetry for the billing engine: the canonical Iap events plus MMP purchase revenue,
 * both through vendor-free seams so this module never learns which MMP the app ships.
 */
internal object BillingTracking {

    private const val DEFAULT_CURRENCY = "USD"

    @JvmStatic
    @JvmName("trackPurchaseSuccess")
    fun trackPurchaseSuccess(
        revenueMicros: Double,
        currency: String?,
        productId: String?,
        typeIap: Int,
    ) {
        Tracker.track(
            TrackkitEvents.Iap.Success(
                productId.orEmpty(),
                revenueMicros / 1_000_000.0,
                currency?.takeUnless { it.isEmpty() } ?: DEFAULT_CURRENCY,
                if (typeIap == AppPurchase.TYPE_IAP.SUBSCRIPTION) "subscription" else "purchase",
            ),
        )
        // The seam takes micros, exactly as getPriceAmountMicros() reports them.
        MmpTracking.trackPurchaseRevenue(revenueMicros, currency)
    }

    @JvmStatic
    @JvmName("trackPurchaseFail")
    fun trackPurchaseFail(productId: String?, responseCode: Int) {
        Tracker.track(TrackkitEvents.Iap.Fail(productId, responseCode))
    }
}
