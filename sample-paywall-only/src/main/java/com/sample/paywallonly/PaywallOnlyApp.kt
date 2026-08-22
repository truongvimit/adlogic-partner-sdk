package com.sample.paywallonly

import android.app.Application
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.PurchaseItem
import io.paykit.PayKit
import io.paykit.payKitConfig

/**
 * The smallest scenario-2 host: billing plus the prebuilt paywall, no ads module anywhere.
 * Exercising the real entry points keeps this a compile-time proof, not an empty shell.
 */
class PaywallOnlyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPurchase.getInstance().initBilling(
            this,
            listOf(PurchaseItem("premium_monthly", AppPurchase.TYPE_IAP.SUBSCRIPTION)),
        )
        Billing.install(this)
        payKitConfig {
            termsUrl = "https://example.com/terms"
            privacyUrl = "https://example.com/privacy"
        }.onSuccess { PayKit.install(this, it) }
    }
}
