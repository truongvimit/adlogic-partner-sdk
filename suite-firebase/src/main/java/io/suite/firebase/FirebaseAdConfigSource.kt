package io.suite.firebase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.ads.module.config.AdConfigSource
import org.json.JSONObject

/**
 * Reads the ad-unit document from Firebase Remote Config.
 *
 * Install it once and the ads module refreshes itself; without it the app runs on the
 * `ad_config.json` it ships, which is the right behaviour for a partner who does not tune ad units
 * remotely.
 *
 * @param key the Remote Config parameter holding the ad config JSON
 */
class FirebaseAdConfigSource @JvmOverloads constructor(
    private val key: String = "ad_remote_config",
) : AdConfigSource, com.ads.module.config.settings.SettingsConfigSource {

    private companion object {
        const val CACHE_NAME = "adlogic_remote_ad_config"
        const val CACHE_VALUE = "last_valid"
    }

    private fun prefs(): SharedPreferences? = runCatching {
        FirebaseApp.getInstance().applicationContext
            .getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    /** Accept only a document the SDK parser can resolve; malformed payloads never replace disk. */
    private fun activeRaw(usePersistedOnMissing: Boolean): String? {
        val raw = RemoteConfigClient.remoteRawString(key)
        if (raw == null) {
            // A missing remote parameter after a successful activation is a deletion. The
            // cached() path is the failure/restart path and deliberately restores the last valid
            // value when Firebase cannot expose the activated snapshot.
            if (usePersistedOnMissing) return prefs()?.getString(CACHE_VALUE, null)
            prefs()?.edit()?.remove(CACHE_VALUE)?.apply()
            return null
        }
        if (raw.isBlank()) {
            prefs()?.edit()?.remove(CACHE_VALUE)?.apply()
            return raw
        }
        // Syntactic validation belongs here; the ads module performs the schema and
        // field validation after the shared fetch. This keeps the adapter usable in
        // hosts that do not include Android's streaming parser in their JVM tests.
        if (runCatching { JSONObject(raw) }.isSuccess) {
            prefs()?.edit()?.putString(CACHE_VALUE, raw)?.apply()
            return raw
        }
        Log.w("FirebaseAdConfig", "$key ignored (invalid document); keeping last valid cache")
        return prefs()?.getString(CACHE_VALUE, null)
    }

    override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?>? {
        // This is an explicit refresh boundary. Firebase still applies its own
        // minimum fetch interval, while a later publish/deletion is observable.
        if (!RemoteConfigClient.fetchAndActivate(timeoutMs)) return null
        return mapOf(
            "ad_remote_config" to activeRaw(usePersistedOnMissing = false),
            "ad_behavior_config" to RemoteConfigClient.remoteRawString("ad_behavior_config"),
            "onboarding_config" to RemoteConfigClient.remoteRawString("onboarding_config"),
        )
    }

    override val id: String = "firebase"

    /** After a failed fetch, the document activated last still outranks the app's own. */
    override suspend fun fetch(timeoutMs: Long): String? {
        return if (RemoteConfigClient.fetchOnce(timeoutMs)) activeRaw(usePersistedOnMissing = false)
        else cached()
    }

    // Console-set values only: an in-app default here would replace a live configuration with
    // whatever the app happened to ship.
    // Keep blank as a delivered value.  An explicitly blank/empty document is a successful
    // clear operation; filtering it here would make the ads module retain a stale override.
    override suspend fun cached(): String? = activeRaw(usePersistedOnMissing = true)
}
