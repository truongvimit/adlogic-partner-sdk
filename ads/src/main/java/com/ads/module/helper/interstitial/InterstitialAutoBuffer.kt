package com.ads.module.helper.interstitial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.helper.AdSkipReason
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import java.util.concurrent.ConcurrentHashMap

/** What the partner wants auto-buffered, and how hard. */
class InterstitialBufferOptions @JvmOverloads constructor(
    /**
     * Placement keys to keep one ad buffered for. Empty — the default — means the buffer does
     * nothing, so upgrading the SDK never adds a request on its own.
     */
    val placements: List<String> = emptyList(),

    /**
     * How often to check, in milliseconds. `0` follows `ERainAdConfig.intervalInterstitialAd`,
     * which is the point: one ad per interval is exactly one ad per showable moment.
     */
    val tickMs: Long = 0L,

    /** Used when the interval rule is switched off; something still has to pace the check. */
    val idleTickMs: Long = 30_000L,

    /** Floor on the tick, so a tiny remote interval cannot turn this into a spin loop. */
    val minTickMs: Long = 5_000L,

    /** Legacy constructor parameter; retries now use the shared remote interval. */
    val backoffMs: Long = 30_000L,

    /** Legacy constructor parameter retained for source/binary compatibility. */
    val maxBackoffMs: Long = 5 * 60_000L,
) {
    var independentIntervalPlacements: Set<String> = emptySet()
        private set
    var tapThresholds: Map<String, Int> = emptyMap()
        private set
    var intervalMsByPlacement: Map<String, Long> = emptyMap()
        private set
    var isPlacementEnabled: (String) -> Boolean = { true }
        private set

    /** Opt-in overload keeps the original Java and Kotlin default constructors intact. */
    @JvmOverloads
    constructor(
        independentIntervalPlacements: Set<String>,
        placements: List<String> = emptyList(),
        tapThresholds: Map<String, Int> = emptyMap(),
        intervalMsByPlacement: Map<String, Long> = emptyMap(),
        isPlacementEnabled: (String) -> Boolean = { true },
        tickMs: Long = 0L,
        idleTickMs: Long = 30_000L,
        minTickMs: Long = 5_000L,
        backoffMs: Long = 30_000L,
        maxBackoffMs: Long = 5 * 60_000L,
    ) : this(placements, tickMs, idleTickMs, minTickMs, backoffMs, maxBackoffMs) {
        this.independentIntervalPlacements = independentIntervalPlacements.toSet()
        this.tapThresholds = tapThresholds.mapValues { it.value.coerceAtLeast(0) }
        this.intervalMsByPlacement = intervalMsByPlacement.mapValues { it.value.coerceAtLeast(0L) }
        this.isPlacementEnabled = isPlacementEnabled
    }
}

/**
 * Keeps one interstitial buffered per placement, paced by the frequency clock instead of by
 * whichever screen the user happens to open.
 *
 * Configure the group and call [start] from the first content screen after onboarding,
 * including notification/restored entries. Opted-in placements preload 2s before their interval;
 * legacy placements wait the full remote interval.
 * Process background pauses scheduling but preserves both the gate and cached ads.
 * All placement-based loads share the manager's cache and in-flight request guard.
 *
 * Ad unit ids come from [AdRemoteConfig.tiersFor], so a placement that grows a `_high` floor in
 * remote config starts using it without a code change.
 *
 * Off by default. Never started from `AdsMultiDexApplication` — a partner who upgrades and changes
 * nothing gets no new requests.
 */
object InterstitialAutoBuffer {

    private const val TAG = "InterAutoBuffer"

    private val handler = Handler(Looper.getMainLooper())
    private val reserved = ConcurrentHashMap.newKeySet<String>()
    private val gateObservers = linkedSetOf<() -> Unit>()

    internal fun observeGates(observer: () -> Unit) { gateObservers += observer }
    internal fun removeGateObserver(observer: () -> Unit) { gateObservers -= observer }

