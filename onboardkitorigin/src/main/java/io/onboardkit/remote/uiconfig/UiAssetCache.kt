package io.onboardkit.remote.uiconfig

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background prefetcher for remote UI assets. Steps apply remote UI PER SCREEN as soon as
 * their own assets are ready — no all-or-nothing gate, so the first cold start can still
 * use remote UI for the screens whose assets finished in time.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UiAssetCache internal constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    private val _readyUrls = MutableStateFlow<Set<String>>(emptySet())
    val readyUrls: StateFlow<Set<String>> = _readyUrls.asStateFlow()

    val videoCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "ob_video_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_VIDEO_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    fun cacheDataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun prefetch(config: UiConfig) {
        config.steps.forEach { step ->
            val url = step.contentUrl ?: return@forEach
            if (url in _readyUrls.value) return@forEach
            if (step.isImage) prefetchImage(url) else prefetchVideo(url)
        }
    }

    fun isReady(url: String?): Boolean = url == null || url in _readyUrls.value

    private fun prefetchImage(url: String) {
        scope.launch {
            runCatching {
                Glide.with(context.applicationContext).downloadOnly().load(url).submit().get()
            }.onSuccess { markReady(url) }
                .onFailure { Log.w(TAG, "Image prefetch failed: $url") }
        }
    }

    private fun prefetchVideo(url: String) {
        scope.launch {
            runCatching {
                val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(url))
                CacheWriter(
                    cacheDataSourceFactory().createDataSource(),
                    dataSpec,
                    null,
                    null,
                ).cache()
            }.onSuccess { markReady(url) }
                .onFailure { Log.w(TAG, "Video prefetch failed: $url") }
        }
    }

    private fun markReady(url: String) {
        _readyUrls.value = _readyUrls.value + url
    }

    private companion object {
        const val TAG = "OnboardKit.AssetCache"
        const val MAX_VIDEO_CACHE_BYTES = 200L * 1024 * 1024
    }
}
