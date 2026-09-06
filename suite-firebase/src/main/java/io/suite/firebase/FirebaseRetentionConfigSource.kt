package io.suite.firebase

import android.util.Log
import io.retentionkit.core.RetentionConfigCallback
import io.retentionkit.core.RetentionConfigFetchResult
import io.retentionkit.core.RetentionConfigSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Uses the suite's existing shared fetch and remote-only value rule; no second Firebase client. */
class FirebaseRetentionConfigSource @JvmOverloads constructor(
    private val key: String = "retention_config",
) : RetentionConfigSource {
    override val id = "firebase"
    override fun fetch(timeoutMillis: Long, callback: RetentionConfigCallback): AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val result = try {
                if (RemoteConfigClient.fetchOnce(timeoutMillis)) {
                    RemoteConfigClient.remoteString(key)?.let(RetentionConfigFetchResult::Document) ?: RetentionConfigFetchResult.Missing
                } else RetentionConfigFetchResult.Missing
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { RetentionConfigFetchResult.Failed(error.message ?: "firebase_error") }
            try { callback.onResult(result) }
            catch (error: Exception) { Log.w("SuiteFirebase", "Retention config callback failed", error) }
        }
        return AutoCloseable { scope.cancel() }
    }
}
