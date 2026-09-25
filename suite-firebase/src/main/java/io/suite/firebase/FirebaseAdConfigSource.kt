package io.suite.firebase

import com.ads.module.config.AdConfigSource

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

    override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?>? {
        if (!RemoteConfigClient.fetchOnce(timeoutMs)) return null
        return listOf("ad_behavior_config", "onboarding_config").associateWith(RemoteConfigClient::remoteRawString)
    }

    override val id: String = "firebase"

    /** After a failed fetch, the document activated last still outranks the app's own. */
    override suspend fun fetch(timeoutMs: Long): String? {
        RemoteConfigClient.fetchOnce(timeoutMs)
        return cached()
    }

    // Console-set values only: an in-app default here would replace a live configuration with
    // whatever the app happened to ship.
    override suspend fun cached(): String? = RemoteConfigClient.remoteString(key)
}
