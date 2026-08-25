package com.ads.module.helper.interstitial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import io.trackkit.ConsentState
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

    /** First backoff after a failed fill; doubles up to [maxBackoffMs]. */
    val backoffMs: Long = 30_000L,

    val maxBackoffMs: Long = 5 * 60_000L,
)

/**
 * Keeps one interstitial buffered per placement, paced by the frequency clock instead of by
 * whichever screen the user happens to open.
 *
 * Without this a partner app loads from every screen that *might* show an ad, so entering a screen
 * the user never acts on still costs a request, and a screen the user never reaches leaves the
 * placement empty. Opt in once, from `Application`:
 *
 * ```
 * InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf("inter_all", "inter_back")))
 * InterstitialAutoBuffer.start(this)
 * ```
 *
 * **It shares one store with explicit loads and never doubles them.** Everything goes through
 * [InterstitialAdManager], whose `cache`/`inFlight` are keyed by placement, so:
 * a screen that calls `load` itself is untouched; a tick that finds the load still in flight does
 * not start a second; and the ad that load produced *is* the buffer, so the tick that follows
 * finds the placement satisfied and asks for nothing.
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

    /** Placement -> when a failed fill may be retried. */
    private val backoffUntil = ConcurrentHashMap<String, Long>()
    private val backoffStep = ConcurrentHashMap<String, Long>()

    /** Placements this object asked to load on the previous tick, to notice a silent failure. */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var options = InterstitialBufferOptions()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    private val tick = Runnable {
        val progressed = topUp()
        schedule(soon = !progressed)
    }

    /** Replaces the configuration. Safe before or after [start]; takes effect on the next tick. */
    @JvmStatic
    fun configure(newOptions: InterstitialBufferOptions) {
        options = newOptions
        Log.i(TAG, "configured for ${newOptions.placements}")
    }

    @JvmStatic
    fun options(): InterstitialBufferOptions = options

    /**
     * Starts ticking. Call after `AdRemoteConfig.initializeFromAssets` and `ERainAd.init` — before
     * the first the tier list is empty, before the second the interval reads as 0.
     */
    @JvmStatic
    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        schedule()
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
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

    /** Tops up now — called by the store once an ad is committed to the screen. */
    @JvmStatic
    fun topUpNow() {
        if (!running) return
        handler.post { topUp() }
        schedule()
    }

    /** Clears the failure backoff, e.g. when connectivity returns. */
    @JvmStatic
    fun resetBackoff() {
        backoffUntil.clear()
        backoffStep.clear()
    }

    /** The decision for one placement on one tick. Separated out so the rules are testable. */
    internal enum class Decision { LOAD, SKIP_READY, SKIP_IN_FLIGHT, SKIP_BACKOFF, SKIP_NO_IDS, SKIP_RESERVED }

    internal fun decide(
        nowMs: Long,
        isReady: Boolean,
        isLoading: Boolean,
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

    /** @return false when the pass was blocked by something worth re-checking sooner. */
    private fun topUp(): Boolean {
        val context = appContext ?: return false
        // Never request before the UMP answer. AdGate does not cover consent, and this runs on a
        // timer rather than behind the flow's consent step.
        if (ConsentCenter.state.value == ConsentState.UNKNOWN) return false
        // A paying user has nothing to wait for, so this is not a "retry sooner" case.
        if (AdGate.isPurchased(context)) return true

        val now = System.currentTimeMillis()
        options.placements.forEach { placement ->
            val wasRequested = requested.remove(placement)
            val ready = InterstitialAdManager.isReady(placement)
            val loading = InterstitialAdManager.isLoading(placement)
            // Nothing to observe but a failure: asked last tick, still neither filled nor walking.
            if (wasRequested && !ready && !loading) noteFailure(placement, now) else if (ready) {
                backoffUntil.remove(placement)
                backoffStep.remove(placement)
            }
            val ids = runCatching { AdRemoteConfig.getInstance().tiersFor(placement) }
                .getOrDefault(emptyList())
            val decision = decide(
                nowMs = now,
                isReady = ready,
                isLoading = loading,
                backoffUntilMs = backoffUntil[placement] ?: 0L,
                hasIds = ids.isNotEmpty(),
                isReserved = placement in reserved,
            )
            if (decision != Decision.LOAD) return@forEach
            Log.d(TAG, "buffering '$placement'")
            requested += placement
            // listener = null on purpose: the store keeps one listener per placement, so passing
            // one here would silently evict the partner's and their load callbacks would stop.
            InterstitialAdManager.load(
                context,
                placement,
                ids,
                InterLoadOptions(enabled = true, passesUaGate = AdGate.passesUaGate(false)),
            )
        }
        return true
    }

    private fun noteFailure(placement: String, nowMs: Long) {
        val step = (backoffStep[placement] ?: 0L).let {
            if (it <= 0L) options.backoffMs else (it * 2).coerceAtMost(options.maxBackoffMs)
        }
        backoffStep[placement] = step
        backoffUntil[placement] = nowMs + step
        Log.d(TAG, "'$placement' did not fill; backing off ${step}ms")
    }

    private fun schedule(soon: Boolean = false) {
        if (!running) return
        handler.removeCallbacks(tick)
        val period =
            if (soon) options.minTickMs
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
}
