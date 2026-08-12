package io.trackkit.internal

import android.content.Context
import android.content.SharedPreferences
import io.trackkit.AdImpression
import io.trackkit.TrackEvent
import io.trackkit.TrackkitEvents
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/**
 * GA4 grammar. Breaking any of these makes Firebase drop the event or the param **silently**,
 * which is how `robot_settting_view` and truncated purchase tokens reach production unnoticed.
 */
internal object EventValidator {

    const val MAX_NAME_LENGTH = 40
    const val MAX_PARAM_COUNT = 25
    const val MAX_STRING_VALUE_LENGTH = 100

    /** GA4's user-property VALUE cap — 36 chars, far below the 100 event params get. */
    const val MAX_USER_PROP_VALUE_LENGTH = 36

    private val NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,${MAX_NAME_LENGTH - 1}}$")
    private val RESERVED_PREFIXES = listOf("firebase_", "google_", "ga_")

    /** Param keys that must never carry a value — PII / secrets that dashboards must not store. */
    private val FORBIDDEN_KEYS = setOf(
        "purchase_token", "purchase_token_part_1", "purchase_token_part_2",
        "email", "phone", "device_id", "android_id", "gaid", "idfa", "advertising_id",
    )

    fun validateName(name: String): String? = when {
        !NAME_REGEX.matches(name) ->
            "event name '$name' must match [a-zA-Z][a-zA-Z0-9_]{0,${MAX_NAME_LENGTH - 1}}"

        RESERVED_PREFIXES.any { name.startsWith(it) } ->
            "event name '$name' uses a reserved prefix"

        else -> null
    }

    fun validateParamKey(key: String): String? = when {
        !NAME_REGEX.matches(key) -> "param key '$key' must match the GA4 grammar"
        RESERVED_PREFIXES.any { key.startsWith(it) } -> "param key '$key' uses a reserved prefix"
        key.lowercase() in FORBIDDEN_KEYS -> "param key '$key' is forbidden (PII / secret)"
        else -> null
    }

    /**
     * Drops invalid keys, truncates long strings and caps the param count.
     * Returns the sanitised map plus any problems found, so the caller can decide to throw.
     */
    fun sanitize(params: Map<String, Any?>): Sanitized {
        val problems = mutableListOf<String>()
        val out = LinkedHashMap<String, Any?>(params.size)

        for ((key, value) in params) {
            if (value == null) continue
            val keyProblem = validateParamKey(key)
            if (keyProblem != null) {
                problems += keyProblem
                continue
            }
            if (out.size >= MAX_PARAM_COUNT) {
                problems += "param '$key' dropped, more than $MAX_PARAM_COUNT params"
                continue
            }
            out[key] = when (value) {
                is String -> if (value.length > MAX_STRING_VALUE_LENGTH) {
                    problems += "param '$key' truncated to $MAX_STRING_VALUE_LENGTH chars"
                    value.take(MAX_STRING_VALUE_LENGTH)
                } else {
                    value
                }

                is Number, is Boolean -> value
                is Enum<*> -> value.name.lowercase()
                else -> value.toString().take(MAX_STRING_VALUE_LENGTH)
            }
        }
        return Sanitized(out, problems)
    }

    data class Sanitized(val params: Map<String, Any?>, val problems: List<String>)
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

internal class TrackkitPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** First launch stamps install time; every later call returns the stored value. */
    fun installTimeMs(): Long {
        val stored = prefs.getLong(KEY_INSTALL_TIME, 0L)
        if (stored != 0L) return stored
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_INSTALL_TIME, now).apply()
        return now
    }

    fun nextSessionNumber(): Int {
        val next = prefs.getInt(KEY_SESSION_NO, 0) + 1
        prefs.edit().putInt(KEY_SESSION_NO, next).apply()
        return next
    }

    val totalRevenueMicros: Long
        get() = prefs.getLong(KEY_TOTAL_MICROS, 0L)

    val pendingFlushMicros: Long
        get() = prefs.getLong(KEY_PENDING_MICROS, 0L)

    /** One editor, one apply: both counters always change together, per impression. */
    fun setRevenueCounters(totalMicros: Long, pendingMicros: Long) {
        prefs.edit()
            .putLong(KEY_TOTAL_MICROS, totalMicros)
            .putLong(KEY_PENDING_MICROS, pendingMicros)
            .apply()
    }

    fun isMilestoneSent(days: Int): Boolean = prefs.getBoolean(milestoneKey(days), false)

    fun markMilestoneSent(days: Int) {
        prefs.edit().putBoolean(milestoneKey(days), true).apply()
    }

    private fun milestoneKey(days: Int) = "milestone_d$days"

    private companion object {
        const val FILE = "trackkit_prefs"
        const val KEY_INSTALL_TIME = "install_time"
        const val KEY_SESSION_NO = "session_no"
        const val KEY_TOTAL_MICROS = "total_revenue_micros"
        const val KEY_PENDING_MICROS = "pending_flush_micros"
    }
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/**
 * Values attached to every event so any funnel can be sliced by cohort without a join.
 * The audited Apero build had none of these — no user property, no user id, no default params —
 * which is why its A/B variants were unattributable.
 */
