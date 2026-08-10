package io.onboardkit.remote

import android.content.Context
import io.onboardkit.remote.uiconfig.UiAssetCache
import io.onboardkit.remote.uiconfig.UiConfig
import io.onboardkit.remote.uiconfig.UiConfigParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Facade over remote flags + server-driven UI. One instance per SDK install. */
class ObRemote internal constructor(context: Context) {

    internal val syncer = RemoteConfigSyncer(context)
    internal val assetCache = UiAssetCache(context)

    private val _uiConfig = MutableStateFlow(UiConfig(emptyList(), emptyList()))

    val flags: StateFlow<RemoteFlags> get() = syncer.flags
    val uiConfig: StateFlow<UiConfig> = _uiConfig.asStateFlow()

    suspend fun sync(timeoutMs: Long): Boolean {
        val fetched = syncer.fetchAndSync(timeoutMs)
        rebuildUiConfig()
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
        val f = syncer.flags.value
        _uiConfig.value = if (!f.enableUiContent) {
            UiConfig(emptyList(), emptyList())
        } else {
            UiConfigParser.parse(f.uiContentJson, f.uiDesignTokensJson).also {
                assetCache.prefetch(it)
            }
        }
    }
}
