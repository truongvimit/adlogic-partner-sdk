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
    suspend fun fetchOnce(timeoutMs: Long): Boolean {
        inFlight?.let { pending ->
            return withTimeoutOrNull(timeoutMs.milliseconds) { pending.await() } ?: false
        }
        val deferred = CompletableDeferred<Boolean>()
        inFlight = deferred

        val remote = remoteConfig()
        if (remote == null) {
            Log.w(TAG, "Firebase Remote Config unavailable — is Firebase initialised?")
            deferred.complete(false)
            inFlight = null
            return false
        }

        val result = withTimeoutOrNull(timeoutMs.milliseconds) {
            val awaited = CompletableDeferred<Boolean>()
            try {
                remote.fetchAndActivate()
                    // The task's boolean only reports whether values changed, not success.
                    .addOnSuccessListener { awaited.complete(true) }
                    .addOnFailureListener {
                        Log.w(TAG, "Remote config fetch failed: ${it.message}")
                        awaited.complete(false)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Remote config fetch threw: ${e.message}")
                awaited.complete(false)
            }
            awaited.await()
        } ?: false

        deferred.complete(result)
        // Only a success is memoised for the launch. Pinning a failure let the shortest-tempered
        // caller decide for everyone: the paywall syncs with 3s and the ad config with 10s, so a
        // paywall timeout used to hand the ad config an instant `false` and the ad units were
        // never refreshed that session. Clearing it lets the next caller run its own fetch.
        if (!result) inFlight = null
        return result
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
        inFlight = null
    }
}
