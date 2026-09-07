package io.retentionkit.core

import java.util.ArrayDeque

/** Names describe observed evidence, never infer displayed/rated/uninstalled from an API request. */
data class RetentionEvent @JvmOverloads constructor(val name: String, val attributes: Map<String, String> = emptyMap())

fun interface RetentionEventSink {
    fun onEvent(event: RetentionEvent)
    companion object { @JvmField val NONE = RetentionEventSink {} }
}

enum class RetentionDiagnosticLevel { INFO, WARNING, ERROR }

data class RetentionDiagnostic(
    val atMillis: Long,
    val component: String,
    val message: String,
    val level: RetentionDiagnosticLevel,
    val exception: String? = null,
)

/** Bounded in-memory diagnostics; no backend/vendor dependency and no Activity references. */
class RetentionDiagnostics internal constructor(private val clock: RetentionClock, private val capacity: Int = 100) {
    private val records = ArrayDeque<RetentionDiagnostic>()
    @Synchronized fun snapshot(): List<RetentionDiagnostic> = records.toList()
    @Synchronized fun record(component: String, message: String, level: RetentionDiagnosticLevel = RetentionDiagnosticLevel.INFO, error: Exception? = null) {
        if (records.size >= capacity) records.removeFirst()
        records.addLast(RetentionDiagnostic(clock.wallTimeMillis(), component, message, level, error?.stackTraceToString()))
    }

    internal fun guard(component: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Exception) {
        record(component, error.message ?: error.javaClass.simpleName, RetentionDiagnosticLevel.ERROR, error)
        false
    }
}
