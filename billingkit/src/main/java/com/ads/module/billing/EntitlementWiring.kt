package com.ads.module.billing

import android.content.Context
import com.ads.module.helper.Entitlement
import com.ads.module.helper.EntitlementSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the engine's premium answer to the ads SDK's Entitlement port when `:ads` is on the
 * classpath; an app that ships billing without ads simply skips the hand-off.
 */
internal object EntitlementWiring {

    private val attempted = AtomicBoolean(false)

    @JvmStatic
    @JvmName("installIntoAdsIfPresent")
    fun installIntoAdsIfPresent() {
        if (!attempted.compareAndSet(false, true)) return
        try {
            AdsEntitlementHook.install()
        } catch (_: NoClassDefFoundError) {
            // :ads absent — nothing gates on premium
        } catch (_: ExceptionInInitializerError) {
        }
    }
}

// Separate object so the Entitlement reference resolves only inside the guarded call above.
private object AdsEntitlementHook {

    fun install() {
        Entitlement.install(
            object : EntitlementSource {
                override fun isPremium(context: Context): Boolean =
                    AppPurchase.getInstance().isPurchased(context)
            },
        )
    }
}
