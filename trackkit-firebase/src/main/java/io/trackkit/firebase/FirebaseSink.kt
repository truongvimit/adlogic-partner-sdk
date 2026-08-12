package io.trackkit.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import io.trackkit.AdImpression
import io.trackkit.Consent
import io.trackkit.ConsentState
import io.trackkit.TrackSink
import io.trackkit.TrackkitEvents

/**
 * Firebase Analytics destination.
 *
 * What it guarantees:
 *
 *  1. **One `ad_impression` per impression.** `Tracker.adRevenue()` already routes every paid
 *     impression through [onEvent] under `ad_impression` — the same name GA4 reserves for its
 *     built-in ad-revenue reporting. This sink enriches that single bundle with the two GA4 param
 *     names Trackkit spells differently and logs nothing from [onAdRevenue]; a second `logEvent`
 *     would double the `Total ad revenue` metric.
 *  2. **The impression's real currency.** Never a hardcoded `"USD"`, and never a made-up FX rate.
 *  3. **No silently dropped params.** GA4 stores only String / long / double, so [onEvent] widens
 *     `Int` and encodes `Boolean` rather than handing the SDK a type it discards without warning.
 *  4. **No work before install.** Every callback no-ops until [onInstall] has supplied a context;
 *     defaults pushed early are replayed once the instance exists.
 *
 * Consent Mode *defaults* are not this module's job — declare
 * `google_analytics_default_allow_analytics_storage` and friends as `meta-data` in the app
 * manifest. [onConsent] only applies the resolved UMP decision on top of them.
 */
class FirebaseSink @JvmOverloads constructor(
    /**
     * When true a denial also flips `setAnalyticsCollectionEnabled(false)`. Set it false to keep
     * pure Consent Mode behaviour, where Firebase still sends consent-less pings for modelling.
     */
    private val collectionFollowsConsent: Boolean = true,
) : TrackSink {

    override val id: String = "firebase"

    @Volatile
    private var analytics: FirebaseAnalytics? = null

    @Volatile
    private var pendingDefaults: Bundle? = null

    override fun onInstall(context: Context) {
        val fa = FirebaseAnalytics.getInstance(context.applicationContext)
        analytics = fa
        pendingDefaults?.let {
            fa.setDefaultEventParameters(it)
            pendingDefaults = null
        }
    }

    override fun onEvent(name: String, params: Map<String, Any?>) {
        val fa = analytics ?: return
        val bundle = toBundle(params)
        if (name == TrackkitEvents.AD_IMPRESSION) addGa4AdAliases(bundle, params)
        fa.logEvent(name, bundle)
    }

    override fun onScreen(name: String, screenClass: String?) {
        val fa = analytics ?: return
        val bundle = Bundle(2)
        bundle.putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
        // Omit rather than send null: Firebase then keeps the class it inferred from the Activity.
        if (screenClass != null) bundle.putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        fa.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
    }

    override fun onUserProperty(key: String, value: String?) {
        analytics?.setUserProperty(key, value)
    }

    override fun onUserId(id: String?) {
        analytics?.setUserId(id)
    }

    override fun onConsent(consent: Consent) {
        val fa = analytics ?: return
        if (consent.analytics == ConsentState.UNKNOWN && consent.ads == ConsentState.UNKNOWN) {
            // Tracker replays this state on every cold start. Forcing DENIED here would suppress
            // first_open even where no UMP form is ever shown, so leave the manifest defaults be.
            return
        }
        val analyticsStatus = statusOf(consent.analytics)
        val adsStatus = statusOf(consent.ads)
        fa.setConsent(
            mapOf(
                FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to analyticsStatus,
                FirebaseAnalytics.ConsentType.AD_STORAGE to adsStatus,
                FirebaseAnalytics.ConsentType.AD_USER_DATA to adsStatus,
                FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to adsStatus,
            )
        )
        if (collectionFollowsConsent) fa.setAnalyticsCollectionEnabled(consent.analyticsGranted)
    }

    /**
     * Deliberately empty. The GA4 `ad_impression` event is emitted once, from [onEvent] — see the
     * class KDoc. Firebase has no dedicated revenue API to call here the way Adjust or Meta do.
     */
    override fun onAdRevenue(impression: AdImpression) = Unit

    /**
     * Attaches [params] to **every** Firebase event, including the ones the SDK collects on its own
     * (`first_open`, `session_start`) and `screen_view`, which Trackkit's own defaults never reach.
     * Pass `null` to clear. Safe to call before install — the values are applied on [onInstall].
     */
    fun setDefaultEventParameters(params: Map<String, Any?>?) {
        val bundle = params?.let { toBundle(it) }
        val fa = analytics
        if (fa == null) {
            pendingDefaults = bundle
            return
        }
        fa.setDefaultEventParameters(bundle)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * GA4 accepts String, long and double only. Anything else — including `Boolean` — is discarded
     * by the SDK without an error, which is precisely the class of silent loss this pipeline is
     * being rewritten to remove. Numbers are widened; booleans become `1` / `0` so they stay
     * countable as a metric; everything else falls back to [toString].
     */
    private fun toBundle(params: Map<String, Any?>): Bundle {
        val bundle = Bundle(params.size)
        for ((key, value) in params) {
            when (value) {
                null -> Unit
                is String -> bundle.putString(key, value)
                is Long, is Int, is Short, is Byte -> bundle.putLong(
                    key,
                    (value as Number).toLong()
                )

                is Double, is Float -> bundle.putDouble(key, (value as Number).toDouble())
                is Boolean -> bundle.putLong(key, if (value) 1L else 0L)
                else -> bundle.putString(key, value.toString())
            }
        }
        return bundle
    }

    /**
     * Trackkit's taxonomy already uses GA4's spelling for `ad_platform`, `ad_format`, `value` and
     * `currency`, so only two aliases are missing from the standard schema: `ad_source` and
     * `ad_unit_name`. The originals stay in the bundle — dashboards built on `ad_unit_id` and
     * `ad_network` keep working.
     */
    private fun addGa4AdAliases(bundle: Bundle, params: Map<String, Any?>) {
        (params[TrackkitEvents.PARAM_AD_NETWORK] as? String)?.let {
            bundle.putString(FirebaseAnalytics.Param.AD_SOURCE, it)
        }
        (params[TrackkitEvents.PARAM_AD_UNIT_ID] as? String)?.let {
            bundle.putString(FirebaseAnalytics.Param.AD_UNIT_NAME, it)
        }
    }

    /** Firebase has no tri-state: an axis still unresolved while the other resolved counts as denied. */
    private fun statusOf(state: ConsentState): FirebaseAnalytics.ConsentStatus =
        if (state == ConsentState.GRANTED) {
            FirebaseAnalytics.ConsentStatus.GRANTED
        } else {
            FirebaseAnalytics.ConsentStatus.DENIED
        }
}
