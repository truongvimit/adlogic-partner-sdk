package com.ads.module.util

import android.content.Context
import android.content.SharedPreferences

object SharePreferenceUtils {
    private const val PREF_NAME = "e_rain_ad_pref"
    private const val KEY_INSTALL_TIME = "KEY_INSTALL_TIME"
    private const val KEY_LAST_IMPRESSION_INTERSTITIAL_TIME = "KEY_LAST_IMPRESSION_INTERSTITIAL_TIME"
    private const val KEY_IS_ORGANIC = "KEY_IS_ORGANIC"
    private const val KEY_INSTALL_REFERRER_TRACKED = "KEY_INSTALL_REFERRER_TRACKED"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getInstallTime(context: Context): Long = prefs(context).getLong(KEY_INSTALL_TIME, 0)

    @JvmStatic
    fun setInstallTime(context: Context) {
        prefs(context).edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun getLastImpressionInterstitialTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, 0)

    @JvmStatic
    fun setLastImpressionInterstitialTime(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, System.currentTimeMillis()).apply()
    }

    @JvmStatic
    fun getIsOrganic(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_ORGANIC, true)

    @JvmStatic
    fun setIsOrganic(context: Context, isOrganic: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_ORGANIC, isOrganic).apply()
    }

    /** The install referrer never changes, so it is read and reported exactly once per install. */
    @JvmStatic
    fun isInstallReferrerTracked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INSTALL_REFERRER_TRACKED, false)

    @JvmStatic
    fun setInstallReferrerTracked(context: Context) {
        prefs(context).edit().putBoolean(KEY_INSTALL_REFERRER_TRACKED, true).apply()
    }
}
