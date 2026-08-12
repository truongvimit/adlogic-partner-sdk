package io.trackkit

import android.content.Context
import android.util.Log
import io.trackkit.internal.AdRevenueAccumulator
import io.trackkit.internal.EventValidator
import io.trackkit.internal.PendingBuffer
import io.trackkit.internal.SessionState
import io.trackkit.internal.TrackkitPrefs
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single fan-out point for analytics.
 *
 * Everything the app, the ads SDK and the onboarding SDK emit goes through here, which is what
 * makes it possible to add consent gating, default params, validation and dedupe **once** instead
 * of in nine unrelated wrappers.
 *
 * Typical wiring in `Application.onCreate`:
 * ```
 * Tracker.install(this, TrackerConfig(
 *     appVersionCode  = BuildConfig.VERSION_CODE.toLong(),
 *     strictValidation = BuildConfig.DEBUG,
 * ))
 * Tracker.addSink(
 *     FirebaseSink(),
 *     // Adjust is not a sink: the MMP lives in :ads. See ARCHITECTURE.md.
 * )
 * ```
 */
object Tracker {

    private const val TAG = "Trackkit"

    private val sinks = CopyOnWriteArrayList<TrackSink>()
    private val defaults = Collections.synchronizedMap(LinkedHashMap<String, Any?>())
    private val emittedOnce = Collections.synchronizedSet(HashSet<String>())
    private val pending = PendingBuffer()
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var config: TrackerConfig = TrackerConfig()
    @Volatile
    private var prefs: TrackkitPrefs? = null
    @Volatile
    private var session: SessionState? = null
    @Volatile
    private var accumulator: AdRevenueAccumulator? = null
    @Volatile
    private var consent: Consent = Consent()

    /** True once [install] has run. Events tracked before that are buffered, never dropped. */
    val isInstalled: Boolean get() = appContext != null

    val currentConsent: Consent get() = consent

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @JvmStatic
    @JvmOverloads
    fun install(context: Context, config: TrackerConfig = TrackerConfig()) {
        synchronized(lock) {
            if (isInstalled) {
                warn("install() called twice — ignoring the second call")
                return
            }
            val app = context.applicationContext
            val store = TrackkitPrefs(app)
            val state = SessionState(store)

            this.config = config
            prefs = store
            session = state
            accumulator = AdRevenueAccumulator(store, state, config.reportingCurrency)

            defaults.putAll(config.defaultParams)
            defaults[PARAM_APP_VC] = config.appVersionCode
            defaults[PARAM_SDK_VER] = config.sdkVersion
            defaults[PARAM_SESSION_NO] = state.sessionNumber

            sinks.forEach { sink -> safely(sink) { it.onInstall(app) } }
            sinks.forEach { sink -> safely(sink) { it.onConsent(consent) } }

            // Published LAST: isInstalled reads this volatile without the lock, so flipping it
            // before defaults/session/sinks are ready would let a racing track() dispatch into
            // half-initialised state (and a sink that ignores onInstall would silently drop it).
            appContext = app
        }
        flushPending()
        verbose { "installed — session=${session?.sessionId} sinks=${sinks.map { it.id }}" }
    }

    /** Registers destinations. Safe to call before or after [install]; duplicate ids are ignored. */
    @JvmStatic
    fun addSink(vararg newSinks: TrackSink) {
        // Same lock as install(): a sink registered while install() is mid-flight must either see
        // the not-installed state (and be onInstall-ed by install itself) or the fully built one.
        synchronized(lock) {
            newSinks.forEach { sink ->
                if (sinks.any { it.id == sink.id }) {
                    warn("sink '${sink.id}' already registered — ignoring")
                    return@forEach
                }
                sinks.add(sink)
                appContext?.let { ctx ->
                    safely(sink) { it.onInstall(ctx) }
                    safely(sink) { it.onConsent(consent) }
                }
            }
        }
        if (isInstalled) flushPending()
    }

    @JvmStatic
    fun removeSink(sink: TrackSink) {
        sinks.remove(sink)
    }

    @JvmStatic
    fun sinkIds(): List<String> = sinks.map { it.id }

    // -----------------------------------------------------------------------
    // Defaults / identity
    // -----------------------------------------------------------------------

    /** Params merged into **every** subsequent event. Keep it under ~6 keys: they eat the 25-param budget. */
    @JvmStatic
    fun setDefaults(vararg pairs: Pair<String, Any?>) {
        pairs.forEach { (key, value) -> setDefault(key, value) }
    }

    @JvmStatic
    fun setDefault(key: String, value: Any?) {
        val problem = EventValidator.validateParamKey(key)
        if (problem != null) {
            fail(problem)
            return
        }
        if (value == null) defaults.remove(key) else defaults[key] = value
    }

