package com.example.app

import com.ads.module.billing.AppPurchase
import com.ads.module.billing.PurchaseItem

/** App-owned product catalogue. Replace IDs with products configured in Google Play. */
object BillingProducts {
    const val LIFETIME = "iap.lifetime"
    const val YEARLY = "sub.yearly"
    const val YEARLY_BASE_PLAN = "yearly"
    const val YEARLY_OFFER = "freetrial" // "" lets the SDK choose an offer; it does not force the base price.

    val items: List<PurchaseItem> get() = listOf(
        PurchaseItem(LIFETIME, AppPurchase.TYPE_IAP.PURCHASE),
        PurchaseItem(YEARLY, YEARLY_BASE_PLAN, YEARLY_OFFER, AppPurchase.TYPE_IAP.SUBSCRIPTION),
    )
}
