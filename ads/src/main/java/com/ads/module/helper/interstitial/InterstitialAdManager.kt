package com.ads.module.helper.interstitial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ads.module.admob.Admob
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.funtion.AdCallback
import com.ads.module.dialog.PrepareLoadingAdsDialog
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.CachedAd
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import io.trackkit.SimpleEvent
import io.trackkit.Tracker
import io.trackkit.AdFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Load-time knobs for one interstitial request. */
class InterLoadOptions @JvmOverloads constructor(
    /** Placement on/off from config; off skips with `disabled_config`. */
    val enabled: Boolean = true,
    /** Pre-resolved UA/organic gate — see [AdGate.passesUaGate]. */
    val passesUaGate: Boolean = true,
    /** How long one waterfall tier may take before the next floor is tried. */
    val tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS,
    /** Off when the caller owns request/skip analytics itself (OnboardKit's AdTelemetry). */
    val reportTelemetry: Boolean = true,
)

/** Options for an explicit partner trigger that may wait for an interstitial fill. */
class InterLoadAndShowOptions @JvmOverloads constructor(
    /** UI wait only; expiry does not cancel a shared load or bound the waterfall's lifetime. */
    val timeoutMs: Long = 8_000L,
    /** Off skips even when this placement already has a ready fill. */
    val enabled: Boolean = true,
    /** Pre-resolved UA/organic gate, applied before using either a buffer or a new request. */
    val passesUaGate: Boolean = true,
    /** Whether this invocation reports request/skip telemetry. */
    val reportTelemetry: Boolean = true,
    /** Captured for this invocation; load time cannot change the eventual navigation mode. */
    val nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
) {
    var allowWaitForAutoBuffer: Boolean = false
        private set

    /** Separate overload preserves the legacy Kotlin default-constructor descriptor. */
    @JvmOverloads
    constructor(
        allowWaitForAutoBuffer: Boolean,
        timeoutMs: Long = 5_000L,
        enabled: Boolean = true,
        passesUaGate: Boolean = true,
        reportTelemetry: Boolean = true,
        nextAction: InterNextAction = InterstitialAdManager.defaultNextAction,
    ) : this(timeoutMs, enabled, passesUaGate, reportTelemetry, nextAction) {
        this.allowWaitForAutoBuffer = allowWaitForAutoBuffer
    }
}

/**
 * The moments of one interstitial presentation. [onComplete] fires exactly once on every
 * path — wire navigation there and nothing else, so a failed or skipped show can never
 * strand the screen.
 *
 * *When* it fires is [InterNextAction]: after the ad is gone by default, or while the ad is on
 * screen when the caller asked for [InterNextAction.UnderAd]. Only the timing changes — the
 * once-on-every-path guarantee holds either way.
 */
open class InterShowCallback {

    /** The ad is committed to the screen. */
    open fun onShowed() {}

    /**
     * The ad was displayed and dismissed. Runs before [onComplete] under
     * [InterNextAction.AfterDismiss] and after it under [InterNextAction.UnderAd], because
     * that is precisely what the two modes mean — never put navigation here.
     */
    open fun onClosed() {}

    /** The ad never reached the screen; [reason] says why. */
    open fun onSkipped(reason: AdSkipReason) {}

    open fun onClicked() {}

    /** Exactly once per [InterstitialAdManager.show] call, on every outcome. */
    open fun onComplete() {}
}

/**
 * Placement-keyed interstitial store: the app says *when* to load and *where* to show;
 * everything between the two calls — waterfall, cache, expiry, dedup, the module's
 * ambiguous show callbacks — is owned here.
 *
 * One buffered ad per placement. Buffered ads expire after [CachedAd.MAX_AGE_MS]; an
 * expired buffer reports [AdSkipReason.NOT_READY] instead of showing a blank.
 *
 * Main-thread only — the legacy GMA SDK requires load/show calls there.
 */
object InterstitialAdManager {

