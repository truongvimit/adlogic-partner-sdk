package io.paykit.billing

import io.paykit.model.TrialInfo
import io.paykit.model.TrialUnit
import java.math.BigDecimal
import java.math.RoundingMode

/** Turns Play's raw price and period strings into what a row shows, without inventing English. */
internal object PriceFormatter {

    private const val DAYS_PER_YEAR = 365
    private const val DAYS_PER_MONTH = 30
    private const val DAYS_PER_WEEK = 7

    private val HUNDRED = BigDecimal(100)

    // Grouping separators only, never a plain space: Play appends units after one ("$9.99 / mo").
    private val AMOUNT = Regex("\\d[\\d.,\\u00A0\\u202F]*\\d|\\d")

    /**
     * Strike-through price implied by taking [discountPercent] off [price].
     *
     * Fallback path only — `AppPurchase.getOldPriceFormatted` is exact whenever Play knows the
     * micros, and this reconstruction has to guess which separator is the decimal one.
     */
    fun oldPrice(price: String, discountPercent: Int): String? {
        if (discountPercent !in 1..99) return null
        val match = AMOUNT.find(price) ?: return null
        val raw = match.value

        val separator = raw.lastIndexOfAny(charArrayOf('.', ','))
        val fraction = if (separator >= 0) raw.substring(separator + 1) else ""
        // Three trailing digits are a thousands group, not cents: "1.234" is not 1 unit 234 cents.
        val isDecimal = fraction.length in 1..2 && fraction.all { it.isDigit() }
        val decimals = if (isDecimal) fraction.length else 0

        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val amount = BigDecimal(digits).movePointLeft(decimals)
        if (amount.signum() <= 0) return null

        val scaled = amount
            .multiply(HUNDRED)
            .divide(BigDecimal(100 - discountPercent), decimals, RoundingMode.HALF_UP)
        val rendered = scaled.toPlainString().let {
            if (isDecimal && raw[separator] == ',') it.replace('.', ',') else it
        }
        return price.replaceRange(match.range, rendered)
    }

    /**
     * Reads an ISO-8601 billing period as Play reports it, e.g. `P1W`, `P7D`, `P1M15D`.
     *
     * @return null for anything unparseable, which the caller renders as "no trial" rather than
     *   as a broken label.
     */
    fun parseTrial(isoPeriod: String): TrialInfo? {
        if (!isoPeriod.startsWith("P")) return null

        var years = 0
        var months = 0
        var weeks = 0
        var days = 0
        var value = 0
        var hasDigits = false
        for (index in 1 until isoPeriod.length) {
            val char = isoPeriod[index]
            if (char.isDigit()) {
                value = value * 10 + (char - '0')
                hasDigits = true
                continue
            }
            if (!hasDigits) return null
            when (char) {
                'Y' -> years += value
                'M' -> months += value
                'W' -> weeks += value
                'D' -> days += value
                else -> return null
            }
            value = 0
            hasDigits = false
        }

        val present = listOf(
            years to TrialUnit.YEAR,
            months to TrialUnit.MONTH,
            weeks to TrialUnit.WEEK,
            days to TrialUnit.DAY,
        ).filter { (count, _) -> count > 0 }

        val single = present.singleOrNull()
        if (single != null) return TrialInfo(single.first, single.second)
        if (present.isEmpty()) return null
        // Each pw_trial_* plural carries exactly one unit, so a compound period collapses to days.
        val total = years * DAYS_PER_YEAR + months * DAYS_PER_MONTH + weeks * DAYS_PER_WEEK + days
        return TrialInfo(total, TrialUnit.DAY)
    }
}
