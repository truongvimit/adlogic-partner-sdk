package com.ads.module.helper.interstitial

import com.ads.module.config.settings.AdBehavior
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.helper.AdSkipReason
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import com.ads.module.engine.adMainScope
import com.ads.module.engine.launchAfter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val tickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.tick_ms"),

    /** Used when the interval rule is switched off; something still has to pace the check. */
    val idleTickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.idle_tick_ms"),

    /** Floor on the tick, so a tiny remote interval cannot turn this into a spin loop. */
    val minTickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.min_tick_ms"),

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
        tickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.tick_ms"),
        idleTickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.idle_tick_ms"),
        minTickMs: Long = AdBehavior.defaultNumber("interstitial_auto_buffer.min_tick_ms"),
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
 * including notification/restored entries. Opted-in placements preload according to
 * preload_lead_ms once their tap threshold is met; showing still waits the full interval.
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

    private var tickJob: Job? = null
    private val reserved = ConcurrentHashMap.newKeySet<String>()
    private val gateObservers = linkedSetOf<() -> Unit>()

    internal fun observeGates(observer: () -> Unit) { gateObservers += observer }
    internal fun removeGateObserver(observer: () -> Unit) { gateObservers -= observer }

    private var observing = false
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (running) schedule(1L)
            Lifecycle.Event.ON_STOP -> cancelTick()
            else -> Unit
        }
    }

    @Volatile
    private var configuredOptions = InterstitialBufferOptions()
    private val options: InterstitialBufferOptions get() = resolvedOptions()

    private var resolvedLocal: InterstitialBufferOptions? = null
    private var resolvedSnapshot: com.ads.module.config.settings.SettingsSnapshot? = null
    private var resolved: InterstitialBufferOptions? = null

    @Synchronized private fun resolvedOptions(): InterstitialBufferOptions {
        val local = configuredOptions
        val v = AdBehavior.document.snapshot
        if (resolvedLocal === local && resolvedSnapshot === v) return checkNotNull(resolved)
        // Bundled rules supply values; only host options or explicit asset/remote rules opt in.
        val remotePlacements = v.objectEntries("interstitial_auto_buffer.rules").keys
            .map { it.substringBefore('.') }
            .filter { v.hasOverride("interstitial_auto_buffer.rules.$it") }
        val keys = (local.placements + remotePlacements).distinct()
        val sharedConfig = v.boolean("interstitial_auto_buffer.shared_config")
        fun path(key: String, field: String) = "interstitial_auto_buffer.rules.$key.$field"
        fun bundledNumber(key: String, field: String): Long? =
            (AdBehavior.document.defaultValue(path(key, field)) as? Number)?.toLong()
        val result = InterstitialBufferOptions(
            independentIntervalPlacements = keys.filter {
                !sharedConfig || v.boolean(path(it, "independent_interval"), it in local.independentIntervalPlacements)
            }.toSet(),
            placements = keys,
            tapThresholds = keys.associateWith {
                v.long(path(it, "tap_threshold"),
                    local.tapThresholds[it]?.toLong() ?: bundledNumber(it, "tap_threshold") ?: 0L).toInt()
            },
            intervalMsByPlacement = keys.mapNotNull { key ->
                if (v.hasOverride(path(key, "interval_ms"))) key to v.long(path(key, "interval_ms"), 0L)
                else (local.intervalMsByPlacement[key] ?: bundledNumber(key, "interval_ms"))?.let { key to it }
            }.toMap(),
            isPlacementEnabled = { key ->
                local.isPlacementEnabled(key) && v.boolean(path(key, "enabled"),
                    key in local.placements && AdBehavior.document.defaultValue(path(key, "enabled")) != false)
            },
            tickMs = v.long("interstitial_auto_buffer.tick_ms", local.tickMs),
            idleTickMs = v.long("interstitial_auto_buffer.idle_tick_ms", local.idleTickMs),
            minTickMs = v.long("interstitial_auto_buffer.min_tick_ms", local.minTickMs),
            backoffMs = local.backoffMs, maxBackoffMs = local.maxBackoffMs,
        )
        resolvedLocal = local; resolvedSnapshot = v; resolved = result
        return result
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    /** Replaces the configuration. Safe before or after [start]; takes effect on the next tick. */
    @JvmStatic
    fun configure(newOptions: InterstitialBufferOptions) {
        if (configuredOptions.placements.isEmpty() && newOptions.placements.isNotEmpty()) {
            InterstitialFrequency.reset()
        }
        configuredOptions = newOptions
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
        cancelTick()
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
        adMainScope.launch { if (running) schedule(topUp()) }
    }

    /** Compatibility API: connectivity changes can prompt a check but never erase the gate. */
    @JvmStatic
    fun resetBackoff() = topUpNow()

    internal fun owns(placement: String): Boolean =
        placement in options.placements && placement !in reserved

    internal fun loadSkipReason(placement: String): AdSkipReason? {
        if (!owns(placement)) return null
        if (!running || !AdBehavior.bool("interstitial_auto_buffer.enabled")) return AdSkipReason.DISABLED_CONFIG
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
        if (!owns(placement)) return null
        if (!options.isPlacementEnabled(placement)) return AdSkipReason.DISABLED_CONFIG
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
            adMainScope.launch { onGateChanged() }
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
        // This receives the preload gate; the show gate still requires the full interval.
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
        return base.coerceIn(minTickMs.coerceAtMost(MAX_PERIOD_MS), MAX_PERIOD_MS)
    }

    /**
     * @return the delay to use before looking again, or `0` for the configured period.
     */
    private fun topUp(): Long {
        val context = appContext ?: return options.minTickMs
        if (!running || !AdBehavior.bool("interstitial_auto_buffer.enabled") || !isForeground()) return 0L
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
        cancelTick()
        if (!running || !AdBehavior.bool("interstitial_auto_buffer.enabled") || !isForeground()) return
        val period =
            if (delayMs > 0L) delayMs.coerceAtLeast(if (options.independentIntervalPlacements.isEmpty()) MIN_WAKE_MS else 1L)
            else periodMs(
                options.tickMs,
                InterstitialFrequency.intervalSeconds(),
                options.idleTickMs,
                options.minTickMs,
            )
        tickJob = launchAfter(period) { schedule(topUp()) }
    }

    private fun cancelTick() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Half of [com.ads.module.helper.CachedAd.MAX_AGE_MS] — a buffer must never expire unchecked. */
    private const val MAX_PERIOD_MS = 30 * 60 * 1_000L

    /** Floor on an exact wake-up, so a nearly-expired interval cannot spin the looper. */
    private const val MIN_WAKE_MS = 1_000L
}
