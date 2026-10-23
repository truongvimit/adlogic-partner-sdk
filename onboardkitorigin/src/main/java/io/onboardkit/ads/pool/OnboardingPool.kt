package io.onboardkit.ads.pool

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.pool.CarouselSelection
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolCancellable
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolSnapshot
import com.ads.module.helper.adnative.pool.PoolState
import com.ads.module.helper.adnative.pool.PoolTraceSink
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.reportsSuccess
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.carouselBase
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.core.ObLog
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.flow.NativeFlowSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The onboarding side of the process-wide native pool: switched on per SHARED_POOL run, it turns
 * placements into pool requests and reports each outcome to flow analytics the way the classic
 * flow does. Pool calls are main-thread only; the run switches hop to main themselves, and the
 * latest switch wins even when an earlier one is still queued there.
 */
internal object OnboardingPool {
    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong()
    private val results = ConcurrentHashMap<String, LoadResult>()
    private val runGateSkips = ConcurrentHashMap<String, Int>()
    private val listeners = CopyOnWriteArrayList<(PoolEvent<ApNativeAd>) -> Unit>()
    private var attached: NativePoolEngine<ApNativeAd>? = null
    private var detach: List<PoolCancellable> = emptyList()
    private val trace = PoolTraceSink { line -> ObLog.d(ObLog.Section.LOAD, "pool | $line") }

    /**
     * The run whose activation last landed on main. Both flags read it against [epoch], so a switch
     * that started later turns them off at once and no earlier switch can turn them back.
     */
    @Volatile
    private var liveRun = NO_RUN

    @Volatile
    private var snapshot = NativeFlowSnapshot()

    /** Native resume opens the pooled resume screen while this holds; a classic run clears it. */
    val routeResume: Boolean get() = liveRun == epoch.get()

    val isActive: Boolean get() = routeResume && engineOrNull() != null

    /** What every pooled slot renders; hidden while no pool exists. */
    val selection: Flow<PoolSelection<ApNativeAd>> = flow {
        val engine = engineOrNull()
        if (engine == null) emit(PoolSelection.hidden()) else emitAll(engine.selection)
    }

    /** The two keys an LFO carousel's pages show, and the ad each page's view holds now. */
    class CarouselPages(
        val firstKey: () -> String,
        val secondKey: () -> String,
        val firstShown: () -> ApNativeAd?,
        val secondShown: () -> ApNativeAd?,
    )

    /** What an LFO carousel's two pages render, re-decided on every pool change. */
    fun carouselSelection(pages: CarouselPages): Flow<CarouselSelection<ApNativeAd>?> = flow {
        val engine = engineOrNull()
        if (engine == null) {
            emit(null)
        } else {
            emitAll(engine.snapshot.map { snapshot -> carouselSelectionOf(engine, snapshot, pages) }.distinctUntilChanged())
        }
    }

    /** [carouselSelection] of the pool as it is now. */
    @MainThread
    fun currentCarouselSelection(pages: CarouselPages): CarouselSelection<ApNativeAd>? =
        engineOrNull()?.let { carouselSelectionOf(it, it.debugSnapshot(), pages) }

    private fun carouselSelectionOf(
        engine: NativePoolEngine<ApNativeAd>,
        snapshot: PoolSnapshot<ApNativeAd>,
        pages: CarouselPages,
    ): CarouselSelection<ApNativeAd> =
        engine.carouselSelection(snapshot, pages.firstKey(), pages.secondKey(), pages.firstShown(), pages.secondShown())

    /** [placement]'s loader state; null before anything loaded it. */
    @MainThread
    fun loaderState(placement: AdPlacement): PoolState? =
        engineOrNull()?.debugSnapshot()?.loader(placement.key)?.state

    /** A load or a refresh of [placement]'s loader is running. */
    @MainThread
    fun isLoading(placement: AdPlacement): Boolean =
        engineOrNull()?.debugSnapshot()?.loader(placement.key)?.let { it.inFlight || it.refreshing } == true

    /** [placement]'s loader holds an ad, shown or not, or is loading one. */
    @MainThread
    fun holdsOrLoads(placement: AdPlacement): Boolean =
        engineOrNull()?.debugSnapshot()?.loader(placement.key)?.let { it.ad != null || it.inFlight || it.refreshing } == true

