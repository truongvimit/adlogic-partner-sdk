package com.ads.module.config

import android.util.Log

/**
 * Where a fresher ad configuration comes from, vendor-free.
 *
 * The module ships the assets loader and nothing else; a host that tunes ad units remotely installs
 * a source for whichever backend it uses. Mirrors [com.ads.module.helper.EntitlementSource]: an
 * interface here, the vendor SDK in a module of its own.
 */
interface AdConfigSource {

    /** Identifies the source in logs, e.g. `firebase`. */
    val id: String

    /**
     * Fetches the ad-config document, or null when it is unavailable within [timeoutMs].
     *
     * Returning null must leave the current configuration standing — a failed fetch is not a
     * reason to run with no ad units.
     */
    suspend fun fetch(timeoutMs: Long): String?
}

/**
 * Process-wide holder for the installed [AdConfigSource].
 *
 * With no source installed the app runs on the configuration shipped in its assets, which is the
 * correct behaviour for a partner who does not tune ad units remotely.
 */
object AdConfig {

    private const val TAG = "AdConfig"

    @Volatile
    private var source: AdConfigSource? = null

    @JvmStatic
    fun install(source: AdConfigSource) {
        AdConfig.source = source
        Log.i(TAG, "Ad config source installed: ${source.id}")
    }

    @JvmStatic
    fun hasSource(): Boolean = source != null

    /**
     * Fetches and applies a newer configuration, if a source is installed and answers in time.
     *
     * @return true when the active configuration was replaced.
     */
    @JvmStatic
    suspend fun refresh(timeoutMs: Long = 10_000): Boolean {
        val current = source ?: return false
        // A debuggable build stays on the ids it shipped in assets/ad_config_debug.json. Those are
        // the test units; the live document holds the real ones, and letting it land here is how a
        // debug run generates invalid traffic on the app's own account.
        if (AdRemoteConfig.isRemoteOverrideBlocked()) {
            Log.i(TAG, "Debug build — keeping ${AdRemoteConfig.DEBUG_FILE_NAME}, remote ignored")
            return false
        }
        val json = runCatching { current.fetch(timeoutMs) }
            .onFailure { Log.w(TAG, "Ad config fetch failed: ${it.message}") }
            .getOrNull()
        if (json.isNullOrBlank()) return false
        val parsed = AdRemoteConfig.fromJson(json) ?: return false
        if (parsed.ads.isEmpty()) {
            // An empty document would silently disable every placement; keep what we have.
            Log.w(TAG, "Ad config from ${current.id} has no placements — ignoring")
            return false
        }
        AdRemoteConfig.update(parsed)
        Log.i(TAG, "Ad config refreshed from ${current.id}: ${parsed.ads.size} placements")
        return true
    }
}
