package com.ads.module.helper.interstitial

/**
 * Immutable preparation for one interstitial show. Defaults retain the loading dialog and
 * 800 ms delay; changing either does not change ad eligibility, ownership or navigation mode.
 * UnderAd cosmetic cleanup remains 1,500 ms after navigation commitment.
 *
 * [preShowDelayMs] must be in 0..89,999 ms: a 90-second delay would exhaust the SDK's reservation
 * before dispatch. This bound is not a recommended delay or a guarantee that a host stays
 * eligible. Setup time, deep sleep or changed policy can still reject before invocation.
 * Invalid values throw [IllegalArgumentException] at construction; they are never clamped.
 */
class InterShowOptions @JvmOverloads constructor(
    /** Whether to create the cosmetic dialog. False keeps the configured delay and all gates. */
    val showLoading: Boolean = true,
    /** Delay before final admission. Zero still queues on the main handler; default 800 ms. */
    val preShowDelayMs: Long = 800L,
) {
    init {
        require(preShowDelayMs in 0L..89_999L) {
            "preShowDelayMs must be between 0 and 89999 milliseconds"
        }
    }

    companion object {
        /** Existing SDK preparation behavior, safe to share because options are immutable. */
        @JvmField
        val DEFAULT = InterShowOptions()
    }
}
