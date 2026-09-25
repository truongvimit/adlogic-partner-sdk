package io.onboardkit.remote

import android.content.Context
import io.onboardkit.remote.uiconfig.UiAssetCache
import io.onboardkit.remote.uiconfig.UiConfig
import io.onboardkit.remote.uiconfig.UiConfigParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Facade over remote flags + server-driven UI. One instance per SDK install. */
class ObRemote internal constructor(context: Context) {

    internal val syncer = RemoteConfigSyncer(context)
    internal val assetCache = UiAssetCache(context)

    private val _uiConfig = MutableStateFlow(UiConfig(emptyList(), emptyList()))
    private val uiLock = Any()
    private var uiRevision = 0L
    private var prefetched: UiConfig? = null

    val flags: StateFlow<RemoteFlags> get() = syncer.flags
    val uiConfig: StateFlow<UiConfig> = _uiConfig.asStateFlow()

    @Volatile private var hostAssigned = false

    init {
        // The last delivered UI stands from install on, whether or not this launch's sync lands.
        rebuildUiConfig(prefetch = false)
    }

    suspend fun sync(timeoutMs: Long): Boolean {
        val fetched = syncer.fetchAndSync(timeoutMs)
        withContext(Dispatchers.Default) { rebuildUiConfig() }
        return fetched
    }

    fun applySnapshot(snapshot: RemoteFlags) {
        hostAssigned = true
        syncer.applySnapshot(snapshot)
        rebuildUiConfig()
    }

    /**
     * Takes the `ob_*` values a fetch made elsewhere already activated, without fetching again, so
     * a host that only awaits `AdConfig.refresh()` gets them too. A host that assigns its own
     * snapshot owns these values, and Firebase does not replace them here.
     */
    internal suspend fun rereadActivated() {
        if (hostAssigned) return
        if (syncer.rereadActivated()) withContext(Dispatchers.Default) { rebuildUiConfig() }
    }

    /** Remote UI applies per screen once that screen's asset is cached. */
    fun isUiStyleReady(stepId: String): Boolean {
        val style = _uiConfig.value.styleFor(stepId) ?: return false
        return assetCache.isReady(style.contentUrl)
    }

    /**
     * @param prefetch false publishes without downloading, for a moment the network may not be
     * up yet. Several paths rebuild after one fetch, and a config whose assets were already
     * requested is not requested again: an in-flight download would start a second time.
     */
    private fun rebuildUiConfig(prefetch: Boolean = true) {
        val revision = synchronized(uiLock) { ++uiRevision }
        val f = OnboardingSettings.resolveFlags(syncer.flags.value)
        val config = if (!f.enableUiContent) {
            UiConfig(emptyList(), emptyList())
        } else {
            UiConfigParser.parse(f.uiContentJson, f.uiDesignTokensJson)
        }
        synchronized(uiLock) {
            // A manual host update may finish while the older remote UI is still being parsed.
            if (revision == uiRevision) {
                if (prefetch && config != prefetched) {
                    assetCache.prefetch(config)
                    prefetched = config
                }
                _uiConfig.value = config
            }
        }
    }

    companion object {
        /**
         * Optionally share the host's Firebase fetch with other kits. Install during application
         * setup and capture application-owned objects only. Null restores standalone fetching.
         * A true result means a successful fetch/activation task, even if no values changed.
         */
        @JvmStatic
        fun installFetchDelegate(delegate: (suspend (Long) -> Boolean)?) {
            RemoteConfigSyncer.fetchDelegate = delegate
        }
    }
}
