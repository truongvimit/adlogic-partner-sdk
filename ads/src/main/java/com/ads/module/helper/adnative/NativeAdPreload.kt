package com.ads.module.helper.adnative

import android.app.Activity
import android.content.Context
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.CachedAd
import com.google.android.gms.ads.LoadAdError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Per-helper preload policy. Defaults match the audited helper layer. */
class NativeAdPreloadClientOption @JvmOverloads constructor(
    /** Refill the buffer right after a buffered ad is consumed and shown. */
    val preloadAfterShow: Boolean = false,
    /** How many ads one preload batch requests. */
    val preloadBuffer: Int = 1,
    /** Route the reload-on-resume request through the buffer instead of the network. */
    val preloadOnResume: Boolean = true,
)

/**
 * Process-wide native preload buffer, keyed by an arbitrary string (a placement name, or
 * the ad-unit concat [keyOf] derives).
 *
 * Buffered ads carry their load time: a stale buffer entry is destroyed on read instead of
 * being handed to a screen that would bind an expired ad.
 *
 * Main-thread only — the legacy GMA SDK requires load calls there.
 */
class NativeAdPreload private constructor() {

    private val executors = ConcurrentHashMap<String, PreloadExecutor>()

    /** Default key when the caller does not name one: the waterfall's id concat. */
    fun keyOf(config: NativeAdConfig): String = config.adUnitIds.joinToString(separator = "")

    /** @return whether a request batch was actually started. */
    @JvmOverloads
    fun preload(activity: Activity, config: NativeAdConfig, buffer: Int = 1): Boolean =
        preloadWithKey(keyOf(config), activity, config, buffer)

    /** @return whether a request batch was actually started. */
    @JvmOverloads
    fun preloadWithKey(
        key: String,
        activity: Activity,
        config: NativeAdConfig,
        buffer: Int = 1,
    ): Boolean {
        require(buffer > 0) { "Buffer must be greater than 0" }
        if (!canRequestLoad(activity)) return false
        if (config.adUnitIds.isEmpty()) return false
        executors.getOrPut(key) { PreloadExecutor() }.execute(activity, config, buffer)
        return true
    }

    /**
     * Preloads one ad only when the buffer is empty and nothing is in flight.
     *
     * @return true when a buffered ad or an in-flight request already covers [key], or a
     *   new request was started — false only when nothing is or will be available.
     */
    fun preloadWithKeyIfEmpty(key: String, activity: Activity, config: NativeAdConfig): Boolean {
        if (AdGate.skipReason(activity, enabled = true, checkNetwork = false) != null) {
            executors[key]?.invalidate()
            return false
        }
        val executor = executors[key]
        if (executor != null && (executor.isInProgress() || executor.peek() != null)) return true
        return preloadWithKey(key, activity, config, 1)
    }

    /** Every preload uses the shared request gate, including consent authority and active forms. */
    fun canRequestLoad(context: Context): Boolean =
        AdGate.skipReason(context, enabled = true) == null

    /** Peeks the freshest buffered ad without consuming it. */
    fun getAdNative(key: String): ApNativeAd? = executors[key]?.peek()

    /** Consumes and returns a fresh buffered ad, or `null`. */
    fun pollAdNative(key: String): ApNativeAd? = executors[key]?.poll()

    /** True when an ad is buffered or a batch is still loading. */
    fun isPreloadAvailable(key: String): Boolean = executors[key]?.isAvailable() == true

    fun isPreloadInProgress(key: String): Boolean = executors[key]?.isInProgress() == true

    fun getNativeAdBuffer(key: String): List<ApNativeAd> =
        executors[key]?.buffer() ?: emptyList()

    /**
     * One-shot: fires with the next ad the in-flight batch produces (or `null` on its
     * failure). Fires immediately with `null` when nothing is loading.
     */
    fun awaitNext(key: String, onResult: (ApNativeAd?) -> Unit) {
        val executor = executors[key]
        if (executor == null || !executor.isInProgress()) {
            runCatching { onResult(executor?.poll()) }
        } else {
            executor.addWaiter(onResult)
        }
    }

    /**
     * Observes [key] without consuming: every fill fires `onNativeAdLoaded`, every
     * exhausted waterfall `onAdFailedToLoad`, clicks and impressions their counterparts.
     * The ad itself stays in the buffer until someone polls it.
     */
    fun registerAdCallback(key: String, adCallback: AdCallback) {
        executors.getOrPut(key) { PreloadExecutor() }.registerCallback(adCallback)
    }

    fun unregisterAdCallback(key: String, adCallback: AdCallback) {
        executors[key]?.unregisterCallback(adCallback)
    }

    /** Drops the buffer and destroys every buffered ad. */
    fun release(key: String) {
        executors.remove(key)?.release()
    }

