package com.ads.module.billing;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Last known entitlement, so a cold start does not serve ads to a paying user before Play answers.
 *
 * <p>Deliberately never expires. It is only read until the first successful verification of the
 * process, and the splash awaits that verification, so a stale value survives seconds rather than
 * days. An expiry would only ever fire on a device Play cannot reach — which is exactly the device
 * that has no other source of truth to fall back on.
 */
final class PurchasePrefs {

    private static final String FILE = "erain_billing";
    private static final String KEY_PREMIUM = "premium_cached";
    private static final String KEY_PREMIUM_SOURCE = "premium_source";

    private PurchasePrefs() {
    }

    /**
     * @param source what produced the value — {@code "play"} for a verification sweep,
     *               {@code "purchase"} for a completed billing flow. Diagnostics only.
     */
    static void write(Context context, boolean premium, String source) {
        if (context == null) {
            return;
        }
        prefs(context).edit()
                .putBoolean(KEY_PREMIUM, premium)
                .putString(KEY_PREMIUM_SOURCE, source == null ? "" : source)
                .apply();
    }

    static boolean readCached(Context context) {
        return context != null && prefs(context).getBoolean(KEY_PREMIUM, false);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
