package io.suite.firebase

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The suite's single Firebase Remote Config client.
 *
 * Kits used to fetch separately against the one `FirebaseRemoteConfig` singleton, each with its own
 * timeout and its own idea of whether an in-app default counts as a value. The paywall source and
 * the ad-config source now share this client: the first caller does the work, later callers await
 * the same result.
 *
 * Not yet the only fetch in the app: `:onboardkitorigin` still runs its own, and so does the
 * template's app-flag reader. Those are the remaining two to fold in.
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
    private var inFlight: CompletableDeferred<Boolean>? = null

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

    /**
     * Fetches and activates once per launch, sharing the result with concurrent callers.
     *
     * @return true when values were fetched and activated.
     */
    suspend fun fetchOnce(timeoutMs: Long): Boolean = fetchShared(timeoutMs) {
        val remote = remoteConfig()
        if (remote == null) {
            Log.w(TAG, "Firebase Remote Config unavailable — is Firebase initialised?")
            return@fetchShared false
        }
        val awaited = CompletableDeferred<Boolean>()
        try {
            remote.fetchAndActivate()
                // Task boolean means values changed, not whether the fetch succeeded.
                .addOnSuccessListener { awaited.complete(true) }
                .addOnFailureListener {
                    Log.w(TAG, "Remote config fetch failed: ${it.message}")
                    awaited.complete(false)
                }
        } catch (error: Exception) {
            Log.w(TAG, "Remote config fetch threw: ${error.message}")
            awaited.complete(false)
        }
        awaited.await()
    }

    private val fetchLock = Any()

    /** Atomic leader election shared by all suite sources; cancellation cannot strand followers. */
    internal suspend fun fetchShared(timeoutMs: Long, operation: suspend () -> Boolean): Boolean {
        require(timeoutMs > 0)
        val (pending, leader) = synchronized(fetchLock) {
            inFlight?.let { it to false } ?: CompletableDeferred<Boolean>().let {
                inFlight = it
                it to true
            }
        }
        if (!leader) return withTimeoutOrNull(timeoutMs.milliseconds) { pending.await() } ?: false
        var result = false
        try {
            result = withTimeoutOrNull(timeoutMs.milliseconds) { operation() } ?: false
            return result
        } finally {
            pending.complete(result)
            // Preserve a newer fetch installed by reset/retry; success stays memoized this launch.
            if (!result) synchronized(fetchLock) { if (inFlight === pending) inFlight = null }
        }
    }

    /**
     * Reads a key that the server actually supplied.
     *
     * Rejects in-app defaults on purpose: a kit that treats its own default as a fetched value
     * cannot tell "the console says off" from "the console has never heard of this key".
     */
    fun remoteString(key: String): String? {
        val value: FirebaseRemoteConfigValue = remoteConfig()?.getValue(key) ?: return null
        if (value.source != FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) return null
        return value.asString().takeIf { it.isNotBlank() }
    }

    /** Test seam: forget the memoised instance and any in-flight fetch. */
    fun reset() {
        cached = null
        synchronized(fetchLock) { inFlight = null }
    }
}