    @JvmStatic
    fun setUserProperty(key: String, value: String?) {
        val problem = EventValidator.validateParamKey(key)
        if (problem != null) {
            fail(problem)
            return
        }
        // GA4 caps user-property VALUES at 36 chars (not the 100 events get) and truncates
        // silently server-side; trimming here, before buffering, makes the cut visible in QA.
        val trimmed = value?.take(EventValidator.MAX_USER_PROP_VALUE_LENGTH)
        if (trimmed != null && trimmed.length < (value?.length ?: 0)) {
            warn("user property '$key' truncated to ${EventValidator.MAX_USER_PROP_VALUE_LENGTH} chars")
        }
        if (!isInstalled) {
            pending.add(PendingBuffer.Pending.UserProperty(key, trimmed))
            if (isInstalled) flushPending()
            return
        }
        sinks.forEach { sink -> safely(sink) { it.onUserProperty(key, trimmed) } }
    }

    @JvmStatic
    fun setUserId(id: String?) {
        if (!isInstalled) {
            pending.add(PendingBuffer.Pending.UserId(id))
            if (isInstalled) flushPending()
            return
        }
        sinks.forEach { sink -> safely(sink) { it.onUserId(id) } }
    }

    // -----------------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------------

    /**
     * Call this the moment UMP resolves — and only from there. Sinks translate it into their own
     * vendor switch (Firebase Consent Mode, `Adjust.trackMeasurementConsent`, Meta limited data use).
     */
    @JvmStatic
    fun setConsent(analytics: Boolean, ads: Boolean) {
        setConsent(
            Consent(
                analytics = if (analytics) ConsentState.GRANTED else ConsentState.DENIED,
                ads = if (ads) ConsentState.GRANTED else ConsentState.DENIED,
            )
        )
    }

    @JvmStatic
    fun setConsent(newConsent: Consent) {
        consent = newConsent
        defaults[PARAM_CONSENT_ADS] = if (newConsent.adsGranted) 1 else 0
        sinks.forEach { sink -> safely(sink) { it.onConsent(newConsent) } }
        if (isInstalled) flushPending()
    }

    // -----------------------------------------------------------------------
    // Tracking
    // -----------------------------------------------------------------------

    @JvmStatic
    fun track(event: TrackEvent) {
        // Before the once-per-session mark: a dropped event must not poison a later legitimate one
        if (shouldDropNow()) {
            verbose { "dropped '${event.name}' (DROP_UNTIL_GRANTED, consent not granted)" }
            return
        }
        if (event.oncePerSession) {
            val key = event.dedupeKey ?: event.name
            if (!emittedOnce.add(key)) {
                verbose { "skipped once-per-session event '${event.name}'" }
                return
            }
        }
        if (!canDispatchNow()) {
            pending.add(PendingBuffer.Pending.Event(event))
            // The add can race a concurrent flush that already drained: re-check so nothing
            // strands in the buffer until the next unrelated flush trigger.
            if (canDispatchNow()) flushPending()
            return
        }
        dispatch(event.name, event.params)
    }

    @JvmStatic
    @JvmOverloads
    fun track(name: String, params: Map<String, Any?> = emptyMap()) {
        track(SimpleEvent(name, params))
    }

    @JvmStatic
    @JvmOverloads
    fun screen(name: String, screenClass: String? = null) {
        if (shouldDropNow()) return
        // Trimmed before buffering so the flushed replay is byte-identical to the direct path
        val trimmed = name.take(EventValidator.MAX_STRING_VALUE_LENGTH)
        if (!canDispatchNow()) {
            pending.add(PendingBuffer.Pending.Screen(trimmed, screenClass))
            if (canDispatchNow()) flushPending()
            return
        }
        sinks.forEach { sink -> safely(sink) { it.onScreen(trimmed, screenClass) } }
    }

    /**
     * Paid impression. Fans out to three things at once: the raw impression event, the sink's own
     * revenue API, and the cumulative LTV events — the shape the old pipeline got right and the one
     * worth keeping.
     */
    @JvmStatic
    fun adRevenue(impression: AdImpression) {
        if (shouldDropNow()) return
        if (!canDispatchNow()) {
            pending.add(PendingBuffer.Pending.Revenue(impression))
            if (canDispatchNow()) flushPending()
            return
        }
        dispatchRevenue(impression)
    }

