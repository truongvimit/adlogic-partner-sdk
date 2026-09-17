package io.suite.firebase

import com.ads.module.update.ForceUpdateConfig

/** Optional adapter: :ads itself has no Firebase dependency. Local defaults cannot enable updates. */
object FirebaseUpdateConfig {
    /**
     * Call after the host's existing fetch/activate step has settled. Does not fetch again.
     * Only VALUE_SOURCE_REMOTE is trusted, including a previously activated Firebase remote cache.
     * Missing, blank or malformed remote JSON means off; no asset or separate policy-cache fallback.
     */
    @JvmStatic
    fun activated(): ForceUpdateConfig = runCatching {
        RemoteConfigClient.remoteRawString(ForceUpdateConfig.REMOTE_KEY)
            ?.let(ForceUpdateConfig::fromJson)
    }.getOrNull() ?: ForceUpdateConfig()

    /** For standalone hosts without an existing remote step; joins the suite's shared fetch. */
    suspend fun fetch(timeoutMs: Long = 3_000): ForceUpdateConfig {
        RemoteConfigClient.fetchAndActivate(timeoutMs)
        return activated()
    }
}
