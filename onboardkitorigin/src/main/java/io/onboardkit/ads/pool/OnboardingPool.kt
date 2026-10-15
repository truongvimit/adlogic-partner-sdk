package io.onboardkit.ads.pool

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.NativePoolEngine
import com.ads.module.helper.adnative.pool.PoolCancellable
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolTraceSink
import com.ads.module.helper.adnative.pool.SharedNativePool
import com.ads.module.helper.adnative.pool.reportsSuccess
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.awaitNativeRequestWindow
import io.onboardkit.ads.canStartNativeRequest
import io.onboardkit.ads.erain.ERainAdProvider
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.core.ObLog
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.flow.NativeFlowSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
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

    /** The pooled screens alive, oldest first; main thread. */
    private val screens = mutableListOf<WeakReference<Activity>>()

    /** Per screen, what it asked while its window was not open, replayed in order once it opens. */
    private val waiting = mutableMapOf<Activity, LinkedHashMap<String, () -> Unit>>()

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

    /** The pool loads through the ERain ad stack; a test engine stands in for it. */
    fun canRun(): Boolean = OnboardingSdk.provider() is ERainAdProvider || engineOrNull() != null

    fun engineOrNull(): NativePoolEngine<ApNativeAd>? = SharedNativePool.engineOrNull()

    /** Every activation starts a new run: no owner, and an empty pool only under reset_on_new_run. */
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
            waiting.clear()
            attach(engine)
            engine.onNewRun()
            liveRun = run
            ObLog.d(ObLog.Section.LOAD, "pool | activate keep_after_flow=${snapshot.parity.keepAfterFlow} " +
                "reset_on_new_run=${snapshot.parity.resetOnNewRun}")
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
        val held = snapshot.parity.respectFocusWindow && engine.debugSnapshot().loader(placement.key)?.ad != null
        if (held && waitForWindow(placement, "refresh") { refresh(placement) }) return false
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

    /**
     * A pooled screen the requests of `parity.respect_focus_window` wait on: the newest one alive
     * that is not finishing, so a screen closing over the hub does not hold the hub's requests.
     */
    @MainThread
    fun attachScreen(screen: Activity) {
        screens.removeAll { it.get().let { a -> a == null || a === screen } }
        screens += WeakReference(screen)
    }

    @MainThread
    fun detachScreen(screen: Activity) {
        screens.removeAll { it.get().let { a -> a == null || a === screen } }
        waiting.remove(screen)
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
        screens.clear()
        waiting.clear()
        liveRun = NO_RUN
        snapshot = NativeFlowSnapshot()
    }

    /** The epoch bump alone turns routing off: a flag written here could land after a later activation. */
    private fun stop(why: String) {
        val run = epoch.incrementAndGet()
        onMain {
            if (epoch.get() != run) return@onMain
            results.clear()
            waiting.clear()
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
        // Only a load that would go out waits: a refusal, a gate skip or a no-op answers at once.
        if (snapshot.parity.respectFocusWindow && engine.wouldRequest(request, mode) &&
            waitForWindow(placement, "load") { load(placement, mode) }
        ) {
            // The caller owns the slot as after any passing refusal; the replay requests, or not.
            return LoadResult.SkippedTransient(WINDOW_NOT_OPEN).also { results[placement.key] = it }
        }
        return engine.load(request, mode).also { record(placement, it) }
    }

    /**
     * `parity.respect_focus_window`: while the newest pooled screen is not resumed with window
     * focus, [replay] is queued until it is, as the classic natives wait (latest call per [op] and
     * key). True when queued. A screen that goes away drops what it queued.
     */
    private fun waitForWindow(placement: AdPlacement, op: String, replay: () -> Unit): Boolean {
        if (!snapshot.parity.respectFocusWindow) return false
        val screen = screens.lastOrNull { it.get()?.let { a -> !a.isFinishing && !a.isDestroyed } == true }?.get()
            ?: return false
        if (screen.canStartNativeRequest(allowWhileVisible = false)) return false
        val owner = screen as? LifecycleOwner ?: return false
        val queued = waiting[screen] ?: LinkedHashMap<String, () -> Unit>().also { queued ->
            waiting[screen] = queued
            owner.lifecycleScope.launch {
                try {
                    val open = screen.awaitNativeRequestWindow(allowWhileVisible = false)
                    // A new run or the screen's end may have dropped this queue meanwhile.
                    if (open && waiting[screen] === queued) {
                        waiting.remove(screen)
                        queued.values.toList().forEach { it() }
                    }
                } finally {
                    if (waiting[screen] === queued) waiting.remove(screen)
                }
            }
        }
        val key = "$op:${placement.key}"
        queued.remove(key)
        queued[key] = replay
        ObLog.d(ObLog.Section.LOAD, "pool | window_wait key=${placement.key} op=$op")
        return true
    }

    /** Requests and refusals go to analytics as in the classic flow; a gate skip only to the trace. */
    private fun record(placement: AdPlacement, result: LoadResult) {
        results[placement.key] = result
        when (result) {
            LoadResult.Requested -> placement.trackRequest()
            is LoadResult.SkippedTransient -> trackSkip(placement, result.reason)
            is LoadResult.SkippedByConfig -> trackSkip(placement, result.reason)
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
        val placement = PoolPlacementMapper.placementFor(event.key) ?: return
        if (AdBehavior.bool("diagnostics.ads_telemetry_enabled")) {
            OnboardingSdk.track(AnalyticsEvent.AdFailed(placement.key, placement.format))
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }

    private const val NO_RUN = -1L

    /** Not an [AdSkipReason]: a wait is no skip, so it reaches no analytics. */
    private const val WINDOW_NOT_OPEN = "window_not_open"
}
