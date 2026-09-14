package io.suite.firebase

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Firebase adapter shared by the suite and hosts that opt into [fetchAndActivate].
 *
 * The process-owned supervisor keeps a caller's timeout/cancellation from cancelling another
 * kit's fetch. Firebase's own fetch timeout bounds the underlying task; each caller separately
 * chooses how long it can wait. Successful [fetchOnce] calls keep the existing per-process cache,
 * while an explicit refresh still requests a new fetch (subject to Firebase's cache interval).
 */
object RemoteConfigClient {

    private const val TAG = "SuiteFirebase"

    /**
     * Memoised on first success rather than resolved once: a host that initialises Firebase after
     * this object is touched would otherwise be pinned to null for the life of the process.
     */
    @Volatile
    private var cached: FirebaseRemoteConfig? = null

    @Volatile
    private var fetches = newFetchCoordinator()

    /** Overridable for tests; production resolves the real singleton. */
    @Volatile
    var provider: () -> FirebaseRemoteConfig? = {
        runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()
    }

    fun remoteConfig(): FirebaseRemoteConfig? {
        cached?.let { return it }
        val resolved = provider()
        if (resolved != null) cached = resolved
        return resolved
    }

    /** True means the task succeeded, including when Firebase reports no newly activated values. */
    suspend fun fetchOnce(timeoutMs: Long): Boolean = fetches.fetch(timeoutMs, reuseSuccess = true)

    /** Explicit refresh: joins current work but does not reuse a previous completed fetch. */
    suspend fun fetchAndActivate(timeoutMs: Long): Boolean =
        fetches.fetch(timeoutMs, reuseSuccess = false)

    /** Uses Firebase's configured task timeout when the caller has no additional deadline. */
    suspend fun fetchAndActivate(): Boolean = fetches.fetch(null, reuseSuccess = false)

    private fun newFetchCoordinator(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) = FetchCoordinator(scope) {
        val remote = remoteConfig()
            ?: throw IllegalStateException("Firebase Remote Config unavailable; initialize Firebase first")
        // This Boolean describes activation, not success. Await's normal return is success.
        remote.fetchAndActivate().await()
    }

    /**
     * Reads a key that the server actually supplied.
     *
     * Rejects in-app defaults on purpose: a kit that treats its own default as a fetched value
     * cannot tell "the console says off" from "the console has never heard of this key".
     */
    fun remoteString(key: String): String? = remoteRawString(key)?.takeIf { it.isNotBlank() }

    /** Documents distinguish an absent parameter from a present but malformed/blank payload. */
    fun remoteRawString(key: String): String? {
        val value: FirebaseRemoteConfigValue = remoteConfig()?.getValue(key) ?: return null
        if (value.source != FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) return null
        return value.asString()
    }

    /** Test seam: forget the memoised instance and cancel any work owned by this client. */
    fun reset() {
        cached = null
        val previous = fetches
        fetches = newFetchCoordinator()
        previous.cancel()
    }

    internal fun reset(scope: CoroutineScope) {
        cached = null
        val previous = fetches
        fetches = newFetchCoordinator(scope)
        previous.cancel()
    }

    private class FetchCoordinator(
        private val scope: CoroutineScope,
        private val activate: suspend () -> Unit,
    ) {
        private val lock = Any()
        private var inFlight: Deferred<Boolean>? = null
        private var succeeded = false

        suspend fun fetch(timeoutMs: Long?, reuseSuccess: Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            if (timeoutMs != null && timeoutMs <= 0) return false
            val task = synchronized(lock) {
                if (inFlight == null && reuseSuccess && succeeded) return true
                inFlight ?: scope.async(start = CoroutineStart.LAZY) {
                    try {
                        activate()
                        synchronized(lock) { succeeded = true }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Remote config fetch failed: ${error.message}")
                        false
                    }
                }.also { pending ->
                    inFlight = pending
                    pending.invokeOnCompletion {
                        synchronized(lock) {
                            if (inFlight === pending) inFlight = null
                        }
                    }
                }
            }
            // Once started, work survives an individual waiter's deadline.
            task.start()
            return if (timeoutMs == null) task.await()
            else withTimeoutOrNull(timeoutMs) { task.await() } ?: false
        }

        fun cancel() = scope.cancel()
    }
}
