package io.paykit.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import com.ads.module.admob.AppOpenManager
import com.ads.module.billing.AppPurchase
import com.ads.module.billing.Billing
import com.ads.module.billing.PurchaseItem
import com.ads.module.billing.ReadyResult
import io.paykit.R
import io.paykit.internal.PayKitLog
import io.paykit.model.PackageType
import io.paykit.model.PaywallPackage
import io.paykit.model.PriceView
import io.paykit.model.TrialInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Named here so the rest of :paykit can spell these types without importing com.ads.module. The
// per-case aliases earn their keep: Kotlin cannot reach a nested classifier through an alias.
internal typealias LaunchResult = com.ads.module.billing.LaunchResult
internal typealias PurchaseEvent = com.ads.module.billing.PurchaseEvent
internal typealias PurchasedEvent = com.ads.module.billing.PurchaseEvent.Purchased
internal typealias PendingEvent = com.ads.module.billing.PurchaseEvent.Pending
internal typealias AlreadyOwnedEvent = com.ads.module.billing.PurchaseEvent.AlreadyOwned
internal typealias PurchaseErrorEvent = com.ads.module.billing.PurchaseEvent.Error
internal typealias CanceledEvent = com.ads.module.billing.PurchaseEvent.Canceled
internal typealias RestoreResult = com.ads.module.billing.RestoreResult
internal typealias RestoredResult = com.ads.module.billing.RestoreResult.Restored
internal typealias NothingToRestoreResult = com.ads.module.billing.RestoreResult.NothingToRestore
internal typealias RestoreErrorResult = com.ads.module.billing.RestoreResult.Error

/**
 * The single door between the paywall and the billing engine in `:billingkit`, plus the one
 * app-resume switch flipped in `:ads` when that module is present at all.
 *
 * Swapping vendors is a rewrite of this file and of nothing else, which is why no other file in
 * `:paykit` imports `com.ads.module`.
 */
internal object BillingBridge {

    val events: Flow<PurchaseEvent> = Billing.purchaseEvents

    val isPremium: StateFlow<Boolean> = Billing.isPremium

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var registered: List<String> = emptyList()

    /**
     * Hands the catalogue to Play and starts the entitlement stream.
     *
     * Re-registering an unchanged catalogue is skipped: `initBilling` tears down the live client,
     * so calling it on every `sync` would drop a connection that was already working.
     */
    fun registerCatalog(app: Application, packages: List<PaywallPackage>) {
        appContext = app.applicationContext

        val signature = packages.map { "${it.id}|${it.basePlanId}|${it.offerId}|${it.type}" }
        if (packages.isNotEmpty() && signature != registered) {
            val items = packages.map { pkg ->
                PurchaseItem(
                    pkg.id,
                    pkg.basePlanId.orEmpty(),
                    pkg.offerId.orEmpty(),
                    pkg.type.typeIap(),
                )
            }
            AppPurchase.getInstance().initBilling(app, items)
            registered = signature
            PayKitLog.d("catalogue registered with billing: ${signature.joinToString()}")
        }

        // Unconditional, and after any initBilling: it seeds isPremium from the cached entitlement,
        // so skipping it for an empty catalogue would show a paying user the paywall.
        Billing.install(app)
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean = when (Billing.awaitReady(timeoutMs)) {
        is ReadyResult.Ready -> true
        is ReadyResult.Timeout -> false
        is ReadyResult.Error -> false
    }

    /** Everything the row needs, already resolved against Play and the host's string table. */
    fun priceOf(pkg: PaywallPackage, selected: Boolean): PriceView {
        val purchase = AppPurchase.getInstance()
        // The renewal price, never the offer's entry phase: on a free-trial offer that phase is
        // zero, so the row would advertise the plan itself as free.
        val price = when (pkg.type) {
            PackageType.SUBSCRIPTION -> purchase.getPriceSub(pkg.id)
            PackageType.IN_APP, PackageType.CONSUMABLE -> purchase.getPrice(pkg.id)
        }.orEmpty()

        return PriceView(
            packageId = pkg.id,
            title = pkg.title ?: label(pkg.titleKey) ?: defaultTitle(pkg),
            subtitle = pkg.subtitle ?: label(pkg.subtitleKey),
            price = price,
            oldPrice = oldPriceOf(purchase, pkg, price),
            badge = pkg.badge?.takeIf { it.isNotBlank() },
            trial = trialOf(purchase, pkg),
            selected = selected,
        )
    }

    /** Returns the engine's own verdict; the caller maps it to a message, never the reverse. */
    fun launch(activity: Activity, pkg: PaywallPackage): LaunchResult {
        val purchase = AppPurchase.getInstance()
        return when (pkg.type) {
            PackageType.SUBSCRIPTION -> purchase.subscribeProduct(
                activity,
                pkg.id,
                // An empty token means the catalogue coordinates matched nothing; let :ads pick.
                offerTokenOf(purchase, pkg).ifEmpty { null },
            )

            PackageType.IN_APP, PackageType.CONSUMABLE -> purchase.purchaseProduct(activity, pkg.id)
        }
    }

    suspend fun restore(): RestoreResult = Billing.restore()

    /** Keeps the app-resume ad off the paywall; the ads SDK keeps a plain list, so call once. */
    fun excludeFromAppResume(screen: Class<*>) {
        try {
            AppOpenManager.getInstance().disableAppResumeWithActivity(screen)
        } catch (_: NoClassDefFoundError) {
            // :ads is compileOnly and absent on a paywall-without-ads host — nothing to suppress
        }
    }

    private fun offerTokenOf(purchase: AppPurchase, pkg: PaywallPackage): String = when (pkg.type) {
        PackageType.SUBSCRIPTION ->
            purchase.resolveOfferToken(pkg.id, pkg.basePlanId.orEmpty(), pkg.offerId.orEmpty())
                .orEmpty()

        PackageType.IN_APP, PackageType.CONSUMABLE -> ""
    }

    private fun oldPriceOf(purchase: AppPurchase, pkg: PaywallPackage, price: String): String? {
        if (pkg.discountPercent !in 1..99) return null
        // Play's strike-through needs the product's micros; an offer it cannot price falls back to
        // scaling the formatted string, which is the only figure guaranteed to be present.
        val fromPlay = purchase
            .getOldPriceFormatted(pkg.id, pkg.type.typeIap(), pkg.discountPercent)
            .orEmpty()
        return fromPlay.ifBlank { PriceFormatter.oldPrice(price, pkg.discountPercent) }
    }

    private fun trialOf(purchase: AppPurchase, pkg: PaywallPackage): TrialInfo? {
        if (pkg.type != PackageType.SUBSCRIPTION || !purchase.hasFreeTrial(pkg.id)) return null
        return PriceFormatter.parseTrial(purchase.getFreeTrialPeriod(pkg.id).orEmpty())
    }

    // A key nobody defined must not put a Play SKU on screen, which is what the raw id would do.
    private fun defaultTitle(pkg: PaywallPackage): String =
        appContext?.getString(R.string.pw_plan_default) ?: pkg.id

    /** Resolves a remote `*_key` against the merged string table; an unknown key is not fatal. */
    private fun label(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val context = appContext ?: return null
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }

    private fun PackageType.typeIap(): Int = when (this) {
        PackageType.SUBSCRIPTION -> AppPurchase.TYPE_IAP.SUBSCRIPTION
        PackageType.IN_APP -> AppPurchase.TYPE_IAP.PURCHASE
        PackageType.CONSUMABLE -> AppPurchase.TYPE_IAP.CONSUMABLE
    }
}
