package io.paykit.model

/** Everything the row needs to render, already resolved and formatted. */
data class PriceView(
    val packageId: String,
    val title: String,
    val subtitle: String?,
    val price: String,
    val oldPrice: String?,
    val badge: String?,
    val trial: TrialInfo?,
    val selected: Boolean,
)

data class TrialInfo(val count: Int, val unit: TrialUnit)

enum class TrialUnit { DAY, WEEK, MONTH, YEAR }
