package io.suite.firebase

import android.util.Log
import io.retentionkit.core.RetentionConfigCallback
import io.retentionkit.core.RetentionConfigFetchResult
import io.retentionkit.core.RetentionConfigSource
import io.retentionkit.core.RetentionConfigParser
import io.retentionkit.core.RetentionConfigParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Uses the suite's existing shared fetch and remote-only value rule; no second Firebase client. */
class FirebaseRetentionConfigSource @JvmOverloads constructor(
    private val key: String = "retention_config",
    legacyKeys: Set<String> = emptySet(),
    private val legacyMapper: ((Map<String, String>) -> Map<String, String>)? = null,
) : RetentionConfigSource {
    private val legacyKeys = legacyKeys.toSet()
    init {
        require(key.isNotBlank()) { "Retention document key must not be blank" }
        require(legacyKeys.all { it.isNotBlank() }) { "Legacy keys must not be blank" }
        require(legacyKeys.isEmpty() || legacyMapper != null) { "Legacy keys require an explicit mapper" }
    }
    override val id = "firebase"
    override fun fetch(timeoutMillis: Long, callback: RetentionConfigCallback): AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val result = try {
                if (RemoteConfigClient.fetchOnce(timeoutMillis)) {
                    readActivatedValues(RemoteConfigClient::remoteString)
                } else RetentionConfigFetchResult.Missing
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { RetentionConfigFetchResult.Failed(error.message ?: "firebase_error") }
            ensureActive()
            try { callback.onResult(result) }
            catch (error: Exception) { Log.w("SuiteFirebase", "Retention config callback failed", error) }
        }
        return AutoCloseable { scope.cancel() }
    }

    /** Same activated snapshot for the JSON document and optional source keys; no second fetch.
     * Missing source keys do not become false/empty overrides. The normalized document wins,
     * including explicit removals, so an old alias cannot revive a key the new document removed.
     */
    internal fun readActivatedValues(read: (String) -> String?): RetentionConfigFetchResult {
        val document = read(key)
        val mapped = if (legacyKeys.isEmpty()) emptyMap() else {
            val present = legacyKeys.mapNotNull { sourceKey -> read(sourceKey)?.let { sourceKey to it } }.toMap()
            if (present.isEmpty()) emptyMap() else checkNotNull(legacyMapper).invoke(present)
        }
        if (mapped.isEmpty()) return document?.let(RetentionConfigFetchResult::Document) ?: RetentionConfigFetchResult.Missing
        val parsed = document?.let(RetentionConfigParser::parse)
        // Let the runtime reject this invalid document and retain its last good configuration.
        // Falling back to aliases here would hide a malformed authoritative document.
        if (parsed is RetentionConfigParseResult.Invalid) return RetentionConfigFetchResult.Document(checkNotNull(document))
        val normalized = (parsed as? RetentionConfigParseResult.Valid)?.document
        val removed = normalized?.removeKeys.orEmpty()
        val merged = mapped.filterKeys { it !in removed } + normalized?.overrides.orEmpty()
        return RetentionConfigFetchResult.Document(JSONObject().apply {
            put("version", 1)
            put("overrides", JSONObject(merged))
            if (removed.isNotEmpty()) put("removeKeys", JSONArray(removed.sorted()))
        }.toString())
    }
}
