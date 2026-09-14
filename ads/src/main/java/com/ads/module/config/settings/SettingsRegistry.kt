package com.ads.module.config.settings

import com.ads.module.admob.AppOpenManager
import com.ads.module.helper.interstitial.InterstitialAutoBuffer
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
    private val documents = ConcurrentHashMap<String, SettingsDocument>()
    private val updates = Mutex()

    fun register(document: SettingsDocument) { documents[document.name] = document }

    suspend fun acceptSuccessfulFetch(values: Map<String, String?>) = updates.withLock {
        var changed = false
        for ((key, value) in values) {
            val document = documents[key] ?: continue
            val previous = document.snapshot
            document.acceptFetched(value)
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
}
