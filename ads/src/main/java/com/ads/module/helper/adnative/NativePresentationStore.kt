package com.ads.module.helper.adnative

import androidx.lifecycle.ViewModel
import com.ads.module.ads.wrapper.ApNativeAd

/** Only configuration recreation transfers a presentation. Ordinary departures dispose it. */
internal class NativePresentationStore : ViewModel() {
    class Presentation(val ad: ApNativeAd?, val shownAtMs: Long, val refreshAtMs: Long, val awaiting: Boolean)
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
