package com.ads.module.helper.adnative

import android.content.Context
import androidx.annotation.MainThread
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.LoadAdError
import io.trackkit.AdFormat

/**
 * One unused native and one load per placement, shared by preloads and visible helpers.
 * Requests use the application context and never capture a screen. A screen only owns its
 * subscription and the ad it consumes. All operations and callbacks run on the main thread.
 */
@MainThread
object NativeAdManager {
    fun interface Subscription { fun cancel() }

    private class Entry {
        var ready: ApNativeAd? = null
        var loading = false
        var discardResult = false
        val waiters = linkedSetOf<(ApNativeAd?) -> Unit>()
        val listeners = linkedSetOf<AdCallback>()
    }

    private val entries = mutableMapOf<String, Entry>()
    private val adListeners = java.util.WeakHashMap<ApNativeAd, MutableSet<AdCallback>>()

    /** A stable placement is preferred; this fallback preserves existing unnamed callers. */
    fun keyOf(config: NativeAdConfig): String = config.adUnitIds.joinToString(separator = "")

    @JvmStatic fun isLoading(placement: String): Boolean = entries[placement]?.loading == true
    @JvmStatic fun isReady(placement: String): Boolean = peek(placement) != null

    /** Starts at most one request. false means covered already, or blocked by request gates. */
    @JvmStatic
    @JvmOverloads
    fun preload(context: Context, placement: String, config: NativeAdConfig, reportTelemetry: Boolean = true): Boolean {
        if (isReady(placement) || isLoading(placement)) return false
        val app = context.applicationContext
        val reason = AdGate.skipReason(app, config.canShowAds && config.adUnitIds.isNotEmpty(),
            AdGate.passesUaGate(config.forceUaCheck))
        if (reason != null) {
            if (reportTelemetry) AdTracking.skipped(placement, AdFormat.NATIVE, reason.key)
            return false
        }
        val entry = entries.getOrPut(placement) { Entry() }
        entry.loading = true
        entry.discardResult = false
        config.adUnitIds.forEach { AdTracking.registerPlacement(it, placement) }
        if (reportTelemetry) AdTracking.request(placement, AdFormat.NATIVE, config.idAds)
        var filled: ApNativeAd? = null
        AdWaterfall.loadNative(app, config.adUnitIds, config.layoutId, config.tierTimeoutMs,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    filled = nativeAd
                    finish(entry, nativeAd, null)
                }
                override fun onAdFailedToLoad(error: LoadAdError?) = finish(entry, null, error)
                override fun onAdClicked() = events { it.onAdClicked() }
                override fun onAdOpened() = events { it.onAdOpened() }
                override fun onAdImpression() = events { it.onAdImpression() }
                private fun events(block: (AdCallback) -> Unit) {
                    val ad = filled?.takeIf { it.isUsable } ?: return
                    entry.listeners.toList().forEach { runCatching { block(it) } }
                    adListeners[ad]?.toList()?.forEach { runCatching { block(it) } }
                }
            })
        return true
    }

    /** Claims a ready ad or joins/starts the placement load. Cancellation only detaches this UI. */
    internal fun acquire(context: Context, placement: String, config: NativeAdConfig,
        reportTelemetry: Boolean, result: (ApNativeAd?) -> Unit): Subscription {
        poll(placement)?.let { result(it); return Subscription {} }
        val entry = entries.getOrPut(placement) { Entry() }
        entry.waiters.add(result)
        if (!entry.loading && !preload(context, placement, config, reportTelemetry)) {
            entry.waiters.remove(result)
            result(null)
        }
        return Subscription { entry.waiters.remove(result) }
    }

    internal fun peek(placement: String): ApNativeAd? {
        val entry = entries[placement] ?: return null
        val ad = entry.ready ?: return null
        if (!ad.isUsable) {
            entry.ready = null
            dispose(ad)
            return null
        }
        return ad
    }

    /** Removes an unused ad before binding, so two screens cannot spend the same fill. */
    internal fun poll(placement: String): ApNativeAd? = peek(placement)?.also { entries[placement]?.ready = null }

    internal fun awaitNext(placement: String, result: (ApNativeAd?) -> Unit): Subscription {
        val entry = entries[placement]
        if (peek(placement) != null || entry?.loading != true) {
            result(poll(placement))
            return Subscription {}
        }
        entry.waiters.add(result)
        return Subscription { entry.waiters.remove(result) }
    }

    internal fun claim(ad: ApNativeAd) {
        entries.values.forEach { if (it.ready === ad) it.ready = null }
    }

    internal fun returnUnused(placement: String, ad: ApNativeAd) {
        val entry = entries.getOrPut(placement) { Entry() }
        if (ad.isUsable && !entry.discardResult && entry.ready == null) entry.ready = ad
        else dispose(ad)
    }

    internal fun observe(ad: ApNativeAd, callback: AdCallback): Subscription {
        adListeners.getOrPut(ad) { linkedSetOf() }.add(callback)
        return Subscription { adListeners[ad]?.remove(callback) }
    }

    internal fun register(placement: String, callback: AdCallback) {
        entries.getOrPut(placement) { Entry() }.listeners.add(callback)
    }
    internal fun unregister(placement: String, callback: AdCallback) { entries[placement]?.listeners?.remove(callback) }

    private fun finish(entry: Entry, ad: ApNativeAd?, error: LoadAdError?) {
        entry.loading = false
        val accepted = ad?.takeIf { !entry.discardResult && it.isUsable }
        if (accepted == null && ad != null) dispose(ad)
        entry.ready = accepted
        val waiting = entry.waiters.toList()
        entry.waiters.clear()
        // Snapshot before callbacks: a reentrant show/preload starts a separate request.
        entry.listeners.toList().forEach { listener ->
            runCatching {
                if (accepted != null) listener.onNativeAdLoaded(accepted)
                else listener.onAdFailedToLoad(error)
            }
        }
        waiting.forEach { waiter ->
            // Persistent legacy listeners may already have claimed or released this fill.
            val available = entry.ready?.takeIf { it === accepted && it.isUsable }
            if (available != null) entry.ready = null
            runCatching { waiter(available) }.onFailure { available?.let(::dispose) }
        }
    }

    /** Explicit invalidation, unlike a helper leaving its screen. In-flight work stays deduplicated. */
    @JvmStatic fun release(placement: String) {
        val entry = entries[placement] ?: return
        entry.ready?.let(::dispose)
        entry.ready = null
        entry.discardResult = true
        entry.listeners.clear()
        val waiting = entry.waiters.toList()
        entry.waiters.clear()
        if (!entry.loading) entries.remove(placement)
        waiting.forEach { runCatching { it(null) } }
    }

    @JvmStatic fun releaseAll() { entries.keys.toList().forEach(::release) }

    internal fun dispose(ad: ApNativeAd) {
        adListeners.remove(ad)
        runCatching { ad.destroy() }
    }
}