    /**
     * A carousel page asks for a new ad, and only while no loader of the pool has a load running,
     * read synchronously: however fast the user swipes, the carousel never has two ads loading.
     * With [refreshHeld] a held ad is refreshed (it stays shown until the new one fills, and on a
     * failure); an empty loader loads POLITE. The owner is untouched. False when nothing started.
     */
    @MainThread
    fun loadCarouselPage(placement: AdPlacement, refreshHeld: Boolean): Boolean {
        val engine = engineOrNull() ?: return false
        if (engine.anyLoadInFlight()) {
            ObLog.d(ObLog.Section.LOAD, "pool | carousel_gate_skip key=${placement.key}")
            return false
        }
        val held = engine.debugSnapshot().loader(placement.key)?.ad != null
        if (held) return refreshHeld && engine.refresh(placement.key)
        return load(placement, LoadMode.POLITE) == LoadResult.Requested
    }

    /** The pool loads through the ERain ad stack; a test engine stands in for it. */
    fun canRun(): Boolean = OnboardingSdk.provider() is ERainAdProvider || engineOrNull() != null

    fun engineOrNull(): NativePoolEngine<ApNativeAd>? = SharedNativePool.engineOrNull()

    /** Every activation starts a new run: no owner, the pool keeps its ads. */
    fun activate(appContext: Context, snapshot: NativeFlowSnapshot) {
        val run = epoch.incrementAndGet()
        onMain {
            if (epoch.get() != run) return@onMain
            val engine = SharedNativePool.configure(
                appContext, snapshot.parity, OnboardingPoolPolicy(appContext), trace, snapshot.stuckLoadingLogMs,
            )
            this.snapshot = snapshot
            results.clear()
            runGateSkips.clear()
            attach(engine)
            engine.onNewRun()
            liveRun = run
            ObLog.d(ObLog.Section.LOAD, "pool | activate")
        }
    }

    /** A PER_SCREEN run starts: resume goes back to the classic screen and pooled ads are released. */
    fun onClassicRun() = stop("classic_run")

    /** [onClassicRun] for `OnboardingSdk.reset()`. */
    fun deactivate() = stop("deactivate")

    /** Owns the slot whenever DashDrama would: on every outcome but a config refusal. */
    @MainThread
    fun claim(placement: AdPlacement, mode: LoadMode): LoadResult {
        val result = load(placement, mode)
        if (result.reportsSuccess) engineOrNull()?.setOwner(placement.key)
        return result
    }

    /** A load that leaves the owner alone; POLITE unless the caller warms a full page ahead. */
    @MainThread
    fun preload(placement: AdPlacement, mode: LoadMode = LoadMode.POLITE): LoadResult = load(placement, mode)

    @MainThread
    fun refresh(placement: AdPlacement): Boolean {
        val engine = engineOrNull() ?: return false
        return engine.refresh(placement.key)
    }

    /**
     * [refresh] that waits its turn like a POLITE load: skipped, not queued, while any loader has a
     * request in flight. The pooled resume screen renews its ad this way and never jumps the gate.
     */
    @MainThread
    fun refreshPolite(placement: AdPlacement): Boolean {
        val engine = engineOrNull() ?: return false
        if (!engine.gateOpen.value) {
            ObLog.d(ObLog.Section.LOAD, "pool | gate_skip refresh key=${placement.key}")
            return false
        }
        return refresh(placement)
    }

    @MainThread
    fun setOwner(placement: AdPlacement?) {
        engineOrNull()?.setOwner(placement?.key)
    }

    @MainThread
    fun clearOwnerIf(placement: AdPlacement) {
        engineOrNull()?.clearOwnerIf(placement.key)
    }

    /** Drops the pooled ads but keeps the run's routing: a purchase, or the end of a non-keeping flow. */
    fun releaseAll() = SharedNativePool.releaseAllIfCreated()

    /** Survives an engine swap; listeners hear Skipped events after [lastResult] already holds them. */
    fun addEventListener(listener: (PoolEvent<ApNativeAd>) -> Unit): PoolCancellable {
        listeners += listener
        return PoolCancellable { listeners -= listener }
    }

    /** The latest load result for [placement] in this run, replays included; an owner slot hides on a config refusal. */
    fun lastResult(placement: AdPlacement): LoadResult? = results[placement.key]

