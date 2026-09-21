package com.ads.module.event

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import com.adjust.sdk.Adjust
import com.adjust.sdk.GooglePlayInstallReferrerDetails
import com.adjust.sdk.OnGooglePlayInstallReferrerReadListener
import com.ads.module.util.SharePreferenceUtils
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents

/**
 * Reads the Google Play install referrer through Adjust and reports it once per install.
 *
 * Adjust already fetches the referrer for its own attribution; this only mirrors it into
 * analytics so a Firebase-side funnel can be split by acquisition channel without a join against
 * the MMP. It is read exactly once — the guard is persisted, because the callback also fires on
 * every cold start and the referrer never changes.
 *
 * The raw referrer string is deliberately not sent. It is a URL-encoded query that routinely
 * runs past GA4's 100-character param limit, so it would arrive truncated in the middle of a
 * campaign name; the `utm_*` fields are parsed out instead.
 */
object AdjustInstallReferrer {

    private const val TAG = "ERainAdjust"

    @JvmStatic
    fun readOnce(context: Context) {
        val appContext = context.applicationContext
        if (SharePreferenceUtils.isInstallReferrerTracked(appContext)) {
            return
        }
        Adjust.getGooglePlayInstallReferrer(appContext, object : OnGooglePlayInstallReferrerReadListener {
            override fun onInstallReferrerRead(details: GooglePlayInstallReferrerDetails?) {
                if (details == null) {
                    return
                }
                SharePreferenceUtils.setInstallReferrerTracked(appContext)
                Tracker.track(TrackkitEvents.APP_INSTALL_REFERRER, parse(details))
            }

            override fun onFail(message: String?) {
                // No mark: a failure here is usually "Play Store not ready yet", which the next
                // cold start resolves. Marking it done would lose the referrer permanently.
                Log.w(TAG, "install referrer unavailable: " + message)
            }
        })
    }

    private fun parse(details: GooglePlayInstallReferrerDetails): Map<String, Any?> {
        val params: MutableMap<String, Any?> = HashMap(6)
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_SOURCE, param(details.installReferrer, "utm_source"))
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_MEDIUM, param(details.installReferrer, "utm_medium"))
        putIfPresent(params, TrackkitEvents.PARAM_REFERRER_CAMPAIGN, param(details.installReferrer, "utm_campaign"))
        putIfPresent(params, "install_version", details.installVersion)
        params["is_instant"] = details.googlePlayInstant == true
        return params
    }

    /**
     * The referrer is a bare query string (`utm_source=x&utm_medium=y`), so it is parsed as
     * the query of a synthetic URI rather than split by hand — that is what decodes the escapes.
     */
    private fun param(referrer: String?, key: String): String? {
        if (TextUtils.isEmpty(referrer)) {
            return null
        }
        return try {
            Uri.parse("https://x/?" + referrer).getQueryParameter(key)
        } catch (e: RuntimeException) {
            // UnsupportedOperationException on an opaque URI, IllegalArgumentException on garbage
            Log.w(TAG, "unparsable install referrer: " + e.message)
            null
        }
    }

    private fun putIfPresent(params: MutableMap<String, Any?>, key: String, value: String?) {
        if (!TextUtils.isEmpty(value)) {
            params[key] = value
        }
    }
}
