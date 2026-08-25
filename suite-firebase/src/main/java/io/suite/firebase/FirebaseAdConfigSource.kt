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
) : AdConfigSource {

    override val id: String = "firebase"

    override suspend fun fetch(timeoutMs: Long): String? {
        if (!RemoteConfigClient.fetchOnce(timeoutMs)) return null
        // Console-set values only: an in-app default here would replace a live configuration with
        // whatever the app happened to ship.
        return RemoteConfigClient.remoteString(key)
    }
}
