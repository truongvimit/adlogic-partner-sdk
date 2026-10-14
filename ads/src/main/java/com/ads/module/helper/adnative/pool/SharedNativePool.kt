package com.ads.module.helper.adnative.pool

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.adnative.NativeAdManager
import kotlinx.coroutines.Dispatchers
import java.lang.ref.WeakReference

/** The process's one native pool, built by the first [configure] and never before. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object SharedNativePool {
    @Volatile
    private var engine: NativePoolEngine<ApNativeAd>? = null

    /**
     * The adoption each joined manager load completes. One manager waiter per key: a second join
     * of the same load re-adopts instead of adding a waiter that would split its fill.
     */
    private val joined = HashMap<String, PoolAdoption<ApNativeAd>>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Later calls update the same engine; its async gate and selector keep their first values. */
    @MainThread
    fun configure(
        context: Context,
        parity: PoolParity,
        policy: PoolRequestPolicy,
        trace: PoolTraceSink,
        stuckLoadingLogMs: Long,
    ): NativePoolEngine<ApNativeAd> {
        assertMain()
        engine?.let { current ->
            current.policy = policy
            current.trace = trace
            current.stuckLoadingLogMs = stuckLoadingLogMs
            current.parity = parity
            return current
        }
        return NativePoolEngine(
            loader = AdMobPoolLoader(context.applicationContext),
            policy = policy,
            parity = parity,
            clock = SystemPoolClock,
            scheduler = MainLooperPoolScheduler(),
            expiry = PoolExpiry { AdBehavior.number("native.cache.max_age_ms") },
            derivedDispatcher = Dispatchers.Default,
            destroyAd = { it.destroy() },
            trace = trace,
            stuckLoadingLogMs = stuckLoadingLogMs,
        ).also { engine = it }
    }

    fun engineOrNull(): NativePoolEngine<ApNativeAd>? = engine

    /** Creates nothing. Callable from any thread: off main the release is posted, not refused. */
    fun releaseAllIfCreated() {
        if (engine == null) return
        if (isMainThread()) {
            engine?.releaseAll()
        } else {
            main.post { engine?.releaseAll() }
        }
    }

    /** Moves the manager's unused fill into the pool; on false the manager keeps it. */
    @MainThread
    fun adoptReadyFromManager(request: PoolRequest): Boolean {
        assertMain()
        val pool = engine ?: return false
        val ad = NativeAdManager.poll(request.key) ?: return false
        val sink = pool.adoptReady(request, ad)
        NativeAdManager.observe(ad, eventsInto(ad, sink))
        return true
    }

    /**
     * Joins the manager's running load; a release of that placement while waiting is a failure.
     * The outcome reaches the engine on main even when the manager is released from another thread.
     */
    @MainThread
    fun adoptPendingFromManager(request: PoolRequest): Boolean {
        assertMain()
        val pool = engine ?: return false
        if (!NativeAdManager.isLoading(request.key)) return false
        val waiting = joined.containsKey(request.key)
        joined[request.key] = pool.adoptPending(request)
        if (!waiting) {
            NativeAdManager.awaitNext(request.key) { ad ->
                if (isMainThread()) deliver(request.key, ad) else main.post { deliver(request.key, ad) }
            }
        }
        return true
    }

    @VisibleForTesting
    fun installForTest(engine: NativePoolEngine<ApNativeAd>) {
        this.engine = engine
    }

    @VisibleForTesting
    fun resetForTest() {
        joined.clear()
        val current = engine ?: return
        engine = null
        current.releaseAll()
        current.close()
    }

    /**
     * Holds [ad] weakly: the manager keys its listeners weakly by that same ad, and a value that
     * referenced its key would pin the ad for the life of the process. A slot showing the ad, or
     * the loader holding it, keeps it alive while its events still matter.
     */
    @VisibleForTesting
    internal fun eventsInto(ad: ApNativeAd, sink: PoolLoadSink<ApNativeAd>): AdCallback {
        val ref = WeakReference(ad)
        return object : AdCallback() {
            override fun onAdImpression() {
                ref.get()?.let(sink::onImpression)
            }

            override fun onAdClicked() {
                ref.get()?.let(sink::onClicked)
            }

            override fun onAdOpened() {
                ref.get()?.let(sink::onClicked)
            }
        }
    }

    private fun deliver(key: String, ad: ApNativeAd?) {
        val adoption = joined.remove(key)
        if (adoption == null) {
            // The pool was reset while waiting: nobody else will spend the fill the manager handed over.
            ad?.let(NativeAdManager::dispose)
            return
        }
        adoption.complete(ad)
        // A released or superseded adoption destroyed the fill; only an ad the pool kept is observed.
        if (ad != null && engine?.debugSnapshot()?.loader(key)?.ad === ad) {
            NativeAdManager.observe(ad, eventsInto(ad, adoption.sink))
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    private fun assertMain() = check(isMainThread()) { "SharedNativePool is main-thread only" }
}
