package com.ads.module.util;

import android.content.Context;
import android.content.SharedPreferences;

public class SharePreferenceUtils {
    private final static String PREF_NAME = "e_rain_ad_pref";

    private final static String KEY_INSTALL_TIME = "KEY_INSTALL_TIME";

    private final static String KEY_LAST_IMPRESSION_INTERSTITIAL_TIME = "KEY_LAST_IMPRESSION_INTERSTITIAL_TIME";

    private final static String KEY_IS_ORGANIC = "KEY_IS_ORGANIC";

    private final static String KEY_INSTALL_REFERRER_TRACKED = "KEY_INSTALL_REFERRER_TRACKED";

    public static long getInstallTime(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return pre.getLong(KEY_INSTALL_TIME, 0);
    }

    public static void setInstallTime(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        pre.edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply();
    }

    // The cumulative ad-revenue persistence (total, $0.01 bucket, D3/D7 milestone flags) that used
    // to live here belongs to Trackkit's AdRevenueAccumulator now; the mirror copy was deleted
    // rather than left to drift out of sync with the real one.

    public static long getLastImpressionInterstitialTime(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return pre.getLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, 0);
    }

    public static void setLastImpressionInterstitialTime(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        pre.edit().putLong(KEY_LAST_IMPRESSION_INTERSTITIAL_TIME, System.currentTimeMillis()).apply();
    }

    public static boolean getIsOrganic(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return pre.getBoolean(KEY_IS_ORGANIC, true);
    }

    public static void setIsOrganic(Context context, boolean isOrganic) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        pre.edit().putBoolean(KEY_IS_ORGANIC, isOrganic).apply();
    }

    /**
     * The install referrer never changes, so it is read and reported exactly once per install.
     */
    public static boolean isInstallReferrerTracked(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return pre.getBoolean(KEY_INSTALL_REFERRER_TRACKED, false);
    }

    public static void setInstallReferrerTracked(Context context) {
        SharedPreferences pre = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        pre.edit().putBoolean(KEY_INSTALL_REFERRER_TRACKED, true).apply();
    }
}
