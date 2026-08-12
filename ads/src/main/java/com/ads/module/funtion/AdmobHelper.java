package com.ads.module.funtion;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-ad-unit click counter behind the interstitial daily cap.
 *
 * <p>Counts are keyed by ad unit id and live in their own prefs file so the whole window can be
 * wiped with a single {@code clear()}. The window start is {@link #KEY_FIRST_TIME}; its absence —
 * not a separate boolean — is what marks "no window open yet".
 *
 * <p>The rollover check runs inside {@link #getNumClickAdsPerDay} and
 * {@link #increaseNumClickAdsPerDay} rather than being a step callers must remember. It used to be
 * an explicit {@code setupAdmobData} call that only one of the three touch points made, guarded by
 * an inverted flag that could never become true — so the window never opened, {@code KEY_FIRST_TIME}
 * was never written, and the daily reset never ran.
 */
public class AdmobHelper {
    private static final String FILE_SETTING_ADMOB = "setting_admob.pref";
    private static final String KEY_FIRST_TIME = "KEY_FIRST_TIME";

    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    /**
     * Clicks recorded for {@code idAds} in the current 24h window.
     */
    public static int getNumClickAdsPerDay(Context context, String idAds) {
        rolloverIfDue(context);
        return prefs(context).getInt(idAds, 0);
    }

    /** Records one click on {@code idAds}. */
    public static void increaseNumClickAdsPerDay(Context context, String idAds) {
        rolloverIfDue(context);
        SharedPreferences pre = prefs(context);
        pre.edit().putInt(idAds, pre.getInt(idAds, 0) + 1).apply();
    }

    /**
     * Opens the window on first use and wipes every counter once it is 24h old.
     *
     * <p>A negative elapsed time also rolls over: the user moved the device clock backwards, and
     * the alternative is a window that never expires.
     */
    private static synchronized void rolloverIfDue(Context context) {
        SharedPreferences pre = prefs(context);
        long windowStart = pre.getLong(KEY_FIRST_TIME, 0L);
        long now = System.currentTimeMillis();
        if (windowStart == 0L) {
            pre.edit().putLong(KEY_FIRST_TIME, now).apply();
            return;
        }
        long elapsed = now - windowStart;
        if (elapsed >= 0 && elapsed < WINDOW_MS) {
            return;
        }
        // clear() then put() on one editor: clear is applied first, so the new window survives
        pre.edit().clear().putLong(KEY_FIRST_TIME, now).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE_SETTING_ADMOB, Context.MODE_PRIVATE);
    }
}
