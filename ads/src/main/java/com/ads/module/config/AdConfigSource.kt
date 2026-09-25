package com.ads.module.config

import android.util.Log
import com.ads.module.config.settings.SettingsConfigSource
import com.ads.module.config.settings.SettingsRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * The document the backend last delivered, read without a network wait, or null when it has
     * delivered none.
     *
     * It stands in for a fetch that fails or has not answered yet, so it must never be a default
     * the app ships: that would put the app's own units above the backend's.
     */
    suspend fun cached(): String? = null
}

/**
 * Process-wide holder for the installed [AdConfigSource].
 *
 * With no source installed the app runs on the configuration shipped in its assets, which is the
 * correct behaviour for a partner who does not tune ad units remotely.
 */
object AdConfig {

    private const val TAG = "AdConfig"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var source: AdConfigSource? = null

    /** Also applies the source's [AdConfigSource.cached] document in the background. */
    @JvmStatic
    fun install(source: AdConfigSource) {
        AdConfig.source = source
        Log.i(TAG, "Ad config source installed: ${source.id}")
        scope.launch { applyCached(source) }
    }

    @JvmStatic
    fun hasSource(): Boolean = source != null

    /**
     * Fetches and applies a newer configuration, if a source is installed and answers in time.
     * When its settings fetch fails, the source's [AdConfigSource.cached] document applies instead.
     *
     * @return true when the active configuration was replaced.
     */
    @JvmStatic
    suspend fun refresh(timeoutMs: Long = 10_000): Boolean {
        val current = source ?: return false
        var reachable = true
        if (current is SettingsConfigSource) {
            val settings = try {
                current.fetchSettings(timeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Settings fetch failed: ${failure.message}")
                null
            }
            if (settings == null) reachable = false
            else withContext(NonCancellable) { SettingsRegistry.acceptSuccessfulFetch(settings) }
        }
        // A backend that just failed is not waited on a second time; the document it delivered
        // last still outranks the one the app shipped.
        val json = read(current) { if (reachable) it.fetch(timeoutMs) else it.cached() }
        if (json.isNullOrBlank()) return false
        // Applied whole: a caller's deadline must not leave the session half on the new document.
        return withContext(NonCancellable) { applyDocument(current.id, json) }
    }

    private suspend fun applyCached(installed: AdConfigSource) {
        val parsed = read(installed) { it.cached() }?.let { AdRemoteConfig.fromJson(it) }
            ?.takeIf { it.ads.isNotEmpty() } ?: return
        withContext(Dispatchers.Main.immediate) {
            // A refresh that landed meanwhile, or another source installed since, is newer.
            if (source === installed && !AdRemoteConfig.isFromRemote()) {
                AdRemoteConfig.applyRemote(parsed)
                Log.i(TAG, "Ad config from ${installed.id}'s last delivery: ${parsed.ads.size} placements")
            }
        }
    }

    private suspend fun read(current: AdConfigSource, block: suspend (AdConfigSource) -> String?): String? =
        try {
            block(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Ad config read from ${current.id} failed: ${failure.message}")
            null
        }

    private suspend fun applyDocument(sourceId: String, json: String): Boolean {
        val parsed = withContext(Dispatchers.Default) { AdRemoteConfig.fromJson(json) } ?: return false
        if (parsed.ads.isEmpty()) {
            // An empty document would silently disable every placement; keep what we have.
            Log.w(TAG, "Ad config from $sourceId has no placements — ignoring")
            return false
        }
        withContext(Dispatchers.Main.immediate) { AdRemoteConfig.applyRemote(parsed) }
        Log.i(TAG, "Ad config refreshed from $sourceId: ${parsed.ads.size} placements")
        return true
    }
}
