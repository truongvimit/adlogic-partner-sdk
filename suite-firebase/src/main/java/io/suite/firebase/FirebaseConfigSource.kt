package io.suite.firebase

import io.paykit.config.PaywallConfigSource

/**
 * Reads the paywall document from Firebase Remote Config.
 *
 * The fetch itself belongs to [RemoteConfigClient], shared with the ad-config source: this used to
 * run a `fetchAndActivate` of its own on every launch.
 *
 * @param key the Remote Config parameter holding the paywall JSON
 */
class FirebaseConfigSource @JvmOverloads constructor(
    private val key: String = "paywall_config",
) : PaywallConfigSource {

    override val id: String = "firebase"

    override suspend fun fetch(timeoutMs: Long): String? {
        if (!RemoteConfigClient.fetchOnce(timeoutMs)) return null
        // Console-set values only: a deleted key must fall through to PayKit's cache and bundled
        // default instead of returning an in-app default that is not a paywall document.
        return RemoteConfigClient.remoteString(key)
    }
}
