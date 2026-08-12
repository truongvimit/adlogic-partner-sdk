package io.trackkit

/**
 * Public contract of Trackkit.
 *
 * The core module deliberately has **no vendor dependency**. Firebase / Adjust / Meta live in
 * separate sink modules, so `:ads` and `:onboardkitorigin` can depend on this contract without
 * dragging an analytics vendor into every partner build.
 */

// ---------------------------------------------------------------------------
// Event
// ---------------------------------------------------------------------------

/**
 * One tracked event. Implementations are expected to be immutable value types.
 *
 * [name] and every key in [params] are validated against the GA4 grammar before dispatch —
 * a typo like `iap_successfull` fails a unit test instead of reaching production.
 */
interface TrackEvent {
    val name: String
    val params: Map<String, Any?> get() = emptyMap()

    /** When true the event is emitted at most once per app session. */
    val oncePerSession: Boolean get() = false

    /** Optional dedupe key; defaults to [name]. Use when the same name is legitimately re-emitted. */
    val dedupeKey: String? get() = null
}

/** Escape hatch for app-specific events that do not warrant a catalog entry. */
open class SimpleEvent(
    override val name: String,
    override val params: Map<String, Any?> = emptyMap(),
    override val oncePerSession: Boolean = false,
) : TrackEvent

// ---------------------------------------------------------------------------
// Ad revenue — a dedicated channel, not a normal event
// ---------------------------------------------------------------------------

/**
 * Ad formats, normalised across mediation providers.
 *
 * [key] is what lands in the `ad_format` param — keep the values stable, dashboards depend on them.
 */
enum class AdFormat(val key: String) {
    BANNER("banner"),
    COLLAPSIBLE_BANNER("collapsible_banner"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    REWARDED_INTERSTITIAL("rewarded_interstitial"),
    NATIVE("native"),
    NATIVE_FULL_SCREEN("native_full_screen"),
    APP_OPEN("app_open"),
    UNKNOWN("unknown");

    companion object {
        @JvmStatic
        fun fromKey(key: String?): AdFormat =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * A single paid ad impression.
 *
 * Note [valueMicros] is expressed in [currency] — **not** always USD. The old wrapper hardcoded
 * `"USD"` into the bundle while reading a real currency code from the ad value, which silently
 * mislabels revenue for non-USD accounts. Sinks must use [currency] as given.
 */
data class AdImpression @JvmOverloads constructor(
    val placement: String,
    val format: AdFormat,
    val adUnitId: String,
    /** `admob` or `max`. */
    val adPlatform: String,
    /** Mediation network / adapter that filled the impression, when the SDK reports one. */
    val network: String? = null,
    val valueMicros: Long,
    val currency: String,
    /** AdMob precision type; `0` when the provider does not report one. */
    val precision: Int = 0,
) {
    val value: Double get() = valueMicros / 1_000_000.0

    companion object {
        const val PLATFORM_ADMOB = "admob"
        const val PLATFORM_MAX = "max"
    }
}

// ---------------------------------------------------------------------------
// Consent
// ---------------------------------------------------------------------------

/** Tri-state consent. `UNKNOWN` means the UMP form has not resolved yet. */
enum class ConsentState { UNKNOWN, GRANTED, DENIED }

data class Consent(
    val analytics: ConsentState = ConsentState.UNKNOWN,
    val ads: ConsentState = ConsentState.UNKNOWN,
) {
    val analyticsGranted: Boolean get() = analytics == ConsentState.GRANTED
    val adsGranted: Boolean get() = ads == ConsentState.GRANTED
}

/** What Trackkit does with events while consent is still [ConsentState.UNKNOWN]. */
enum class ConsentPolicy {
    /** Buffer until consent resolves, then flush or drop. Safest default for EEA traffic. */
    QUEUE_UNTIL_RESOLVED,

    /** Dispatch immediately; consent only toggles vendor collection flags afterwards. */
    SEND_ALWAYS,

    /** Drop silently until analytics consent is granted. */
    DROP_UNTIL_GRANTED,
}

// ---------------------------------------------------------------------------
// Sink
// ---------------------------------------------------------------------------

/**
 * A destination. One sink per vendor.
 *
 * Every callback is invoked on the caller's thread and must not block. Trackkit already wraps
 * each call in `runCatching`, so a throwing sink can not take down the others — but a sink that
 * blocks will stall the caller.
 */
interface TrackSink {
    /** Stable id, used in logs and to prevent double-registration. */
    val id: String

    fun onInstall(context: android.content.Context) {}

    fun onEvent(name: String, params: Map<String, Any?>)

    fun onScreen(name: String, screenClass: String?) {}

    fun onUserProperty(key: String, value: String?) {}

    fun onUserId(id: String?) {}

    /** Called on every consent change, including the initial `UNKNOWN` state at install time. */
    fun onConsent(consent: Consent) {}

    /**
     * Paid impression. Sinks that need the vendor's dedicated revenue API (Adjust
     * `AdjustAdRevenue`, Meta purchase logging) implement this; the rest can ignore it and rely on
     * the standard revenue events Trackkit emits through [onEvent].
     */
    fun onAdRevenue(impression: AdImpression) {}
}

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

/**
 * @param appVersionCode attached to every event as `app_vc`
 * @param sdkVersion attached as `sdk_ver`
 * @param reportingCurrency currency the cumulative-revenue events are reported in. Impressions in
 *        a different currency are still forwarded to sinks but are excluded from the accumulator,
 *        because summing mixed currencies produces a meaningless LTV figure.
 * @param strictValidation when true an invalid event name/param throws instead of being sanitised.
 *        Wire it to `BuildConfig.DEBUG` so taxonomy mistakes fail in QA, never in production.
 * @param logLevel `0` off, `1` warnings, `2` verbose.
 */
data class TrackerConfig @JvmOverloads constructor(
    val appVersionCode: Long = 0L,
    val sdkVersion: String = BuildConfigCompat.SDK_VERSION,
    val reportingCurrency: String = "USD",
    val consentPolicy: ConsentPolicy = ConsentPolicy.SEND_ALWAYS,
    val strictValidation: Boolean = false,
    val logLevel: Int = 1,
    /** Emit the cumulative ad-LTV events (`ad_revenue_total`, D3/D7 milestones, $0.01 flush). */
    val enableRevenueAccumulator: Boolean = true,
    /** Extra params merged into every event. Same effect as calling `Tracker.setDefaults`. */
    val defaultParams: Map<String, Any?> = emptyMap(),
)

internal object BuildConfigCompat {
    const val SDK_VERSION: String = "1.0.0"
}
