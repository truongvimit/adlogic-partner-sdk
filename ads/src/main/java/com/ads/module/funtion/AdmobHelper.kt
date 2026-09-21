package com.ads.module.funtion

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-ad-unit click counter behind the interstitial daily cap.
 *
 * Counts are keyed by ad unit id and live in their own prefs file so the whole window can be wiped
 * with a single `clear()`. The window start is [KEY_FIRST_TIME]; its absence — not a separate
 * boolean — is what marks "no window open yet".
 *
 * The rollover check runs inside [getNumClickAdsPerDay] and [increaseNumClickAdsPerDay] rather than
 * being a step callers must remember.
 */
object AdmobHelper {
    private const val FILE_SETTING_ADMOB = "setting_admob.pref"
    private const val KEY_FIRST_TIME = "KEY_FIRST_TIME"

    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** Clicks recorded for [idAds] in the current 24h window. */
    @JvmStatic
    fun getNumClickAdsPerDay(context: Context?, idAds: String?): Int {
        rolloverIfDue(context)
        return prefs(context).getInt(idAds, 0)
    }

    /** Records one click on [idAds]. */
    @JvmStatic
    fun increaseNumClickAdsPerDay(context: Context?, idAds: String?) {
        rolloverIfDue(context)
        val pre = prefs(context)
        pre.edit().putInt(idAds, pre.getInt(idAds, 0) + 1).apply()
    }

    @Synchronized
    private fun rolloverIfDue(context: Context?) {
        val pre = prefs(context)
        val windowStart = pre.getLong(KEY_FIRST_TIME, 0L)
        val now = System.currentTimeMillis()
        if (windowStart == 0L) {
            pre.edit().putLong(KEY_FIRST_TIME, now).apply()
            return
        }
        // A negative elapsed time rolls over too: the clock moved back, and the alternative is a
        // window that never expires.
        val elapsed = now - windowStart
        if (elapsed in 0 until WINDOW_MS) return
        // clear() then put() on one editor: clear is applied first, so the new window survives
        pre.edit().clear().putLong(KEY_FIRST_TIME, now).apply()
    }

    private fun prefs(context: Context?): SharedPreferences =
        context!!.getSharedPreferences(FILE_SETTING_ADMOB, Context.MODE_PRIVATE)
}
