package io.retentionkit.core

import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** REUSABLE is for widget/launcher surfaces; each delivered Intent gets a fresh consumption token. */
enum class RetentionEntryMode { ONCE, REUSABLE }
enum class RetentionEntrySource { DAILY, WINBACK, ONBOARDING_ABANDONMENT, AD_RETURN, REMINDER, PINNED, LOCKSCREEN, WIDGET, SHORTCUT, FEEDBACK, OTHER }

data class RetentionEntry @JvmOverloads constructor(
    val source: RetentionEntrySource,
    val destination: String,
    val actionId: String,
    val token: String = UUID.randomUUID().toString(),
    val campaignId: String? = null,
    val instanceId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long? = null,
    val mode: RetentionEntryMode = RetentionEntryMode.ONCE,
)

sealed class RetentionEntryDecodeResult {
    data class Valid(val entry: RetentionEntry) : RetentionEntryDecodeResult()
    data class Invalid(val reason: String) : RetentionEntryDecodeResult()
    data object Absent : RetentionEntryDecodeResult()
}

sealed class RetentionEntryAcceptance {
    data class Accepted(val entry: RetentionEntry) : RetentionEntryAcceptance()
    data class Rejected(val reason: String) : RetentionEntryAcceptance()
    data object Absent : RetentionEntryAcceptance()
}

/** Versioned string envelope: safe across process death/minification, without custom Parcelable. */
object RetentionEntryCodec {
    const val EXTRA_ENTRY = "io.retentionkit.entry.v1"
    private const val CATEGORY_PREFIX = "io.retentionkit.identity."

    @JvmStatic fun validate(entry: RetentionEntry): String? = when {
        !validId(entry.destination) -> "invalid_destination"
        !validId(entry.actionId) -> "invalid_action"
        !validId(entry.token) -> "invalid_token"
        entry.campaignId != null && !validId(entry.campaignId) -> "invalid_campaign"
        entry.instanceId != null && !validId(entry.instanceId) -> "invalid_instance"
        entry.createdAtMillis < 0 -> "invalid_created_at"
        entry.expiresAtMillis != null && entry.expiresAtMillis <= entry.createdAtMillis -> "invalid_expiration"
        else -> null
    }

    @JvmStatic fun encode(entry: RetentionEntry): String {
        require(validate(entry) == null) { validate(entry) ?: "invalid_entry" }
        return JSONObject().apply {
            put("version", 1)
            put("source", entry.source.name)
            put("destination", entry.destination)
            put("action", entry.actionId)
            put("token", entry.token)
            put("campaign", entry.campaignId)
            put("instance", entry.instanceId)
            put("created", entry.createdAtMillis)
            put("expires", entry.expiresAtMillis)
            put("mode", entry.mode.name)
        }.toString()
    }

    @JvmStatic fun decode(raw: String?): RetentionEntryDecodeResult {
        if (raw == null) return RetentionEntryDecodeResult.Absent
        if (raw.length > 8192) return RetentionEntryDecodeResult.Invalid("oversized_envelope")
        return try {
            val json = JSONObject(raw)
            if (json.getInt("version") != 1) return RetentionEntryDecodeResult.Invalid("unsupported_version")
            val entry = RetentionEntry(
                source = RetentionEntrySource.valueOf(json.getString("source")),
                destination = json.getString("destination"),
                actionId = json.getString("action"),
                token = json.getString("token"),
                campaignId = if (json.has("campaign")) json.getString("campaign") else null,
                instanceId = if (json.has("instance")) json.getString("instance") else null,
                createdAtMillis = json.getLong("created"),
                expiresAtMillis = if (json.has("expires")) json.getLong("expires") else null,
                mode = RetentionEntryMode.valueOf(json.getString("mode")),
            )
            val failure = validate(entry)
            if (failure == null) RetentionEntryDecodeResult.Valid(entry) else RetentionEntryDecodeResult.Invalid(failure)
        } catch (_: Exception) {
            RetentionEntryDecodeResult.Invalid("malformed_envelope")
        }
    }

    @JvmStatic fun read(intent: Intent?): RetentionEntryDecodeResult = try {
        decode(intent?.getStringExtra(EXTRA_ENTRY))
    } catch (_: Exception) {
        RetentionEntryDecodeResult.Invalid("unreadable_extra")
    }

