package com.ads.module.helper.interstitial

import android.content.Context
import com.ads.module.ads.ERainAd
import com.ads.module.util.SharePreferenceUtils

/**
 * The interstitial interval rule, readable without attempting a show.
 *
 * The rule itself lives in [ERainAd.forceShowInterstitial], which compares
 * [SharePreferenceUtils.getLastImpressionInterstitialTime] against
 * `ERainAdConfig.intervalInterstitialAd` and answers a blocked show with a bare `onNextAction`.
 * By the time that answer arrives the store has already dropped the buffer, so a tap made one
 * second too early used to cost a filled ad.
 *
 * This reads the *same two values* rather than keeping a second copy of the clock. That is
 * deliberate: a mirrored timestamp that drifted would let the store pass a show the module then
 * refuses, and the buffer would be eaten anyway.
 *
 * The clock is global — one timestamp for the whole app, stamped when any interstitial routed
 * through the module is dismissed — so one placement's impression paces every other placement.
 */
object InterstitialFrequency {

    /**
     * Milliseconds left before an interstitial may be shown; `0` when one may be shown now.
     *
     * Pure, so the boundary cases are testable: interval `0` disables the rule, a fresh install
     * (`lastImpressionMs == 0`) is never blocked, and `now - last == interval` is allowed —
     * matching the strict `<` the module compares with.
     */
    @JvmStatic
    fun remainingMs(nowMs: Long, lastImpressionMs: Long, intervalSeconds: Int): Long {
        if (intervalSeconds <= 0) return 0L
        if (lastImpressionMs <= 0L) return 0L
        val intervalMs = intervalSeconds * 1_000L
        val elapsed = nowMs - lastImpressionMs
        // A rolled-back device clock makes elapsed negative. The module blocks in that case, so
        // this blocks too — clamping here alone would only hand the module a show it then refuses.
        return (intervalMs - elapsed).coerceIn(0L, intervalMs)
    }

    /** The configured interval in seconds; `0` when the module is not initialised yet. */
    @JvmStatic
    fun intervalSeconds(): Int = ERainAd.getInstance().adConfig?.intervalInterstitialAd ?: 0

    /** Milliseconds left before the next interstitial may be shown. */
    @JvmStatic
    fun remainingMs(context: Context): Long = remainingMs(
        System.currentTimeMillis(),
        SharePreferenceUtils.getLastImpressionInterstitialTime(context),
        intervalSeconds(),
    )

    /** True when the interval rule would let an interstitial through right now. */
    @JvmStatic
    fun elapsed(context: Context): Boolean = remainingMs(context) == 0L
}