    /** Flushes anything buffered before install / before consent resolved. */
    @JvmStatic
    fun flushPending() {
        if (!canDispatchNow()) return
        val drained = pending.drain()
        if (drained.isEmpty()) return
        verbose { "flushing ${drained.size} buffered items" }
        drained.forEach { item ->
            when (item) {
                is PendingBuffer.Pending.Event -> dispatch(item.event.name, item.event.params)
                is PendingBuffer.Pending.Screen -> sinks.forEach { sink ->
                    safely(sink) { it.onScreen(item.name, item.screenClass) }
                }

                is PendingBuffer.Pending.Revenue -> dispatchRevenue(item.impression)
                is PendingBuffer.Pending.UserProperty -> sinks.forEach { sink ->
                    safely(sink) { it.onUserProperty(item.key, item.value) }
                }

                is PendingBuffer.Pending.UserId -> sinks.forEach { sink ->
                    safely(sink) { it.onUserId(item.id) }
                }
            }
        }
        if (pending.droppedCount() > 0) {
            warn("${pending.droppedCount()} events were dropped before install (buffer overflow)")
        }
    }

    /** Test hook — clears all state. */
    @JvmStatic
    fun resetForTesting() {
        synchronized(lock) {
            sinks.clear()
            defaults.clear()
            emittedOnce.clear()
            pending.drain()
            appContext = null
            prefs = null
            session = null
            accumulator = null
            consent = Consent()
            config = TrackerConfig()
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun canDispatchNow(): Boolean {
        if (!isInstalled) return false
        return when (config.consentPolicy) {
            ConsentPolicy.SEND_ALWAYS -> true
            ConsentPolicy.QUEUE_UNTIL_RESOLVED -> consent.analytics != ConsentState.UNKNOWN
            ConsentPolicy.DROP_UNTIL_GRANTED -> consent.analyticsGranted
        }
    }

    /**
     * DROP means drop. Without this, a pre-grant event fell into the pending buffer and was
     * retro-delivered the moment consent arrived — exactly the behaviour the policy promises not
     * to have. Pre-install buffering is untouched: the policy is unknown until [install].
     */
    private fun shouldDropNow(): Boolean =
        isInstalled &&
                config.consentPolicy == ConsentPolicy.DROP_UNTIL_GRANTED &&
                !consent.analyticsGranted

    private fun dispatch(rawName: String, rawParams: Map<String, Any?>) {
        val nameProblem = EventValidator.validateName(rawName)
        if (nameProblem != null) {
            fail(nameProblem)
            return
        }
        val merged = LinkedHashMap<String, Any?>()
        synchronized(defaults) { merged.putAll(defaults) }
        session?.let { merged[PARAM_INSTALL_DAY] = it.installDayBucket() }
        merged.putAll(rawParams)

        val sanitized = EventValidator.sanitize(merged)
        sanitized.problems.forEach { problem -> fail("$rawName: $problem") }

        verbose { "$rawName ${sanitized.params}" }
        sinks.forEach { sink -> safely(sink) { it.onEvent(rawName, sanitized.params) } }
    }

    private fun dispatchRevenue(impression: AdImpression) {
        dispatch(
            TrackkitEvents.AD_IMPRESSION,
            mapOf(
                TrackkitEvents.PARAM_PLACEMENT to impression.placement,
                TrackkitEvents.PARAM_AD_FORMAT to impression.format.key,
                TrackkitEvents.PARAM_AD_UNIT_ID to impression.adUnitId,
                TrackkitEvents.PARAM_AD_PLATFORM to impression.adPlatform,
                TrackkitEvents.PARAM_AD_NETWORK to impression.network,
                TrackkitEvents.PARAM_VALUE to impression.value,
                TrackkitEvents.PARAM_CURRENCY to impression.currency,
                TrackkitEvents.PARAM_PRECISION to impression.precision,
            )
        )
        sinks.forEach { sink -> safely(sink) { it.onAdRevenue(impression) } }
        if (config.enableRevenueAccumulator) {
            accumulator?.record(impression) { name, params -> dispatch(name, params) }
        }
    }

    private inline fun safely(sink: TrackSink, block: (TrackSink) -> Unit) {
        try {
            block(sink)
        } catch (t: Throwable) {
            warn("sink '${sink.id}' threw: ${t.message}")
        }
    }

    /** Strict mode turns taxonomy mistakes into test failures; production only logs. */
    private fun fail(message: String) {
        if (config.strictValidation) throw IllegalArgumentException("Trackkit: $message")
        warn(message)
    }

    private fun warn(message: String) {
        if (config.logLevel >= 1) Log.w(TAG, message)
    }

    /** Lazy on purpose: the message is a string-built param dump, wasted work when logging is off. */
    private inline fun verbose(message: () -> String) {
        if (config.logLevel >= 2) Log.d(TAG, message())
    }

    // Default param keys — kept here so sinks and tests reference one constant.
    const val PARAM_APP_VC = "app_vc"
    const val PARAM_SDK_VER = "sdk_ver"
    const val PARAM_SESSION_NO = "session_no"
    const val PARAM_INSTALL_DAY = "install_day"
    const val PARAM_CONSENT_ADS = "consent_ads"
    const val PARAM_AB_VARIANT = "ab_variant"
    const val PARAM_USER_TYPE = "user_type"
}
