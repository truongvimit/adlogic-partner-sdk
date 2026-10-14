package com.ads.module.helper.adnative.pool

import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A splash load the pool takes over while it is still running. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface PoolAdoption<H : Any> {
    /** Forwards the adopted ad's impressions and clicks; its onLoaded/onFailed equal [complete]. */
    val sink: PoolLoadSink<H>

    /** An ad fills the loader like a chain would; null ends it like an exhausted chain. Only the first call counts. */
    fun complete(ad: H?)
}

/**
 * The process-wide native pool: one loader per placement key and one selection every slot renders.
 * Main thread only; the gate and the selection may be derived on `derivedDispatcher`. A late fill of
 * a timed-out, superseded or released attempt is destroyed unshown; impressions and clicks are never
 * filtered, they carry the ad that produced them.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@MainThread
class NativePoolEngine<H : Any>(
    loader: PoolAdLoader<H>,
    policy: PoolRequestPolicy,
    parity: PoolParity,
    private val clock: PoolClock,
    private val scheduler: PoolScheduler,
    private val expiry: PoolExpiry,
    derivedDispatcher: CoroutineDispatcher,
    private val destroyAd: (H) -> Unit = {},
    trace: PoolTraceSink = PoolTraceSink.NONE,
    stuckLoadingLogMs: Long = 60_000,
) {
    private class Loader<H : Any>(val key: String, val index: Int) {
        var state: PoolState = PoolState.None
        var ad: H? = null
        var fillAtMs = 0L
        var loadedTs = 0L
        var inFlight = false
        var refreshing = false
        var savedRequest: PoolRequest? = null
        var adopted = false
        var chainToken = 0L
        var attemptToken = 0L
        var deadline: PoolCancellable? = null
        var stuckWatch: PoolCancellable? = null
        var expirySweep: PoolCancellable? = null

        fun view(): PoolLoaderView<H> =
            PoolLoaderView(key, state, ad, inFlight, refreshing, if (ad != null) fillAtMs else 0L, index, adopted)
    }

    private val adLoader = loader
    private val asyncGate = parity.asyncGate
    private val asyncSelector = parity.asyncSelector

    /** Read between runs; [PoolParity.asyncGate] and [PoolParity.asyncSelector] keep their first values. */
    @Volatile
    var parity: PoolParity = parity
        set(value) {
            val fixed = value.copy(asyncGate = asyncGate, asyncSelector = asyncSelector)
            if (fixed == field) return
            field = fixed
            publish()
        }

    var policy: PoolRequestPolicy = policy
    var trace: PoolTraceSink = trace
    var stuckLoadingLogMs: Long = stuckLoadingLogMs

    private val loaders = LinkedHashMap<String, Loader<H>>()
    private val pendingPolite = LinkedHashMap<String, PoolRequest>()
    private val listeners = mutableListOf<(PoolEvent<H>) -> Unit>()
    private val replayListeners = mutableListOf<(PoolRequest, LoadResult) -> Unit>()
    private var replayPost: PoolCancellable? = null
    private var nextIndex = 0
    private var lastTs = 0L
    private var lastToken = 0L
    private var version = 0L
    private var replaying = false

    private val mutableSnapshot = MutableStateFlow(PoolSnapshot.empty<H>())
    val snapshot: StateFlow<PoolSnapshot<H>> = mutableSnapshot.asStateFlow()

    private val derived = PoolDerived(
        mutableSnapshot, asyncGate, asyncSelector, derivedDispatcher,
        gateOf = { !anyInFlight(it, this.parity) },
        selectionOf = { selectFromPool(it, this.parity, clock.nowMs(), expiry.maxAgeMs()) },
    )

    /** POLITE loads go out only while this is true; it is true for an empty pool. */
    val gateOpen: StateFlow<Boolean> = derived.gate

    /** What every slot renders. The async variant has no value until the first snapshot is mapped. */
    val selection: Flow<PoolSelection<H>> = derived.selection

    var owner: String? = null
        private set

    var gateSkipCount: Int = 0
        private set

    fun currentSelection(): PoolSelection<H> =
        selectFromPool(mutableSnapshot.value, parity, clock.nowMs(), expiry.maxAgeMs())

    fun debugSnapshot(): PoolSnapshot<H> = mutableSnapshot.value

    fun setOwner(key: String?) {
        owner = key
        key?.let { loaders[it] }?.let(::sweepIfExpired)
        publish()
    }

    fun clearOwnerIf(key: String) {
        if (owner == key) owner = null
        loaders[key]?.let(::sweepIfExpired)
        publish()
    }

    /** Only a config refusal leaves no loader behind; see [LoadResult] for what each outcome saves. */
    fun load(request: PoolRequest, mode: LoadMode): LoadResult = load(request, mode) { gateOpen.value }

    /** Repeats the saved request from its first id while the held ad keeps showing; false when it cannot. */
    fun refresh(key: String): Boolean {
        val l = loaders[key] ?: return false
        val request = l.savedRequest ?: return false
        if (sweepIfExpired(l)) publish()
        val refreshable = when (l.state) {
            is PoolState.Showing -> true
            is PoolState.Loaded -> parity.refreshOverwritesUnshown
            else -> false
        }
        if (l.ad == null || !refreshable || l.refreshing || l.inFlight) return false
        val refusal = policy.configRefusal(request) ?: policy.transientRefusal(request)
        if (refusal != null) {
            log("refresh_skip key=$key reason=$refusal")
            return false
        }
        l.refreshing = true
        publish()
        runChain(l, request, isRefresh = true)
        return true
    }

    /** Takes over a splash fill: the loader holds [ad] as Loaded; the returned sink carries its events. */
    fun adoptReady(request: PoolRequest, ad: H): PoolLoadSink<H> {
        val l = loaderFor(request.key)
        cancelChain(l)
        pendingPolite.remove(request.key)
        l.savedRequest = request
        hold(l, ad)
        l.adopted = true
        log("adopt key=${l.key} status=ready")
        publish(PoolEvent.Filled(l.key, ad, refresh = false))
        return ForwardingSink(l, onResult = null)
    }

    /**
     * Takes over a splash load still in flight; the loader counts as in flight until
     * [PoolAdoption.complete]. A held ad stays selectable meanwhile, but is not marked adopted: only
     * [adoptReady] hands over an ad the splash actually delivered.
     */
    fun adoptPending(request: PoolRequest): PoolAdoption<H> {
        val l = loaderFor(request.key)
        cancelChain(l)
        pendingPolite.remove(request.key)
        l.savedRequest = request
        if (l.ad == null) l.state = PoolState.None
        l.inFlight = true
        l.adopted = false
        val chain = newToken()
        val token = newToken()
        l.chainToken = chain
        l.attemptToken = token
        armStuckWatch(l, chain)
        log("adopt key=${l.key} status=pending")
        publish()
        return object : PoolAdoption<H> {
            override val sink: PoolLoadSink<H> = ForwardingSink(l) { complete(it) }

            override fun complete(ad: H?) {
                if (loaders[l.key] !== l || l.attemptToken != token) {
                    if (ad != null && ad !== l.ad) dropStale(l.key, ad)
                    return
                }
                if (ad != null) fill(l, ad, isRefresh = false) else endChain(l, isRefresh = false, why = "adoption_failed")
            }
        }
    }

    fun isAdoptedUnshown(key: String): Boolean {
        val l = loaders[key] ?: return false
        return l.adopted && l.state is PoolState.Loaded && !isExpired(l)
    }

    fun addEventListener(listener: (PoolEvent<H>) -> Unit): PoolCancellable {
        listeners += listener
        return PoolCancellable { listeners -= listener }
    }

    /**
     * Hears each POLITE load [PoolParity.retrySkippedPolite] replays, with its result. A replay has no
     * caller to return that result to, so this is where its request reaches telemetry.
     */
    fun addReplayListener(listener: (PoolRequest, LoadResult) -> Unit): PoolCancellable {
        replayListeners += listener
        return PoolCancellable { replayListeners -= listener }
    }

    /** Ends every chain, destroys every held ad and forgets every loader and the owner. */
    fun releaseAll() {
        val released = loaders.values.toList()
        released.forEach {
            cancelChain(it)
            it.expirySweep?.cancel()
            it.expirySweep = null
        }
        loaders.clear()
        clearPendingPolite()
        owner = null
        log("release_all loaders=${released.size}")
        publish()
        released.forEach { l -> l.ad?.let(destroyAd) }
        released.forEach { it.ad = null }
    }

    fun onNewRun() {
        owner = null
        clearPendingPolite()
        if (parity.resetOnNewRun) releaseAll() else publish()
    }

    /** One line per loader after a header with the owner, the gate and the skip count. */
    fun dumpState(): String = buildString {
        append("owner=").append(owner)
            .append(" gate=").append(if (gateOpen.value) "open" else "closed")
            .append(" gateSkipCount=").append(gateSkipCount)
            .append(" pending=").append(pendingPolite.keys)
        loaders.values.forEach { l ->
            append('\n').append(l.key).append(" #").append(l.index)
                .append(" state=").append(l.state)
                .append(" ad=").append(if (l.ad != null) "held" else "none")
                .append(" inFlight=").append(l.inFlight)
                .append(" refreshing=").append(l.refreshing)
                .append(" adopted=").append(l.adopted)
                .append(" fillAtMs=").append(l.fillAtMs)
                .append(" saved=").append(l.savedRequest?.ids?.size ?: 0)
        }
    }

    fun close() = derived.close()

    private fun load(request: PoolRequest, mode: LoadMode, gateIsOpen: () -> Boolean): LoadResult {
        policy.configRefusal(request)?.let { return skipped(request.key, LoadResult.SkippedByConfig(it)) }
        val created = !loaders.containsKey(request.key)
        val l = loaderFor(request.key)
        val changed = sweepIfExpired(l) || created
        if (mode == LoadMode.POLITE && !gateIsOpen()) {
            gateSkipCount++
            if (changed) publish()
            // With the gate open this would have been a no-op (held ad, own chain running): no retry is owed.
            if (parity.retrySkippedPolite && l.ad == null && !l.inFlight && !l.refreshing) queuePolite(request)
            return skipped(request.key, LoadResult.SkippedByGate)
        }
        pendingPolite.remove(request.key)
        l.savedRequest = request
        policy.transientRefusal(request)?.let {
            if (changed) publish()
            return skipped(request.key, LoadResult.SkippedTransient(it))
        }
        if (l.ad != null || l.inFlight || l.refreshing) {
            if (changed) publish()
            return LoadResult.NoOpState(l.state, l.inFlight || l.refreshing)
        }
        l.state = PoolState.None
        l.inFlight = true
        l.adopted = false
        publish()
        runChain(l, request, isRefresh = false)
        return LoadResult.Requested
    }

    private fun skipped(key: String, result: LoadResult): LoadResult {
        val line = when (result) {
            is LoadResult.SkippedByConfig -> "config_skip key=$key reason=${result.reason}"
            is LoadResult.SkippedTransient -> "transient_skip key=$key reason=${result.reason}"
            else -> "gate_skip key=$key"
        }
        log(line)
        emit(PoolEvent.Skipped(key, result))
        return result
    }

    private fun loaderFor(key: String): Loader<H> = loaders.getOrPut(key) { Loader(key, nextIndex++) }

    private fun runChain(l: Loader<H>, request: PoolRequest, isRefresh: Boolean) {
        val chain = newToken()
        l.chainToken = chain
        armStuckWatch(l, chain)
        requestFrom(l, request, 0, isRefresh, chain)
    }

    private fun requestFrom(l: Loader<H>, request: PoolRequest, start: Int, isRefresh: Boolean, chain: Long) {
        val index = (start until request.ids.size).firstOrNull { request.ids[it].isNotBlank() }
            ?: return endChain(l, isRefresh, why = "exhausted")
        val refusal = policy.configRefusal(request) ?: policy.transientRefusal(request)
        if (refusal != null) return endChain(l, isRefresh, why = "refused reason=$refusal")
        val attempt = Attempt(l, request, index, isRefresh, chain, newToken())
        l.attemptToken = attempt.token
        if (request.tierDeadlineMs > 0) {
            l.deadline = scheduler.postDelayed(request.tierDeadlineMs) { onDeadline(attempt) }
        }
        log("request key=${l.key} tier=$index refresh=$isRefresh")
        try {
            adLoader.load(request, request.ids[index], attempt)
        } catch (e: Exception) {
            log("load_threw key=${l.key} tier=$index error=${e.javaClass.simpleName}")
            if (attempt.isCurrent()) next(attempt)
        }
    }

    private fun onDeadline(attempt: Attempt) {
        if (!attempt.isCurrent()) return
        attempt.loader.deadline = null
        log("tier_timeout key=${attempt.loader.key} tier=${attempt.index}")
        next(attempt)
    }

    private fun next(attempt: Attempt) {
        attempt.loader.deadline?.cancel()
        attempt.loader.deadline = null
        requestFrom(attempt.loader, attempt.request, attempt.index + 1, attempt.isRefresh, attempt.chain)
    }

    private fun fill(l: Loader<H>, ad: H, isRefresh: Boolean) {
        finishChain(l)
        hold(l, ad)
        l.inFlight = false
        l.refreshing = false
        l.adopted = false
        log("fill key=${l.key} refresh=$isRefresh")
        publish(PoolEvent.Filled(l.key, ad, isRefresh))
    }

    private fun hold(l: Loader<H>, ad: H) {
        val old = l.ad
        if (old != null && old !== ad && parity.destroyReplacedAds) destroyAd(old)
        l.ad = ad
        l.loadedTs = nextTs()
        l.state = PoolState.Loaded(l.loadedTs)
        l.fillAtMs = clock.nowMs()
        scheduleExpiry(l, ad, expiry.maxAgeMs())
    }

    private fun endChain(l: Loader<H>, isRefresh: Boolean, why: String) {
        finishChain(l)
        if (isRefresh) {
            l.refreshing = false
        } else {
            l.inFlight = false
            l.state = if (l.ad != null) PoolState.Loaded(l.loadedTs) else PoolState.LoadError
        }
        log("exhausted key=${l.key} refresh=$isRefresh why=$why")
        publish(PoolEvent.Exhausted(l.key, isRefresh))
    }

    private fun finishChain(l: Loader<H>) {
        l.deadline?.cancel()
        l.deadline = null
        l.stuckWatch?.cancel()
        l.stuckWatch = null
        l.attemptToken = 0L
    }

    private fun cancelChain(l: Loader<H>) {
        finishChain(l)
        l.inFlight = false
        l.refreshing = false
    }

    private fun armStuckWatch(l: Loader<H>, chain: Long) {
        val after = stuckLoadingLogMs
        if (after <= 0) return
        l.stuckWatch = scheduler.postDelayed(after) {
            if (loaders[l.key] === l && l.chainToken == chain && (l.inFlight || l.refreshing)) {
                log("stuck_loading key=${l.key} refresh=${l.refreshing} ms=$after")
            }
        }
    }

    private fun markImpression(l: Loader<H>, ad: H) {
        val marks = loaders[l.key] === l && l.ad != null && (parity.impressionMarksLoader || ad === l.ad)
        if (marks) {
            l.state = PoolState.Showing(nextTs())
            publish(PoolEvent.Impression(l.key, ad))
        } else {
            emit(PoolEvent.Impression(l.key, ad))
        }
    }

    private fun dropStale(key: String, ad: H) {
        log("stale_fill key=$key")
        destroyAd(ad)
    }

    private fun isExpired(l: Loader<H>): Boolean =
        l.ad != null && isExpiredAt(l.fillAtMs, clock.nowMs(), expiry.maxAgeMs())

    private fun sweepIfExpired(l: Loader<H>): Boolean {
        val old = l.ad
        if (old == null || !isExpired(l)) return false
        l.ad = null
        l.state = PoolState.None
        l.fillAtMs = 0L
        l.loadedTs = 0L
        l.adopted = false
        l.expirySweep?.cancel()
        l.expirySweep = null
        log("expired key=${l.key}")
        if (parity.destroyReplacedAds) destroyAd(old)
        return true
    }

    private fun scheduleExpiry(l: Loader<H>, ad: H, delayMs: Long) {
        l.expirySweep?.cancel()
        l.expirySweep = null
        if (delayMs <= 0) return
        l.expirySweep = scheduler.postDelayed(delayMs) {
            l.expirySweep = null
            if (loaders[l.key] !== l || l.ad !== ad) return@postDelayed
            if (sweepIfExpired(l)) {
                publish()
            } else {
                val maxAge = expiry.maxAgeMs()
                if (maxAge > 0) scheduleExpiry(l, ad, maxOf(1L, maxAge - (clock.nowMs() - l.fillAtMs)))
            }
        }
    }

    // Order matters: listeners and replayed loads must see the new snapshot and the sync gate.
    private fun publish(event: PoolEvent<H>? = null) {
        val snapshot = PoolSnapshot(loaders.values.map { it.view() }, owner, ++version)
        mutableSnapshot.value = snapshot
        derived.onPublish(snapshot)
        event?.let(::emit)
        replayPendingPolite()
    }

    /**
     * Queued after the skip's own publish, so the skipped call never turns into a request. An async
     * gate can lag an idle pool, and then no later publish is due: the replay is posted instead.
     */
    private fun queuePolite(request: PoolRequest) {
        pendingPolite[request.key] = request
        if (replayPost == null && !anyInFlight(mutableSnapshot.value, parity)) {
            replayPost = scheduler.postDelayed(0) {
                replayPost = null
                replayPendingPolite()
            }
        }
    }

    private fun clearPendingPolite() {
        pendingPolite.clear()
        replayPost?.cancel()
        replayPost = null
    }

    private fun replayPendingPolite() {
        if (replaying || pendingPolite.isEmpty() || !parity.retrySkippedPolite) return
        replaying = true
        try {
            val gateIsOpen = { !anyInFlight(mutableSnapshot.value, parity) }
            while (pendingPolite.isNotEmpty() && gateIsOpen()) {
                val request = pendingPolite.values.first()
                pendingPolite.remove(request.key)
                log("replay_polite key=${request.key}")
                val result = load(request, LoadMode.POLITE, gateIsOpen)
                replayListeners.toList().forEach { listener ->
                    try {
                        listener(request, result)
                    } catch (e: Exception) {
                        log("listener_threw key=${request.key} error=${e.javaClass.simpleName}")
                    }
                }
            }
        } finally {
            replaying = false
        }
    }

    private fun emit(event: PoolEvent<H>) {
        listeners.toList().forEach { listener ->
            try {
                listener(event)
            } catch (e: Exception) {
                log("listener_threw key=${event.key} error=${e.javaClass.simpleName}")
            }
        }
    }

    private fun log(line: String) = trace.trace(line)

    private fun nextTs(): Long = maxOf(clock.nowMs(), lastTs + 1).also { lastTs = it }

    private fun newToken(): Long = ++lastToken

    private inner class Attempt(
        val loader: Loader<H>,
        val request: PoolRequest,
        val index: Int,
        val isRefresh: Boolean,
        val chain: Long,
        val token: Long,
    ) : PoolLoadSink<H> {
        fun isCurrent(): Boolean = loaders[loader.key] === loader && loader.attemptToken == token

        override fun onLoaded(ad: H) {
            if (isCurrent()) fill(loader, ad, isRefresh) else if (ad !== loader.ad) dropStale(loader.key, ad)
        }

        override fun onFailed() {
            if (!isCurrent()) return
            log("fail key=${loader.key} tier=$index")
            next(this)
        }

        override fun onImpression(ad: H) = markImpression(loader, ad)

        override fun onClicked(ad: H) = emit(PoolEvent.Clicked(loader.key, ad))
    }

    private inner class ForwardingSink(
        private val loader: Loader<H>,
        private val onResult: ((H?) -> Unit)?,
    ) : PoolLoadSink<H> {
        override fun onLoaded(ad: H) {
            onResult?.invoke(ad)
        }

        override fun onFailed() {
            onResult?.invoke(null)
        }

        override fun onImpression(ad: H) = markImpression(loader, ad)

        override fun onClicked(ad: H) = emit(PoolEvent.Clicked(loader.key, ad))
    }
}
