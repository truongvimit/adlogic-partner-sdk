package com.ads.module.event;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.adjust.sdk.Adjust;
import com.adjust.sdk.GooglePlayInstallReferrerDetails;
import com.adjust.sdk.OnGooglePlayInstallReferrerReadListener;
import com.ads.module.util.SharePreferenceUtils;

import java.util.HashMap;
import java.util.Map;

import io.trackkit.Tracker;
import io.trackkit.TrackkitEvents;

/**
 * Reads the Google Play install referrer through Adjust and reports it once per install.
 *
 * <p>Adjust already fetches the referrer for its own attribution; this only mirrors it into
 * analytics so a Firebase-side funnel can be split by acquisition channel without a join against
 * the MMP. It is read exactly once — the guard is persisted, because the callback also fires on
 * every cold start and the referrer never changes.
 *
 * <p>The raw referrer string is deliberately not sent. It is a URL-encoded query that routinely
 * runs past GA4's 100-character param limit, so it would arrive truncated in the middle of a
 * campaign name; the {@code utm_*} fields are parsed out instead.
 */
public final class AdjustInstallReferrer {

    private static final String TAG = "ERainAdjust";

    private AdjustInstallReferrer() {
    }

    public static void readOnce(Context context) {
        Context appContext = context.getApplicationContext();
        if (SharePreferenceUtils.isInstallReferrerTracked(appContext)) {
            return;
        }
        Adjust.getGooglePlayInstallReferrer(appContext, new OnGooglePlayInstallReferrerReadListener() {
            @Override
            public void onInstallReferrerRead(GooglePlayInstallReferrerDetails details) {
                if (details == null) {
                    return;
                }
                SharePreferenceUtils.setInstallReferrerTracked(appContext);
                Tracker.track(TrackkitEvents.APP_INSTALL_REFERRER, parse(details));
            }

            @Override
            public void onFail(String message) {
                // No mark: a failure here is usually "Play Store not ready yet", which the next
                // cold start resolves. Marking it done would lose the referrer permanently.
                Log.w(TAG, "install referrer unavailable: " + message);
            }
        });
    }

    private static Map<String, Object> parse(GooglePlayInstallReferrerDetails details) {
        Map<String, Object> params = new HashMap<>(6);
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_SOURCE, param(details.installReferrer, "utm_source"));
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_MEDIUM, param(details.installReferrer, "utm_medium"));
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_CAMPAIGN, param(details.installReferrer, "utm_campaign"));
        putIfPresent(params, "install_version", details.installVersion);
        params.put("is_instant", Boolean.TRUE.equals(details.googlePlayInstant));
        return params;
    }

    /**
     * The referrer is a bare query string ({@code utm_source=x&utm_medium=y}), so it is parsed as
     * the query of a synthetic URI rather than split by hand — that is what decodes the escapes.
     */
    @Nullable
    private static String param(@Nullable String referrer, String key) {
        if (TextUtils.isEmpty(referrer)) {
            return null;
        }
        try {
            return Uri.parse("https://x/?" + referrer).getQueryParameter(key);
        } catch (RuntimeException e) {
            // UnsupportedOperationException on an opaque URI, IllegalArgumentException on garbage
            Log.w(TAG, "unparsable install referrer: " + e.getMessage());
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> params, String key, @Nullable String value) {
        if (!TextUtils.isEmpty(value)) {
            params.put(key, value);
        }
    }
}
