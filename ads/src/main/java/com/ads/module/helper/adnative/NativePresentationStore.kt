package com.ads.module.helper.adnative

import androidx.lifecycle.ViewModel
import com.ads.module.ads.wrapper.ApNativeAd

/** Configuration recreation transfers a presentation; final owner destruction disposes it. */
internal class NativePresentationStore : ViewModel() {
    class Presentation(
        val ad: ApNativeAd?,
        val shownAtMs: Long,
        val refreshAtMs: Long,
        val awaiting: Boolean,
        val failed: Boolean = false,
    )
    private val retained = mutableMapOf<String, Presentation>()

    fun retain(key: String, presentation: Presentation) {
        retained.put(key, presentation)?.ad?.takeIf { it !== presentation.ad }?.let(NativeAdManager::dispose)
    }

    fun take(key: String): Presentation? = retained.remove(key)

    override fun onCleared() {
        retained.values.forEach { it.ad?.let(NativeAdManager::dispose) }
        retained.clear()
    }
}

/** A closed screen's shown ad, waiting for the next helper of its placement anywhere in the process. */
internal object RetainedPresentations {
    private val retained = mutableMapOf<String, NativePresentationStore.Presentation>()

    fun retain(key: String, presentation: NativePresentationStore.Presentation) {
        retained.put(key, presentation)?.ad?.takeIf { it !== presentation.ad }?.let(NativeAdManager::dispose)
    }

    /** Null, disposing the ad, once it has outlived the native cache age. */
    fun take(key: String): NativePresentationStore.Presentation? {
        val saved = retained.remove(key) ?: return null
        if (saved.ad?.isUsable == true) return saved
        saved.ad?.let(NativeAdManager::dispose)
        return null
    }
}
