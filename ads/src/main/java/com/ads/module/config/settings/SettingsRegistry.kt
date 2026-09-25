package com.ads.module.config.settings

import android.util.Log
import com.ads.module.admob.AppOpenManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Backend boundary. Null is fetch failure; a null value is a successfully removed parameter. */
interface SettingsConfigSource {
    suspend fun fetchSettings(timeoutMs: Long): Map<String, String?>?
}

/** Coordinates documents without requiring the ads module to depend on OnboardKit or Firebase. */
object SettingsRegistry {
    private const val TAG = "AdLogicSettings"
    private val documents = ConcurrentHashMap<String, SettingsDocument>()
    private val fetchListeners = ConcurrentHashMap<String, suspend () -> Unit>()
    private val updates = Mutex()

    fun register(document: SettingsDocument) { documents[document.name] = document }

    /**
     * Runs [listener] after every successful fetch, once its documents are applied, for values the
     * same fetch activated outside them. Registering [name] again replaces its listener.
     */
    fun addFetchListener(name: String, listener: suspend () -> Unit) { fetchListeners[name] = listener }

    suspend fun acceptSuccessfulFetch(values: Map<String, String?>) {
        updates.withLock {
            var changed = false
            for ((key, value) in values) {
                val document = documents[key] ?: continue
                val previous = document.snapshot
                if (!document.acceptFetched(value)) Log.w(TAG, "$key was not applied; the values already in place stay")
                changed = changed || previous !== document.snapshot
            }
            if (changed) {
                // Both consumers touch Android handlers/ad presentation. JSON and persistence above
                // have completed before dispatching these UI-owned notifications.
                withContext(Dispatchers.Main.immediate) {
                    InterstitialAutoBuffer.onGateChanged()
                    AppOpenManager.getInstance().applyRemoteConfig()
                }
            }
        }
        // Outside the lock: a listener may apply what it reads through this registry.
        for (listener in fetchListeners.values) {
            try {
                listener()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Fetch listener failed: ${failure.message}")
            }
        }
    }
}