    /** Adds filter identity in categories, preserving the host's existing data URI and flags. */
    @JvmStatic fun write(intent: Intent, entry: RetentionEntry): Intent = intent.apply {
        putExtra(EXTRA_ENTRY, encode(entry))
        categories?.filter { it.startsWith(CATEGORY_PREFIX) }?.forEach { removeCategory(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(identityUri(entry).toString().toByteArray(Charsets.UTF_8))
        addCategory(CATEGORY_PREFIX + digest.joinToString("") { "%02x".format(it) })
    }

    @JvmStatic fun identityUri(entry: RetentionEntry): Uri = Uri.Builder().scheme("retentionkit").authority("entry")
        .appendPath(entry.source.name).appendPath(entry.campaignId ?: "_")
        .appendPath(entry.actionId).appendPath(entry.destination).appendPath(entry.instanceId ?: "_")
        .appendPath(if (entry.mode == RetentionEntryMode.REUSABLE) "reusable" else entry.token).build()
}

/** Durable handoff through splash/setup; consumption happens only at the final destination. */
class RetentionEntries internal constructor(private val store: RetentionStore, private val clock: RetentionClock) {
    fun capture(intent: Intent?): RetentionEntryAcceptance {
        return when (val decoded = RetentionEntryCodec.read(intent)) {
            RetentionEntryDecodeResult.Absent -> RetentionEntryAcceptance.Absent
            is RetentionEntryDecodeResult.Invalid -> RetentionEntryAcceptance.Rejected(decoded.reason)
            is RetentionEntryDecodeResult.Valid -> {
                val original = decoded.entry
                val entry = if (original.mode == RetentionEntryMode.REUSABLE) {
                    original.copy(token = UUID.randomUUID().toString(), createdAtMillis = clock.wallTimeMillis(), mode = RetentionEntryMode.ONCE)
                } else original
                // Mutate the delivered Activity Intent, so recreation/repeated capture of this launch
                // keeps the same token. The OS-owned reusable PendingIntent remains a template.
                if (intent != null && entry !== original) RetentionEntryCodec.write(intent, entry)
                stage(entry)
            }
        }
    }

    fun stage(entry: RetentionEntry): RetentionEntryAcceptance {
        RetentionEntryCodec.validate(entry)?.let { return RetentionEntryAcceptance.Rejected(it) }
        if (entry.mode != RetentionEntryMode.ONCE) return RetentionEntryAcceptance.Rejected("capture_reusable_intent_first")
        return store.transaction(NAMESPACE) { state ->
            prune(state)
            when {
                expired(entry) -> RetentionEntryAcceptance.Rejected("expired")
                state.string("consumed:${entry.token}") != null -> RetentionEntryAcceptance.Rejected("already_consumed")
                state.string("pending:${entry.token}") == null && state.entries().keys.count { it.startsWith("pending:") } >= MAX_PENDING -> RetentionEntryAcceptance.Rejected("pending_capacity")
                else -> {
                    val previous = state.string("pending:${entry.token}")
                    val encoded = RetentionEntryCodec.encode(entry)
                    if (previous != null && previous != encoded) RetentionEntryAcceptance.Rejected("token_collision")
                    else {
                        state.put("pending:${entry.token}", encoded)
                        RetentionEntryAcceptance.Accepted(entry)
                    }
                }
            }
        }
    }

    fun pending(token: String): RetentionEntry? = pending().firstOrNull { it.token == token }
    fun pending(): List<RetentionEntry> = store.transaction(NAMESPACE) { state ->
        prune(state)
        state.entries().filterKeys { it.startsWith("pending:") }.values.mapNotNull {
            (RetentionEntryCodec.decode(it) as? RetentionEntryDecodeResult.Valid)?.entry
        }.sortedBy { it.createdAtMillis }
    }

    /** false for unknown, expired or consumed tokens; caller must navigate only when true. */
    fun consume(token: String): Boolean = store.transaction(NAMESPACE) { state ->
        prune(state)
        if (state.string("pending:$token") == null || state.string("consumed:$token") != null) false
        else {
            state.remove("pending:$token")
            state.put("consumed:$token", clock.wallTimeMillis())
            true
        }
    }

    private fun expired(entry: RetentionEntry): Boolean {
        val now = clock.wallTimeMillis()
        return (entry.expiresAtMillis != null && now >= entry.expiresAtMillis) ||
            (now >= entry.createdAtMillis && now - entry.createdAtMillis >= RETENTION_MILLIS)
    }

    private fun prune(state: RetentionTransaction) {
        val now = clock.wallTimeMillis()
        state.entries().forEach { (key, value) ->
            if (key.startsWith("pending:")) {
                val entry = (RetentionEntryCodec.decode(value) as? RetentionEntryDecodeResult.Valid)?.entry
                if (entry == null || expired(entry)) state.remove(key)
            } else if (key.startsWith("consumed:")) {
                val time = value.toLongOrNull()
                if (time == null || (now >= time && now - time >= RETENTION_MILLIS)) state.remove(key)
            }
        }
        // Fail closed at capacity: callers retain pending entries and cannot stage unlimited tokens.
        // Consumed keys are bounded by their seven-day retention window; no early eviction can replay.
    }

    private companion object {
        const val NAMESPACE = "core.entries"
        const val MAX_PENDING = 32
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