internal class SessionState(prefs: TrackkitPrefs) {

    val installTimeMs: Long = prefs.installTimeMs()
    val sessionNumber: Int = prefs.nextSessionNumber()
    val sessionId: String = "s${installTimeMs % 100_000}_$sessionNumber"

    /** Bucketed rather than raw so it stays inside GA4's 50-custom-dimension budget. */
    fun installDayBucket(nowMs: Long = System.currentTimeMillis()): String {
        val days = ((nowMs - installTimeMs) / DAY_MS).toInt()
        return when {
            days <= 0 -> "d0"
            days == 1 -> "d1"
            days == 2 -> "d2"
            days <= 3 -> "d3"
            days <= 7 -> "d7"
            days <= 14 -> "d14"
            days <= 30 -> "d30"
            else -> "d30plus"
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------------------
// Ad revenue accumulator
// ---------------------------------------------------------------------------

/**
 * The one genuinely valuable piece of the old Apero pipeline, reimplemented with correct units.
 *
 * Emits, per impression:
 *  - `ad_revenue_total`      cumulative LTV so far
 *  - `ad_revenue_micro_flush` once the un-flushed bucket crosses 0.01 of the reporting currency
 *  - `ad_revenue_d3` / `ad_revenue_d7` one-shot milestones
 *
 * These are the signals that feed Meta VO/AEO and Adjust ROAS models. Everything is kept in
 * **micros (Long)**; the old code mixed micros and units in the same variable.
 */
internal class AdRevenueAccumulator(
    private val prefs: TrackkitPrefs,
    private val session: SessionState,
    private val reportingCurrency: String,
) {

    /**
     * Synchronized: paid-event callbacks are not guaranteed onto one thread, and the counters are
     * read-modify-write — two concurrent impressions could each read the same total and one
     * impression's revenue would vanish from the LTV figure.
     */
    @Synchronized
    fun record(impression: AdImpression, emit: (name: String, params: Map<String, Any?>) -> Unit) {
        if (!impression.currency.equals(reportingCurrency, ignoreCase = true)) {
            // Summing mixed currencies would produce a meaningless total, so skip the accumulator
            // but let the raw impression events through untouched.
            return
        }

        val total = prefs.totalRevenueMicros + impression.valueMicros
        val pending = prefs.pendingFlushMicros + impression.valueMicros
        val flush = pending >= FLUSH_THRESHOLD_MICROS
        prefs.setRevenueCounters(total, if (flush) 0 else pending)

        emit(EVENT_TOTAL, mapOf("value" to total / 1_000_000.0, "currency" to reportingCurrency))
        if (flush) {
            emit(
                EVENT_FLUSH,
                mapOf("value" to pending / 1_000_000.0, "currency" to reportingCurrency),
            )
        }

        emitMilestoneIfDue(3, total, emit)
        emitMilestoneIfDue(7, total, emit)
    }

    private fun emitMilestoneIfDue(
        days: Int,
        totalMicros: Long,
        emit: (String, Map<String, Any?>) -> Unit,
    ) {
        if (prefs.isMilestoneSent(days)) return
        val elapsed = System.currentTimeMillis() - session.installTimeMs
        if (elapsed < days * DAY_MS) return
        prefs.markMilestoneSent(days)
        emit(
            "ad_revenue_d$days",
            mapOf("value" to totalMicros / 1_000_000.0, "currency" to reportingCurrency),
        )
    }

    companion object {
        val EVENT_TOTAL = TrackkitEvents.AD_REVENUE_TOTAL
        val EVENT_FLUSH = TrackkitEvents.AD_REVENUE_MICRO_FLUSH

        /** 0.01 of the reporting currency, expressed in micros. */
        const val FLUSH_THRESHOLD_MICROS = 10_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------------------
// Pending buffer
// ---------------------------------------------------------------------------

/**
 * Holds events tracked before `Tracker.install()` (or while consent is unresolved) instead of
 * dropping them on the floor. The audited SDK guarded every log with `if (analytics != null)` and
 * no `else`, so forgetting to call `init()` lost the whole funnel with no signal at all.
 */
internal class PendingBuffer(private val capacity: Int = 128) {

    private val items = ArrayDeque<Pending>()
    private val lock = Any()
    private val dropped = AtomicLong(0)

    fun add(pending: Pending) {
        synchronized(lock) {
            if (items.size >= capacity) {
                items.removeFirst()
                dropped.incrementAndGet()
            }
            items.addLast(pending)
        }
    }

    fun drain(): List<Pending> = synchronized(lock) {
        val copy = items.toList()
        items.clear()
        copy
    }

    fun droppedCount(): Long = dropped.get()

    sealed interface Pending {
        data class Event(val event: TrackEvent) : Pending
        data class Screen(val name: String, val screenClass: String?) : Pending
        data class Revenue(val impression: AdImpression) : Pending
        data class UserProperty(val key: String, val value: String?) : Pending
        data class UserId(val id: String?) : Pending
    }
}