    /**
     * Timing used by every [show] that does not name one. [InterNextAction.AfterDismiss] out of
     * the box.
     *
     * Backed by the module's own `openActivityAfterShowInterAds` — the same switch the legacy
     * splash paths read and the one `ERainAd.setOpenActivityAfterShowInterAds` writes — so this is
     * a view onto one value, never a second copy that can drift.
     *
     * Set it once from `Application.onCreate`.
     */
    @JvmStatic
    var defaultNextAction: InterNextAction
        get() = if (ERainAd.getInstance().isOpenActivityAfterShowInterAds) {
            InterNextAction.UnderAd
        } else {
            InterNextAction.AfterDismiss
        }
        set(value) {
            ERainAd.getInstance()
                .setOpenActivityAfterShowInterAds(value == InterNextAction.UnderAd)
        }

    private val cache = ConcurrentHashMap<String, CachedAd<ApInterstitialAd>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    // A release invalidates restoration of a fill already removed for an in-progress show.
    // This does not cancel or change the existing background load behavior.
    private val releaseTokens = ConcurrentHashMap<String, Any>()
    // One observer per placement; a list here would re-report against dead screens
    private val listeners = ConcurrentHashMap<String, AdCallback>()
    // Temporary UI subscribers never replace the placement's persistent load/click listener.
    private val loadWaiters = ConcurrentHashMap<String, MutableSet<(AdSkipReason?) -> Unit>>()

    /**
     * Fire-and-forget waterfall load for [placement]. Idempotent: a ready buffer or an
     * in-flight request is never doubled, the fresh [listener] still hears the outcome.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        context: Context,
        placement: String,
        adUnitIds: List<String>,
        options: InterLoadOptions = InterLoadOptions(),
        listener: AdCallback? = null,
    ) {
        listener?.let { listeners[placement] = it }
        loadInternal(context, placement, adUnitIds, options, extra = null)
    }

    /**
     * The same load, with the waterfall and the placement's gates read from `ad_config.json`.
     *
     * Prefer this: the app names the placement and nothing else. The id-taking overload is for a
     * caller that deliberately supplies its own units and owns the decision.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        context: Context,
        placement: String,
        options: InterLoadOptions = InterLoadOptions(),
        listener: AdCallback? = null,
    ) {
        listener?.let { listeners[placement] = it }
        loadInternal(
            context,
            placement,
            AdGate.adUnitIds(placement),
            options.forPlacement(placement),
            extra = null,
        )
    }

    /** [options] AND-ed with what the placement's own configuration allows. */
    private fun InterLoadOptions.forPlacement(placement: String) = InterLoadOptions(
        enabled = enabled && AdGate.placementEnabled(placement),
        passesUaGate = passesUaGate && AdGate.placementPassesUaGate(placement),
        tierTimeoutMs = tierTimeoutMs,
        reportTelemetry = reportTelemetry,
    )

