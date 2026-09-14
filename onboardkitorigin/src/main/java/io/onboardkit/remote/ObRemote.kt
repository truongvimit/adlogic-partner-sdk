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

    val flags: StateFlow<RemoteFlags> get() = syncer.flags
    val uiConfig: StateFlow<UiConfig> = _uiConfig.asStateFlow()

    suspend fun sync(timeoutMs: Long): Boolean {
        val fetched = syncer.fetchAndSync(timeoutMs)
        withContext(Dispatchers.Default) { rebuildUiConfig() }
        return fetched
    }

    fun applySnapshot(snapshot: RemoteFlags) {
        syncer.applySnapshot(snapshot)
        rebuildUiConfig()
    }

    /** Remote UI applies per screen once that screen's asset is cached. */
    fun isUiStyleReady(stepId: String): Boolean {
        val style = _uiConfig.value.styleFor(stepId) ?: return false
        return assetCache.isReady(style.contentUrl)
    }

    private fun rebuildUiConfig() {
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
                assetCache.prefetch(config)
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
