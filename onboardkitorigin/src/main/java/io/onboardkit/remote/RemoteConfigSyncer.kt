package io.onboardkit.remote

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit

/**
 * Fetches remote config with a hard timeout and publishes an immutable [RemoteFlags] snapshot.
 *
 * Every key is re-resolved on every sync: a key deleted from the console falls back to its
 * default instead of sticking to the last fetched value forever. The complete snapshot, including
 * `ob_config_version`, replaces the local cache in one preferences edit.
 */
class RemoteConfigSyncer internal constructor(
    context: Context,
    private val remoteConfigProvider: () -> FirebaseRemoteConfig? = {
        runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()
    },
) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _flags = MutableStateFlow(loadCached())
    val flags: StateFlow<RemoteFlags> = _flags.asStateFlow()

    /** Suspends at most [timeoutMs]; on timeout or error the last known snapshot stays active. */
    suspend fun fetchAndSync(timeoutMs: Long): Boolean {
        val remote = remoteConfigProvider() ?: return false
        val fetched = withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                remote.fetchAndActivate()
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener {
                        Log.w(TAG, "Remote fetch failed: ${it.message}")
                        cont.resume(false)
                    }
            }
        } ?: false

        val snapshot = RemoteFlags.from(FirebaseReader(remote))
        persist(snapshot)
        _flags.value = snapshot
        return fetched
    }

    /** For hosts that manage remote config themselves — inject values without Firebase. */
    fun applySnapshot(snapshot: RemoteFlags) {
        persist(snapshot)
        _flags.value = snapshot
    }

    private fun persist(snapshot: RemoteFlags) {
        prefs.edit {
            clear()
            ObRemoteKeys.cacheValues(snapshot).forEach { (key, value) -> putString(key, value) }
        }
    }

    private fun loadCached(): RemoteFlags {
        if (prefs.all.isEmpty()) return RemoteFlags()
        val reader = object : RemoteValueReader {
            override fun string(key: String): String? = prefs.getString(key, null)
        }
        return RemoteFlags.from(reader)
    }

    private class FirebaseReader(private val remote: FirebaseRemoteConfig) : RemoteValueReader {
        override fun string(key: String): String? {
            val value = remote.getValue(key)
            return if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) {
                value.asString()
            } else {
                null
            }
        }
    }

    private companion object {
        const val TAG = "OnboardKit.Remote"
        const val PREFS_NAME = "ob_remote_cache"
    }
}