    fun releaseAll() {
        val keys = executors.keys.toList()
        keys.forEach { release(it) }
    }

    /**
     * Cancels pending work and destroys buffered ads while retaining placement subscriptions.
     * Use for entitlement/policy changes; [releaseAll] also disposes the observers themselves.
     */
    fun invalidateBuffers() {
        executors.values.toList().forEach { it.invalidate() }
    }

    companion object {
        @Volatile
        private var instance: NativeAdPreload? = null

        @JvmStatic
        fun getInstance(): NativeAdPreload =
            instance ?: synchronized(this) {
                instance ?: NativeAdPreload().also { instance = it }
            }
    }

    /**
     * Per-key worker: loads sequentially — one waterfall at a time, batches queued behind
     * one another, matching the audited executor — into a FIFO buffer.
     */
    private class PreloadExecutor {

        private class BufferedNative(ad: ApNativeAd, val personalized: Boolean) {
            val cached = CachedAd(ad)
            val ad: ApNativeAd get() = cached.ad
            val isFresh: Boolean get() = cached.isFresh &&
                personalized == ConsentCenter.canPersonalize() && ConsentCenter.canRequestAds() &&
                !ConsentCenter.isFormShowing()
        }

        private class Batch(
            val activity: Activity,
            val config: NativeAdConfig,
            val buffer: Int,
        )

        private val lock = Any()
        private val queue = ArrayDeque<BufferedNative>()
        private var pending = 0
        private var batchInFlight = false
        private var released = false
        private var generation = 0L
        private var applicationContext: Context? = null
        private val deferredBatches = ArrayDeque<Batch>()
        private val waiters = mutableListOf<(ApNativeAd?) -> Unit>()
        private val callbacks = CopyOnWriteArrayList<AdCallback>()

        fun execute(activity: Activity, config: NativeAdConfig, buffer: Int) {
            val startGeneration = synchronized(lock) {
                if (released) return
                applicationContext = activity.applicationContext
                pending += buffer
                if (batchInFlight) {
                    deferredBatches.addLast(Batch(activity, config, buffer))
                    null
                } else {
                    batchInFlight = true
                    generation
                }
            }
            if (startGeneration != null) loadBatch(activity, config, buffer, startGeneration)
        }

        private fun loadBatch(activity: Activity, config: NativeAdConfig, remaining: Int, expectedGeneration: Long) {
            if (!isCurrent(expectedGeneration)) return
            if (remaining <= 0) {
                onBatchDone(expectedGeneration)
                return
            }
            if (AdGate.skipReason(activity, enabled = true) != null) {
                cancelPending(expectedGeneration)
                return
            }
            val personalized = ConsentCenter.canPersonalize()
            AdWaterfall.loadNative(
                activity,
                config.adUnitIds,
                config.layoutId,
                config.tierTimeoutMs,
                object : AdCallback() {
                    override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                        if (!isCurrent(expectedGeneration)) {
                            destroy(nativeAd)
                            return
                        }
                        if (AdGate.skipReason(activity, enabled = true, checkNetwork = false) != null ||
                            ConsentCenter.canPersonalize() != personalized) {
                            destroy(nativeAd)
                            cancelPending(expectedGeneration)
                            return
                        }
                        deliver(nativeAd, personalized, expectedGeneration)
                        if (isCurrent(expectedGeneration)) loadBatch(activity, config, remaining - 1, expectedGeneration)
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError?) {
                        if (!isCurrent(expectedGeneration)) return
                        if (AdGate.skipReason(activity, enabled = true, checkNetwork = false) != null ||
                            ConsentCenter.canPersonalize() != personalized) {
                            cancelPending(expectedGeneration)
                            return
                        }
                        deliver(null, personalized, expectedGeneration)
                        callbacks.forEach { runCatching { it.onAdFailedToLoad(adError) } }
                        if (isCurrent(expectedGeneration)) loadBatch(activity, config, remaining - 1, expectedGeneration)
                    }

                    override fun onAdClicked() {
                        if (!isCurrent(expectedGeneration)) return
                        callbacks.forEach { runCatching { it.onAdClicked() } }
                    }

                    override fun onAdOpened() {
                        if (!isCurrent(expectedGeneration)) return
                        callbacks.forEach { runCatching { it.onAdOpened() } }
                    }

                    override fun onAdImpression() {
                        if (!isCurrent(expectedGeneration)) return
                        callbacks.forEach { runCatching { it.onAdImpression() } }
                    }
                },
            )
        }

        private fun onBatchDone(expectedGeneration: Long) {
            val next = synchronized(lock) {
                if (released || generation != expectedGeneration) return
                deferredBatches.removeFirstOrNull().also { if (it == null) batchInFlight = false }
            }
            next?.let { loadBatch(it.activity, it.config, it.buffer, expectedGeneration) }
        }