    private fun loadInternal(
        context: Context,
        placement: String,
        adUnitIds: List<String>,
        options: InterLoadOptions,
        extra: ((AdSkipReason?) -> Unit)?,
    ) {
        // Re-requesting over a ready ad burns a request and the fill
        cache[placement]?.takeIf { it.isFresh && it.ad.isReady }?.let { cached ->
            notifyListener(placement) { it.onApInterstitialLoad(cached.ad) }
            extra?.let { runCatching { it(null) } }
            return
        }
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason =
            AdGate.skipReason(context, options.enabled && ids.isNotEmpty(), options.passesUaGate)
                ?: InterstitialAutoBuffer.loadSkipReason(placement)
        if (skipReason != null) {
            // A temporary waiter owns its terminal skip; do not report it twice here.
            if (options.reportTelemetry && extra == null) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, skipReason.key)
            }
            notifyListener(placement) { it.onAdFailedToLoad(null) }
            extra?.let { runCatching { it(skipReason) } }
            return
        }
        extra?.let { loadWaiters.getOrPut(placement) { linkedSetOf() }.add(it) }
        if (!inFlight.add(placement)) return
        // After the guard, not before: a de-duplicated no-op load used to re-point the registry,
        // so a shared ad unit id was attributed to whichever placement called load() last rather
        // than to the one that actually requested it.
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        if (options.reportTelemetry) {
            AdTracking.request(placement, AdFormat.INTERSTITIAL, ids.first())
        }
        AdWaterfall.loadInterstitial(
            context,
            ids,
            options.tierTimeoutMs,
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    inFlight.remove(placement)
                    if (apInterstitialAd == null || !apInterstitialAd.isReady) {
                        InterstitialFrequency.onLoadFailed(placement)
                        notifyLoadResult(placement, AdSkipReason.NOT_READY) { it.onAdFailedToLoad(null) }
                        return
                    }
                    cache[placement] = CachedAd(apInterstitialAd)
                    notifyLoadResult(placement, null) { it.onApInterstitialLoad(apInterstitialAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    inFlight.remove(placement)
                    InterstitialFrequency.onLoadFailed(placement)
                    notifyLoadResult(placement, AdSkipReason.NOT_READY) { it.onAdFailedToLoad(adError) }
                }
            },
        )
    }

    /**
     * Explicit partner trigger: ready fill, join an existing request, or start one cold load.
     * AutoBuffer remains cache-only unless [InterLoadAndShowOptions.allowWaitForAutoBuffer]
     * opts in. The opt-in wait is capped at 5 seconds from entry.
     *
     * [InterLoadAndShowOptions.timeoutMs] limits waiting for a fill, not the waterfall,
     * the existing 800ms show preparation, or an ad awaiting dismissal. Timeout/destroy removes
     * this caller's waiter; a later valid fill can remain buffered but never auto-shows for it.
     * A fill arriving while the host is in the background is likewise retained for a new trigger.
     * The existing [show] path still owns presentation and [InterNextAction] completion timing.
     * Main-thread only, like [load] and [show].
     */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: AppCompatActivity,
        placement: String,
        adUnitIds: List<String>,
        callback: InterShowCallback,
        options: InterLoadAndShowOptions = InterLoadAndShowOptions(),
    ) {
        val clickedAt = SystemClock.elapsedRealtime()
        val source = if (isReady(placement)) "ready" else if (isLoading(placement)) "join" else "cold"
        fun reportSkipped(reason: AdSkipReason) {
            reportWait(placement, source, reason.key, clickedAt, options)
            if (options.reportTelemetry) {
                runCatching { AdTracking.skipped(placement, AdFormat.INTERSTITIAL, reason.key) }
            }
            runCatching { callback.onSkipped(reason) }
            runCatching { callback.onComplete() }
        }

        // A ready fill can show offline, but does not bypass placement or authority gates.
        val entryGate = AdGate.skipReason(activity, options.enabled, options.passesUaGate, checkNetwork = false)
        if (entryGate != null) {
            if (entryGate == AdSkipReason.PURCHASED) cache.remove(placement)
            reportSkipped(entryGate)
            return
        }
        InterstitialFrequency.recordAction(placement)
        val waitMs = if (options.allowWaitForAutoBuffer) options.timeoutMs.coerceIn(0L, 5_000L)
            else options.timeoutMs.coerceAtLeast(0L)
        val deadline = clickedAt + waitMs
        if ((InterstitialAutoBuffer.owns(placement) && !options.allowWaitForAutoBuffer) || isReady(placement)) {
            reportWait(placement, source, "dispatch", clickedAt, options)
            showInternal(activity, placement, callback, options.reportTelemetry, options.nextAction)
            return
        }
        val blockReason = showSkipReason(activity, placement)
            ?.takeUnless { it == AdSkipReason.NOT_READY }
            ?: AdGate.skipReason(activity, AdWaterfall.usableIds(adUnitIds).isNotEmpty(), options.passesUaGate,
                checkNetwork = !options.allowWaitForAutoBuffer || !isLoading(placement))
        if (blockReason != null) {
            reportSkipped(blockReason)
            return
        }
        fun hostCanShow() = !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!hostCanShow()) {
            reportSkipped(AdSkipReason.SHOW_IN_BACKGROUND)
            return
        }

        if (options.allowWaitForAutoBuffer && waitMs == 0L) {
            reportSkipped(AdSkipReason.NOT_READY)
            return
        }
        awaitAndShow(activity, placement, adUnitIds, callback, options, deadline, clickedAt, source)
    }

    /**
     * The same trigger, with the waterfall and the placement's gates read from `ad_config.json`.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: AppCompatActivity,
        placement: String,
        callback: InterShowCallback,
        options: InterLoadAndShowOptions = InterLoadAndShowOptions(),
    ) = loadAndShow(
        activity,
        placement,
        AdGate.adUnitIds(placement),
        callback,
        options.forPlacement(placement),
    )

    /** [loadAndShow] for a caller that only navigates; see [show] for what [onComplete] binds. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: AppCompatActivity,
        placement: String,
        options: InterLoadAndShowOptions = InterLoadAndShowOptions(),
        onComplete: () -> Unit,
    ) = loadAndShow(activity, placement, completionOnly(onComplete), options)

    /** The id-taking [loadAndShow] for a caller that only navigates. */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: AppCompatActivity,
        placement: String,
        adUnitIds: List<String>,
        options: InterLoadAndShowOptions = InterLoadAndShowOptions(),
        onComplete: () -> Unit,
    ) = loadAndShow(activity, placement, adUnitIds, completionOnly(onComplete), options)

    /** [options] AND-ed with what the placement's own configuration allows. */
    private fun InterLoadAndShowOptions.forPlacement(placement: String) = InterLoadAndShowOptions(
        allowWaitForAutoBuffer = allowWaitForAutoBuffer,
        timeoutMs = timeoutMs,
        enabled = enabled && AdGate.placementEnabled(placement),
        passesUaGate = passesUaGate && AdGate.placementPassesUaGate(placement),
        reportTelemetry = reportTelemetry,
        nextAction = nextAction,
    )

    private fun awaitAndShow(
        activity: AppCompatActivity,
        placement: String,
        adUnitIds: List<String>,
        callback: InterShowCallback,
        options: InterLoadAndShowOptions,
        deadline: Long,
        clickedAt: Long,
        source: String,
    ) {
        fun hostCanShow() = !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val releaseToken = releaseTokens.getOrPut(placement) { Any() }
        val handler = Handler(Looper.getMainLooper())
        val settled = AtomicBoolean(false)
        var dialog: PrepareLoadingAdsDialog? = null
        lateinit var timeout: Runnable
        lateinit var observer: LifecycleEventObserver
        lateinit var waiter: (AdSkipReason?) -> Unit
        lateinit var gateObserver: () -> Unit

        fun detach(): Boolean {
            if (!settled.compareAndSet(false, true)) return false
            loadWaiters[placement]?.let { subscribers ->
                subscribers.remove(waiter)
                if (subscribers.isEmpty()) loadWaiters.remove(placement, subscribers)
            }
            InterstitialAutoBuffer.removeGateObserver(gateObserver)
            handler.removeCallbacks(timeout)
            activity.lifecycle.removeObserver(observer)
            runCatching { dialog?.dismiss() }
            dialog = null
            return true
        }
        fun finishSkipped(reason: AdSkipReason) {
            if (!detach()) return
            reportWait(placement, source, if (SystemClock.elapsedRealtime() >= deadline &&
                reason == AdSkipReason.NOT_READY) "timeout" else reason.key, clickedAt, options)
            if (options.reportTelemetry) {
                runCatching { AdTracking.skipped(placement, AdFormat.INTERSTITIAL, reason.key) }
            }
            runCatching { callback.onSkipped(reason) }
            runCatching { callback.onComplete() }
        }
        gateObserver = {
            val reason = AdGate.skipReason(activity, options.enabled, options.passesUaGate, checkNetwork = false)
                ?: InterstitialAutoBuffer.placementSkipReason(activity, placement)
                ?: if (InterstitialAutoBuffer.owns(placement) && !InterstitialAutoBuffer.isRunning()) {
                    AdSkipReason.DISABLED_CONFIG
                } else null
            if (reason != null) finishSkipped(reason)
        }
        timeout = Runnable { finishSkipped(AdSkipReason.NOT_READY) }
        observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY ||
                (options.allowWaitForAutoBuffer && event == Lifecycle.Event.ON_STOP)
            ) finishSkipped(AdSkipReason.SHOW_IN_BACKGROUND)
        }
        waiter = { reason ->
            when {
                // The persistent load listener can release A and preload B before this callback.
                releaseTokens[placement] !== releaseToken -> finishSkipped(AdSkipReason.NOT_READY)
                options.allowWaitForAutoBuffer && SystemClock.elapsedRealtime() >= deadline ->
                    finishSkipped(AdSkipReason.NOT_READY)
                reason != null -> finishSkipped(reason)
                !hostCanShow() -> finishSkipped(AdSkipReason.SHOW_IN_BACKGROUND)
                detach() -> {
                    reportWait(placement, source, "dispatch", clickedAt, options)
                    showInternal(activity, placement, callback, options.reportTelemetry, options.nextAction)
                }
                else -> Unit
            }
        }
        if (options.allowWaitForAutoBuffer) InterstitialAutoBuffer.observeGates(gateObserver)
        activity.lifecycle.addObserver(observer)
        handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        // Cosmetic only. This captured dialog cannot dismiss a later invocation's UI.
        dialog = runCatching {
            PrepareLoadingAdsDialog(activity).apply {
                setCancelable(false)
                show()
            }
        }.getOrNull()
        if (settled.get()) {
            runCatching { dialog?.dismiss() }
            return
        }
        if (options.allowWaitForAutoBuffer && isLoading(placement)) {
            // Already requested: joining never re-runs the network/new-request gate.
            loadWaiters.getOrPut(placement) { linkedSetOf() }.add(waiter)
        } else {
            loadInternal(activity, placement, adUnitIds, InterLoadOptions(
                enabled = options.enabled,
                passesUaGate = options.passesUaGate,
                reportTelemetry = options.reportTelemetry,
            ), extra = waiter)
        }
    }

    /** One diagnostic per opted-in click. Dispatch is not an impression. */
    private fun reportWait(placement: String, source: String, result: String,
                           clickedAt: Long, options: InterLoadAndShowOptions) {
        if (!options.allowWaitForAutoBuffer || !options.reportTelemetry) return
        runCatching { Tracker.track(SimpleEvent("ad_interstitial_wait", mapOf(
            "placement" to placement, "source" to source, "status" to result,
            "wait_ms" to (SystemClock.elapsedRealtime() - clickedAt).coerceAtLeast(0L),
        ))) }
    }

    /** True when a fresh, showable ad is buffered for [placement]. */
    @JvmStatic
    fun isReady(placement: String): Boolean = takeFresh(placement) != null

    @JvmStatic
    fun isLoading(placement: String): Boolean = placement in inFlight

    /**
     * Why [show] would decline right now, or null when it would go ahead.
     *
     * Read-only: unlike [show] it never touches the buffer, so a caller can branch on the answer
     * — hide a loading dialog, take a different route — without spending the ad it asked about.
     */
    @JvmStatic
    fun showSkipReason(context: Context, placement: String): AdSkipReason? =
        AdGate.skipReason(context, enabled = true, checkNetwork = false)
            // Only for a placement the payload declares: a caller that loaded its own ad units
            // under a key the config never mentions still owns that decision at show time.
            ?: AdRemoteConfig.getInstance().takeIf { it.declares(placement) }
                ?.let { AdGate.placementSkipReason(context, placement, checkNetwork = false) }
            ?: InterstitialAutoBuffer.placementSkipReason(context, placement) ?: when {
            !InterstitialFrequency.elapsed(context, placement) -> AdSkipReason.CAPPED_BY_MODULE
            !isReady(placement) -> AdSkipReason.NOT_READY
            else -> null
        }

    /** True when [show] would put an ad on screen. See [showSkipReason] for the reason it would not. */
    @JvmStatic
    fun canShow(context: Context, placement: String): Boolean =
        showSkipReason(context, placement) == null

    /**
     * Shows the buffered ad for [placement].
     *
     * The buffer is removed while showing. A lifecycle rejection before GMA show restores the
     * original fresh fill unless released or replaced; the next trigger decides whether to retry.
     * The module's interval/counter caps surface as [AdSkipReason.CAPPED_BY_MODULE] — it
     * answers those with a bare `onNextAction`, told apart by the commit marker.
     *
     * [nextAction] decides when `onComplete` fires; it defaults to [defaultNextAction] and is
     * read per call, so one placement can open its next screen under the ad while another waits
     * for the dismissal.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        placement: String,
        callback: InterShowCallback,
        reportTelemetry: Boolean = true,
        nextAction: InterNextAction = defaultNextAction,
    ) {
        InterstitialFrequency.recordAction(placement)
        showInternal(context, placement, callback, reportTelemetry, nextAction)
    }

    /**
     * [show] for a caller that only navigates: [onComplete] is [InterShowCallback.onComplete],
     * with the same once-on-every-outcome guarantee and the same [nextAction] timing.
     *
     * Take the [InterShowCallback] overload instead when the screen also needs `onShowed`,
     * `onClosed`, `onSkipped` or `onClicked`.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        placement: String,
        reportTelemetry: Boolean = true,
        nextAction: InterNextAction = defaultNextAction,
        onComplete: () -> Unit,
    ) = show(context, placement, completionOnly(onComplete), reportTelemetry, nextAction)

    /**
     * The module dispatches every outcome through one [InterShowCallback]; this binds only the
     * terminal one. Nothing here decides anything — [showInternal] still owns when it fires.
     */
    private fun completionOnly(action: () -> Unit) = object : InterShowCallback() {
        override fun onComplete() = action()
    }

    private fun showInternal(
        context: Context,
        placement: String,
        callback: InterShowCallback,
        reportTelemetry: Boolean,
        nextAction: InterNextAction,
    ) {
        // Decide before touching the buffer. Group timing is placement-scoped here; raw
        // splash/OB calls downstream no longer impose a second, global interval.
        val blockReason = showSkipReason(context, placement)
        if (blockReason != null) {
            // PURCHASED still drops it: a bought entitlement must not leave a showable ad behind.
            // CAPPED_BY_MODULE and NOT_READY do not — the first still has an ad worth keeping,
            // the second has nothing to drop.
            if (blockReason == AdSkipReason.PURCHASED) cache.remove(placement)
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, blockReason.key)
            }
            runCatching { callback.onSkipped(blockReason) }
            runCatching { callback.onComplete() }
            return
        }
        // Committed to showing: single-use, so the buffer is dropped before show() to make one
        // fill impossible to show twice.
        val releaseToken = releaseTokens.getOrPut(placement) { Any() }
        val spent = cache.remove(placement)
        val ad = spent?.takeIf { it.isFresh && it.ad.isReady }?.ad ?: run {
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, AdSkipReason.NOT_READY.key)
            }
            runCatching { callback.onSkipped(AdSkipReason.NOT_READY) }
            runCatching { callback.onComplete() }
            return
        }
        val committed = AtomicBoolean(false)
        val contentPolicy = InterstitialFrequency.isIndependent(placement)
        var dispatchSkip: AdSkipReason? = null
        val terminal = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val complete = { if (completed.compareAndSet(false, true)) runCatching { callback.onComplete() }; Unit }
        InterstitialFrequency.beginShow(placement)
        ERainAd.getInstance().forceShowInterstitial(
            context,
            ad,
            object : AdCallback() {
                override fun onInterstitialDisplayed() {
                    if (!terminal.get()) InterstitialFrequency.onShown(placement)
                }

                override fun usesActualInterstitialImpression(): Boolean = contentPolicy

                override fun canShowInterstitial(): Boolean {
                    if (!contentPolicy) return true
                    dispatchSkip = when {
                        releaseTokens[placement] !== releaseToken -> AdSkipReason.NOT_READY
                        !InterstitialAutoBuffer.owns(placement) -> AdSkipReason.DISABLED_CONFIG
                        else -> AdGate.skipReason(context, enabled = true, checkNetwork = false)
                            ?: InterstitialAutoBuffer.placementSkipReason(context, placement)
                            ?: if (!InterstitialFrequency.passesShowPolicy(placement)) AdSkipReason.CAPPED_BY_MODULE else null
                    }
                    return dispatchSkip == null
                }

                override fun onInterstitialShow() {
                    committed.set(true)
                    runCatching { callback.onShowed() }
                }

                override fun onNextAction() {
                    when (meaningOfNextAction(nextAction, committed.get(), completed.get())) {
                        NextActionMeaning.MODULE_CAP -> {
                            if (!terminal.compareAndSet(false, true)) return
                            // A lower-level counter cap did not spend this ready fill.
                            if (spent != null && spent.isFresh && spent.ad.isReady &&
                                releaseTokens[placement] === releaseToken
                            ) cache.putIfAbsent(placement, spent)
                            InterstitialFrequency.endShow(placement, closed = false)
                            if (reportTelemetry) {
                                AdTracking.skipped(
                                    placement,
                                    AdFormat.INTERSTITIAL,
                                    AdSkipReason.CAPPED_BY_MODULE.key,
                                )
                            }
                            runCatching { callback.onSkipped(AdSkipReason.CAPPED_BY_MODULE) }
                            complete()
                        }
                        // The ad is on screen; the next screen starts underneath it.
                        NextActionMeaning.NEXT_SCREEN -> complete()
                        // onAdClosed / onAdFailedToShow owns the outcome in this mode.
                        NextActionMeaning.IGNORED -> Unit
                    }
                }

                override fun onAdClosed() {
                    if (!terminal.compareAndSet(false, true)) return
                    InterstitialFrequency.endShow(placement, closed = true)
                    runCatching { callback.onClosed() }
                    complete()
                }

                override fun onAdFailedToShow(adError: AdError?) {
                    if (!terminal.compareAndSet(false, true)) return
                    InterstitialFrequency.endShow(placement, closed = false)
                    val lifecycleRejected = Admob.isShowInBackgroundError(adError)
                    if (lifecycleRejected && spent != null && spent.isFresh && spent.ad.isReady &&
                        releaseTokens[placement] === releaseToken &&
                        AdGate.skipReason(context, enabled = true, checkNetwork = false) == null
                    ) {
                        // Keep the original arrival timestamp; a newer fill always wins.
                        cache.putIfAbsent(placement, spent)
                    }
                    val reason = dispatchSkip ?: if (lifecycleRejected) AdSkipReason.SHOW_IN_BACKGROUND
                        else AdSkipReason.FAILED_TO_SHOW
                    if (reportTelemetry) {
                        AdTracking.skipped(placement, AdFormat.INTERSTITIAL, reason.key)
                    }
                    runCatching { callback.onSkipped(reason) }
                    complete()
                }

                override fun onAdClicked() {
                    callback.onClicked()
                    notifyListener(placement) { it.onAdClicked() }
                }
            },
            // Reloading is the caller's decision; the module doing it too double-requests
            false,
            nextAction == InterNextAction.UnderAd,
        )
    }

    @JvmStatic
    fun release(placement: String) {
        releaseTokens.remove(placement)
        cache.remove(placement)
        // inFlight is deliberately left alone: the walk it marks is not cancellable, and clearing
        // it here let the very next load() start a second concurrent request for the same
        // placement — the duplicate the guard exists to prevent. The in-flight fill still lands
        // and repopulates the cache.
        listeners.remove(placement)
        loadWaiters.remove(placement)?.toList()?.forEach { waiter ->
            runCatching { waiter(AdSkipReason.NOT_READY) }
        }
    }

    @JvmStatic
    fun releaseAll() {
        val waiting = loadWaiters.values.flatMap { it.toList() }
        loadWaiters.clear()
        releaseTokens.clear()
        cache.clear()
        inFlight.clear()
        listeners.clear()
        waiting.forEach { waiter -> runCatching { waiter(AdSkipReason.NOT_READY) } }
    }

    /** Drops an expired buffer so a caller never shows a stale wrapper that no-ops. */
    private fun takeFresh(placement: String): ApInterstitialAd? {
        val cached = cache[placement] ?: return null
        if (!cached.isFresh) {
            cache.remove(placement)
            return null
        }
        return cached.ad.takeIf { it.isReady }
    }

    private fun notifyLoadResult(placement: String, reason: AdSkipReason?, block: (AdCallback) -> Unit) {
        // Detach A's subscribers before a persistent callback can start B at this placement.
        val waiting = loadWaiters.remove(placement)?.toList().orEmpty()
        notifyListener(placement, block)
        waiting.forEach { waiter -> runCatching { waiter(reason) } }
    }

    private fun notifyListener(placement: String, block: (AdCallback) -> Unit) {
        listeners[placement]?.let { runCatching { block(it) } }
    }
}