    /** Run state, this run's gate skips (which no analytics event carries) and the engine's own dump. Main thread. */
    fun dump(): String = buildString {
        append("pool active=").append(isActive)
            .append(" routeResume=").append(routeResume)
            .append(" runGateSkips=").append(runGateSkips.values.sum())
            .append(' ').append(runGateSkips.toSortedMap())
        engineOrNull()?.let { append('\n').append(it.dumpState()) }
    }

    @VisibleForTesting
    fun resetForTest() {
        epoch.incrementAndGet()
        detach.forEach(PoolCancellable::cancel)
        detach = emptyList()
        attached = null
        listeners.clear()
        results.clear()
        runGateSkips.clear()
        liveRun = NO_RUN
        snapshot = NativeFlowSnapshot()
    }

    /** The epoch bump alone turns routing off: a flag written here could land after a later activation. */
    private fun stop(why: String) {
        val run = epoch.incrementAndGet()
        onMain {
            if (epoch.get() != run) return@onMain
            results.clear()
            engineOrNull()?.releaseAll()
            ObLog.d(ObLog.Section.LOAD, "pool | $why")
        }
    }

    private fun load(placement: AdPlacement, mode: LoadMode): LoadResult {
        val engine = engineOrNull()
        val request = PoolPlacementMapper.requestFor(placement, snapshot)
        if (engine == null || request == null) {
            val reason = if (engine == null) AdSkipReason.NO_PROVIDER else AdSkipReason.NO_AD_UNIT
            ObLog.w(ObLog.Section.LOAD, "pool | load_refused key=${placement.key} reason=${reason.key}")
            return LoadResult.SkippedByConfig(reason.key).also { results[placement.key] = it }
        }
        return engine.load(request, mode).also { record(placement, it) }
    }

    /**
     * Requests and refusals go to analytics as in the classic flow; a gate skip only to the trace.
     * A carousel's second page reports as its LFO: the same placement, on the same ad_config key.
     */
    private fun record(placement: AdPlacement, result: LoadResult) {
        results[placement.key] = result
        val reported = placement.carouselBase
        when (result) {
            LoadResult.Requested -> reported.trackRequest()
            is LoadResult.SkippedTransient -> trackSkip(reported, result.reason)
            is LoadResult.SkippedByConfig -> trackSkip(reported, result.reason)
            LoadResult.SkippedByGate -> runGateSkips.merge(placement.key, 1, Int::plus)
            is LoadResult.NoOpState -> Unit
        }
    }

    private fun trackSkip(placement: AdPlacement, reason: String) {
        val skip = skipReasonOf(reason)
        if (skip != null) placement.trackSkipped(skip)
        else ObLog.w(ObLog.Section.LOAD, "pool | untracked_skip key=${placement.key} reason=$reason")
    }

    private fun attach(engine: NativePoolEngine<ApNativeAd>) {
        if (attached === engine) return
        detach.forEach(PoolCancellable::cancel)
        attached = engine
        detach = listOf(
            engine.addEventListener(::dispatch),
            // A replay has no caller: this is the only place its request reaches analytics.
            engine.addReplayListener { request, result ->
                PoolPlacementMapper.placementFor(request.key)?.let { record(it, result) }
                    ?: run { results[request.key] = result }
            },
        )
    }

    private fun dispatch(event: PoolEvent<ApNativeAd>) {
        // A refusal is emitted inside load(), before claim() returns: record it first so a slot
        // reacting to the event already reads it through lastResult.
        if (event is PoolEvent.Skipped) results[event.key] = event.result
        if (event is PoolEvent.Exhausted) reportNoFill(event)
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (e: RuntimeException) {
                ObLog.w(ObLog.Section.LOAD, "pool | listener_threw key=${event.key} error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The classic slot's ob_ad_failed, once per load chain that ends holding nothing, as
     * ad_request is once per chain. A refresh that fails keeps its ad and is no failure.
     */
    private fun reportNoFill(event: PoolEvent.Exhausted<ApNativeAd>) {
        if (event.refresh || engineOrNull()?.debugSnapshot()?.loader(event.key)?.ad != null) return
        val placement = PoolPlacementMapper.placementFor(event.key)?.carouselBase ?: return
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) {
            OnboardingSdk.track(AnalyticsEvent.AdFailed(placement.key, placement.format))
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }

    private const val NO_RUN = -1L
}
