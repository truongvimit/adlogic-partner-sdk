package io.adtracer

/** Ad surface being tracked. OTHER covers non-ad opportunities (e.g. resume-rule decisions). */
enum class AdFormat { NATIVE, INTERSTITIAL, BANNER, REWARDED, APP_OPEN, OTHER }

/**
 * One immutable lifecycle observation. `(sessionId, seq)` is the global dedupe/ordering key;
 * `monoMs` (elapsedRealtime) is authoritative for ordering, `wallMs` is display-only.
 */
data class AdEvent(
    val seq: Long,
    val sessionId: String,
    val wallMs: Long,
    val monoMs: Long,
    val type: String,
    val placement: String,
    val format: String,
    val adUnitId: String?,
    val reason: String?,
    val code: Int?,
    val message: String?,
    val synthetic: Boolean,
    val approx: Boolean,
) {

    fun toJson(): String = buildString(160) {
        append("{\"s\":").jsonValue(sessionId)
        append(",\"q\":").append(seq)
        append(",\"tw\":").append(wallMs)
        append(",\"tm\":").append(monoMs)
        append(",\"type\":").jsonValue(type)
        append(",\"placement\":").jsonValue(placement)
        append(",\"format\":").jsonValue(format)
        adUnitId?.let { append(",\"unit\":").jsonValue(it) }
        reason?.let { append(",\"reason\":").jsonValue(it) }
        code?.let { append(",\"code\":").append(it) }
        message?.let { append(",\"message\":").jsonValue(it) }
        if (synthetic) append(",\"syn\":true")
        if (approx) append(",\"approx\":true")
        append('}')
    }
}

internal fun StringBuilder.jsonValue(value: String): StringBuilder {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
    }
    append('"')
    return this
}
