package io.paykit.firebase

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.paykit.config.PaywallConfigSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads the paywall document from Firebase Remote Config.
 *
 * @param key the Remote Config parameter holding the paywall JSON
 */
class FirebaseConfigSource @JvmOverloads constructor(
    private val key: String = "paywall_config",
) : PaywallConfigSource {

    override val id: String = "firebase"

    @Volatile
    private var cached: FirebaseRemoteConfig? = null

    override suspend fun fetch(timeoutMs: Long): String? {
        val remote = remoteConfig() ?: return null
        if (!awaitFetch(remote, timeoutMs)) return null
        return read(remote)
    }

    /**
     * Resolved on first fetch rather than at construction, and only a success is memoised: a host
     * that initialises Firebase late would otherwise be pinned to null for the whole process.
     */
    private fun remoteConfig(): FirebaseRemoteConfig? {
        cached?.let { return it }
        return runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()?.also { cached = it }
    }

    private suspend fun awaitFetch(remote: FirebaseRemoteConfig, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                try {
                    remote.fetchAndActivate()
                        // The task's boolean only reports whether values changed, not success.
                        .addOnSuccessListener { cont.resume(true) }
                        .addOnFailureListener {
                            Log.w(TAG, "Paywall config fetch failed: ${it.message}")
                            cont.resume(false)
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "Paywall config fetch threw: ${e.message}")
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false

    private fun read(remote: FirebaseRemoteConfig): String? = runCatching {
        val value = remote.getValue(key)
        // Console-set values only: a deleted key must fall through to PayKit's cache and bundled
        // default instead of returning an in-app default that is not a paywall document.
        if (value.source != FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) return@runCatching null
        value.asString().takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val TAG = "PayKit.Firebase"
    }
}