        /** A policy change cancels this whole batch, including queued requests and all waiters. */
        private fun cancelPending(expectedGeneration: Long? = null) {
            val orphaned: List<(ApNativeAd?) -> Unit>
            val hadWork: Boolean
            synchronized(lock) {
                if (released || (expectedGeneration != null && generation != expectedGeneration)) return
                generation++
                hadWork = pending > 0 || queue.isNotEmpty()
                pending = 0
                batchInFlight = false
                deferredBatches.clear()
                queue.forEach { destroy(it.ad) }
                queue.clear()
                orphaned = waiters.toList()
                waiters.clear()
            }
            orphaned.forEach { runCatching { it.invoke(null) } }
            if (hadWork) callbacks.forEach { runCatching { it.onAdFailedToLoad(null) } }
        }

        private fun deliver(ad: ApNativeAd?, personalized: Boolean, expectedGeneration: Long) {
            val claimed = mutableListOf<Pair<(ApNativeAd?) -> Unit, ApNativeAd?>>()
            synchronized(lock) {
                // A fill landing after release() has no owner left — destroy, don't orphan
                if (released || generation != expectedGeneration) {
                    if (ad != null) destroy(ad)
                    return
                }
                if (pending > 0) pending--
                val first = waiters.removeFirstOrNull()
                // A waiter consumes the ad directly; only unclaimed fills are buffered
                if (first != null) claimed.add(first to ad)
                else if (ad != null) queue.addLast(BufferedNative(ad, personalized))
                // Nothing else will arrive: flush stragglers or they wait forever
                if (pending == 0) {
                    while (waiters.isNotEmpty()) {
                        dropStale()
                        claimed.add(waiters.removeAt(0) to queue.removeFirstOrNull()?.ad)
                    }
                }
            }
            claimed.forEach { (waiter, result) ->
                val current = isCurrent(expectedGeneration) && canReadBuffer() &&
                    personalized == ConsentCenter.canPersonalize()
                if (!current && result != null) destroy(result)
                runCatching { waiter.invoke(if (current) result else null) }
            }
            // Observers see every fill after it is buffered, so a bind on this signal hits
            if (ad != null) callbacks.forEach {
                if (isCurrent(expectedGeneration) && canReadBuffer() && personalized == ConsentCenter.canPersonalize()) {
                    runCatching { it.onNativeAdLoaded(ad) }
                }
            }
        }

        fun registerCallback(adCallback: AdCallback) {
            callbacks.addIfAbsent(adCallback)
        }

        fun unregisterCallback(adCallback: AdCallback) {
            callbacks.remove(adCallback)
        }

        fun peek(): ApNativeAd? {
            if (!canReadBuffer()) { cancelPending(); return null }
            return synchronized(lock) { dropStale(); queue.firstOrNull()?.ad }
        }

        fun poll(): ApNativeAd? {
            if (!canReadBuffer()) { cancelPending(); return null }
            return synchronized(lock) { dropStale(); queue.removeFirstOrNull()?.ad }
        }

        fun isInProgress(): Boolean = synchronized(lock) { pending > 0 }

        fun isAvailable(): Boolean {
            if (!canReadBuffer()) { cancelPending(); return false }
            return synchronized(lock) { dropStale(); pending > 0 || queue.isNotEmpty() }
        }

        fun buffer(): List<ApNativeAd> {
            if (!canReadBuffer()) { cancelPending(); return emptyList() }
            return synchronized(lock) { dropStale(); queue.map { it.ad } }
        }

        private fun canReadBuffer(): Boolean = applicationContext?.let {
            AdGate.skipReason(it, enabled = true, checkNetwork = false) == null
        } ?: true

        fun invalidate() = cancelPending()

        fun addWaiter(onResult: (ApNativeAd?) -> Unit) {
            synchronized(lock) { waiters.add(onResult) }
        }

        fun release() {
            val orphaned: List<(ApNativeAd?) -> Unit>
            synchronized(lock) {
                released = true
                generation++
                pending = 0
                batchInFlight = false
                queue.forEach { destroy(it.ad) }
                queue.clear()
                deferredBatches.clear()
                orphaned = waiters.toList()
                waiters.clear()
            }
            callbacks.clear()
            // A silently dropped waiter would pin its helper in Loading forever
            orphaned.forEach { runCatching { it.invoke(null) } }
        }

        private fun isCurrent(expectedGeneration: Long): Boolean =
            synchronized(lock) { !released && generation == expectedGeneration }

        private fun dropStale() {
            while (true) {
                val head = queue.firstOrNull() ?: return
                if (head.isFresh) return
                queue.removeFirst()
                destroy(head.ad)
            }
        }

        private fun destroy(ad: ApNativeAd) {
            runCatching { ad.admobNativeAd?.destroy() }
        }
    }
}
