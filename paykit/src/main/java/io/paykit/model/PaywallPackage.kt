package io.paykit.model

/** One purchasable row as declared by config, before Play prices are attached. */
data class PaywallPackage(
    val id: String,
    val type: PackageType,
    val basePlanId: String?,
    val offerId: String?,
    /** Literal copy from the console. Non-null wins over [titleKey] and is not localised. */
    val title: String?,
    val titleKey: String?,
    val subtitle: String?,
    val subtitleKey: String?,
    val badge: String?,
    val discountPercent: Int,
    val preselected: Boolean,
)

enum class PackageType { SUBSCRIPTION, IN_APP, CONSUMABLE }