    private var observing = false
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (running) schedule(1L)
            Lifecycle.Event.ON_STOP -> handler.removeCallbacks(tick)
            else -> Unit
        }
    }

    @Volatile
    private var options = InterstitialBufferOptions()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    private val tick = Runnable {
        schedule(topUp())
    }

    /** Replaces the configuration. Safe before or after [start]; takes effect on the next tick. */
    @JvmStatic
    fun configure(newOptions: InterstitialBufferOptions) {
        if (options.placements.isEmpty() && newOptions.placements.isNotEmpty()) {
            InterstitialFrequency.reset()
        }
        options = newOptions
        if (running) {
            InterstitialFrequency.activate()
        }
        onGateChanged()
        Log.i(TAG, "configured for ${newOptions.placements}")
    }

    @JvmStatic
    fun options(): InterstitialBufferOptions = options

    /**
     * Arms this process after onboarding/content entry. Repeated calls and foreground returns
     * do not restart the initial delay or a live group interval. Never call from Application.
     */
    @JvmStatic
    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        InterstitialFrequency.activate()
        if (!observing) {
            observing = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
        schedule(nextPreloadDelay().coerceAtLeast(1L))
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        onGateChanged()
    }

    @JvmStatic
    fun isRunning(): Boolean = running

    /**
     * Placement keys the buffer must never touch, whatever a partner configures.
     *
     * OnboardKit registers its own here: its splash interstitial is deliberately reused at the end
     * of the language screen and the onboarding pager, and a flow-exit decision is taken from a
     * bare "is one buffered" probe. Topping that placement up behind the flow's back would both
     * add an impression and delete the screens the flow would otherwise have shown.
     */
    @JvmStatic
    fun reserve(vararg placements: String) {
        reserved += placements
    }

    /** Requests an eligibility check. This never bypasses the group gate or foreground rule. */
    @JvmStatic
    fun topUpNow() {
        if (!running) return
        handler.post { if (running) schedule(topUp()) }
    }

    /** Compatibility API: connectivity changes can prompt a check but never erase the gate. */
    @JvmStatic
    fun resetBackoff() = topUpNow()

    internal fun owns(placement: String): Boolean =
        placement in options.placements && placement !in reserved

    internal fun loadSkipReason(placement: String): AdSkipReason? {
        if (!owns(placement)) return null
        if (!running) return AdSkipReason.DISABLED_CONFIG
        if (!isForeground()) return AdSkipReason.SHOW_IN_BACKGROUND
        if (InterstitialFrequency.isPresenting() ||
            InterstitialFrequency.preloadRemainingMs(placement) > 0L ||
            !InterstitialFrequency.hasTaps(placement)
        ) {
            return AdSkipReason.CAPPED_BY_MODULE
        }
        return appContext?.let { placementSkipReason(it, placement) }
    }

    /** Current partner/config authority shared by preload, waiting and presentation. */
    internal fun placementSkipReason(context: Context, placement: String): AdSkipReason? {
        if (!InterstitialFrequency.isIndependent(placement)) return null
        val config = AdRemoteConfig.getInstance()
        return AdGate.skipReason(context,
            enabled = options.isPlacementEnabled(placement) && config.tiersFor(placement).isNotEmpty(),
            passesUaGate = AdGate.passesUaGate(config.unit(placement).enableUaCheck),
            checkNetwork = false,
        )
    }

    /** Recalculate wakeup when a show closes, a waterfall fails, or remote interval changes. */
    @JvmStatic
    fun onGateChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onGateChanged() }
            return
        }
        gateObservers.toList().forEach { runCatching { it() } }
        if (!running) return
        val remaining = nextPreloadDelay()
        schedule(if (remaining == 0L &&
            (options.independentIntervalPlacements.isNotEmpty() || InterstitialFrequency.intervalSeconds() > 0)
        ) 1L else remaining)
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** The decision for one placement on one tick. Separated out so the rules are testable. */
    internal enum class Decision {
        LOAD, SKIP_READY, SKIP_IN_FLIGHT, SKIP_INTERVAL, SKIP_BACKOFF, SKIP_NO_IDS, SKIP_RESERVED
    }

    internal fun decide(
        nowMs: Long,
        isReady: Boolean,
        isLoading: Boolean,
        intervalRemainingMs: Long,
        backoffUntilMs: Long,
        hasIds: Boolean,
        isReserved: Boolean,
    ): Decision = when {
        isReserved -> Decision.SKIP_RESERVED
        // The buffer is already satisfied — including by an ad some screen loaded itself.
        isReady -> Decision.SKIP_READY
        // A request is already walking the waterfall; a second would be the duplicate this exists
        // to avoid. The fill it produces becomes the buffer.
        isLoading -> Decision.SKIP_IN_FLIGHT
        // This receives the preload gate (the show gate is deliberately later for opt-in).
        // Explicit loads follow the same gate; a ready ad never needs replacing.
        intervalRemainingMs > 0L -> Decision.SKIP_INTERVAL
        nowMs < backoffUntilMs -> Decision.SKIP_BACKOFF
        !hasIds -> Decision.SKIP_NO_IDS
        else -> Decision.LOAD
    }

    /** Tick period: explicit setting, else the interval, else the idle pace; floored and capped. */
    internal fun periodMs(
        tickMs: Long,
        intervalSeconds: Int,
        idleTickMs: Long,
        minTickMs: Long,
    ): Long {
        val base = when {
            tickMs > 0L -> tickMs
            intervalSeconds > 0 -> intervalSeconds * 1_000L
            else -> idleTickMs
        }
        // Never slower than half the buffer's own expiry, or a long interval would let the ad go
        // stale between checks and the placement would sit empty.
        return base.coerceIn(minTickMs, MAX_PERIOD_MS)
    }

    /**
     * @return the delay to use before looking again, or `0` for the configured period.
     */
    private fun topUp(): Long {
        val context = appContext ?: return options.minTickMs
        if (!running || !isForeground()) return 0L
        // Personalization may remain UNKNOWN while UMP already authorizes ad requests.
        // Use the same request authority as AdGate, not the analytics consent state.
        if (!ConsentCenter.canRequestAds()) return options.minTickMs
        if (AdGate.isPurchased(context)) return 0L
        options.placements.distinct().forEach { placement ->
            if (loadSkipReason(placement) != null) return@forEach
            val ids = runCatching { AdRemoteConfig.getInstance().tiersFor(placement) }
                .getOrDefault(emptyList())
            if (decide(
                    nowMs = 0L,
                    isReady = InterstitialAdManager.isReady(placement),
                    isLoading = InterstitialAdManager.isLoading(placement),
                    intervalRemainingMs = InterstitialFrequency.preloadRemainingMs(placement),
                    backoffUntilMs = 0L,
                    hasIds = ids.isNotEmpty(),
                    isReserved = placement in reserved,
                ) != Decision.LOAD
            ) return@forEach
            Log.d(TAG, "buffering '$placement'")
            // Do not register a listener here: the partner owns that subscription. Manager
            // terminal callbacks update the shared gate immediately, including explicit loads.
            InterstitialAdManager.load(
                context,
                placement,
                ids,
                InterLoadOptions(enabled = true, passesUaGate = AdGate.passesUaGate(
                    AdRemoteConfig.getInstance().unit(placement).enableUaCheck,
                )),
            )
        }
        return nextPreloadDelay()
    }

    private fun nextPreloadDelay(): Long = options.placements.asSequence()
        .filter { owns(it) && InterstitialFrequency.hasTaps(it) }
        .filter { placement -> appContext?.let { placementSkipReason(it, placement) } == null }
        .filter { !InterstitialAdManager.isReady(it) && !InterstitialAdManager.isLoading(it) }
        .map { InterstitialFrequency.preloadRemainingMs(it) }
        .minOrNull() ?: 0L

    /** [delayMs] `0` uses the configured period; anything else is an exact wake-up. */
    private fun schedule(delayMs: Long = 0L) {
        handler.removeCallbacks(tick)
        if (!running || !isForeground()) return
        val period =
            if (delayMs > 0L) delayMs.coerceAtLeast(if (options.independentIntervalPlacements.isEmpty()) MIN_WAKE_MS else 1L)
            else periodMs(
                options.tickMs,
                InterstitialFrequency.intervalSeconds(),
                options.idleTickMs,
                options.minTickMs,
            )
        handler.postDelayed(tick, period)
    }

    /** Half of [com.ads.module.helper.CachedAd.MAX_AGE_MS] — a buffer must never expire unchecked. */
    private const val MAX_PERIOD_MS = 30 * 60 * 1_000L

    /** Floor on an exact wake-up, so a nearly-expired interval cannot spin the looper. */
    private const val MIN_WAKE_MS = 1_000L
}
