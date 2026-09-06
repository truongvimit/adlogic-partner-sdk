package io.retentionkit.core

/** A selective kit can use any source without importing an umbrella or Firebase dependency. */
interface RetentionConfigSource {
    val id: String
    /** Return quickly; callbacks may occur synchronously or on any thread. close cancels owned work. */
    fun fetch(timeoutMillis: Long, callback: RetentionConfigCallback): AutoCloseable
}

fun interface RetentionConfigCallback { fun onResult(result: RetentionConfigFetchResult) }
sealed class RetentionConfigFetchResult {
    data class Document(val json: String) : RetentionConfigFetchResult()
    data object Missing : RetentionConfigFetchResult()
    data class Failed(val reason: String) : RetentionConfigFetchResult()
}

data class RetentionConfigDocument(val overrides: Map<String, String>, val removeKeys: Set<String> = emptySet())
sealed class RetentionConfigParseResult {
    data class Valid(val document: RetentionConfigDocument) : RetentionConfigParseResult()
    data class Invalid(val reason: String) : RetentionConfigParseResult()
}
sealed class RetentionConfigSyncResult {
    data class Applied(val revision: Long) : RetentionConfigSyncResult()
    data class Skipped(val reason: String) : RetentionConfigSyncResult()
    data class Failed(val reason: String) : RetentionConfigSyncResult()
}
