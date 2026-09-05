package com.ads.module.helper.interstitial

import android.content.Context
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.CachedAd
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.AdLoadContext
import com.ads.module.tracking.TrackingAdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
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
    /** Whether this owner reports request, tier results, load terminals and skips. */
    val reportTelemetry: Boolean = true,
)

/**
 * The moments of one interstitial presentation. [onComplete] fires exactly once on every
 * path — wire navigation there and nothing else, so a failed or skipped show can never
 * strand the screen.
 *
 * *When* it fires is [InterNextAction]: after the ad is gone by default, or immediately before
 * vendor show when the caller asked for [InterNextAction.UnderAd]. Completion is not evidence
 * of presentation. The once-on-every-path guarantee holds either way.
 */
open class InterShowCallback {

    /** Legacy preparation/commit marker; it does not prove that the vendor presented the ad. */
    open fun onShowed() {}

    /** The vendor confirmed fullscreen presentation. Never use this to move UnderAd navigation. */
    open fun onPresented() {}

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

    private data class BufferedAd(val cached: CachedAd<ApInterstitialAd>, val personalized: Boolean)

    private val cache = ConcurrentHashMap<String, BufferedAd>()
    private val inFlight = ConcurrentHashMap<String, Any>()
    // A held ad is outside the cache; a token keeps release and late callbacks from reclaiming it.
    private val presentations = ConcurrentHashMap<String, Any>()
    // One observer per placement; a list here would re-report against dead screens
    private val listeners = ConcurrentHashMap<String, AdCallback>()

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
        val ids = AdWaterfall.usableIds(adUnitIds)
        val cached = takeFresh(placement)
        val skipReason = AdGate.skipReason(context, options.enabled && ids.isNotEmpty(),
            options.passesUaGate, checkNetwork = cached == null)
        if (skipReason != null) {
            if (options.reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, skipReason.key)
            }
            listener?.let { runCatching { it.onAdFailedToLoad(null) } }
            return
        }
        listener?.let { listeners[placement] = it }
        // Re-requesting over a ready ad burns a request and the fill. Policy still gates reuse.
        cached?.let {
            notifyListener(placement) { it.onApInterstitialLoad(cached) }
            return
        }
        val request = Any()
        if (inFlight.putIfAbsent(placement, request) != null) return
        val personalized = ConsentCenter.canPersonalize()
        // After the guard, not before: a de-duplicated no-op load used to re-point the registry,
        // so a shared ad unit id was attributed to whichever placement called load() last rather
        // than to the one that actually requested it.
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdWaterfall.loadInterstitial(
            context,
            ids,
            options.tierTimeoutMs,
            AdLoadContext(placement, AdFormat.INTERSTITIAL, options.reportTelemetry),
            object : AdCallback() {
                override fun canAcceptLoadedAd(): Boolean =
                    inFlight[placement] === request &&
                        AdGate.skipReason(context, options.enabled, options.passesUaGate,
                            checkNetwork = false) == null &&
                        personalized == ConsentCenter.canPersonalize() && inFlight[placement] === request

                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    if (!inFlight.remove(placement, request)) return
                    if (apInterstitialAd == null || !apInterstitialAd.isReady ||
                        AdGate.skipReason(context, options.enabled, options.passesUaGate,
                            checkNetwork = false) != null ||
                        personalized != ConsentCenter.canPersonalize()
                    ) {
                        notifyListener(placement) { it.onAdFailedToLoad(null) }
                        return
                    }
                    cache[placement] = BufferedAd(CachedAd(apInterstitialAd), personalized)
                    notifyListener(placement) { it.onApInterstitialLoad(apInterstitialAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    if (!inFlight.remove(placement, request)) return
                    notifyListener(placement) { it.onAdFailedToLoad(adError) }
                }
            },
        )
    }

    /** True when a fresh, showable ad is buffered for [placement]. */
    @JvmStatic
    fun isReady(placement: String): Boolean = takeFresh(placement) != null

    @JvmStatic
    fun isLoading(placement: String): Boolean = inFlight.containsKey(placement)

    /**
     * Why [show] would decline right now, or null when it would go ahead.
     *
     * Does not consume an ad for presentation, so a caller can branch without spending a fill.
     * Expiry cleanup and the shared premium observer can still invalidate unusable buffers.
     */
    @JvmStatic
    fun showSkipReason(context: Context, placement: String): AdSkipReason? =
        AdGate.skipReason(context, enabled = true, checkNetwork = false) ?: when {
            presentations.containsKey(placement) -> AdSkipReason.PRESENTATION_BUSY
            !InterstitialFrequency.elapsed(context) -> AdSkipReason.INTERVAL
            !isReady(placement) -> AdSkipReason.NOT_READY
            else -> null
        }

    /** True when [show] would put an ad on screen. See [showSkipReason] for the reason it would not. */
    @JvmStatic
    fun canShow(context: Context, placement: String): Boolean =
        showSkipReason(context, placement) == null

    /**
     * Shows the buffered ad for [placement]. The original cache entry is held while preparing;
     * a rejection before vendor invocation restores it only while fresh, authorized and still
     * owned by this presentation. A replacement loaded meanwhile always wins.
     *
     * [nextAction] pins navigation timing per call. [InterShowCallback.onShowed] retains its old
     * preparation meaning; only [InterShowCallback.onPresented] confirms vendor presentation.
     * [reportTelemetry] controls skipped events. The fullscreen tracking
     * callback separately owns actual show/show-failed events; callback delivery is never muted.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        placement: String,
        callback: InterShowCallback,
        reportTelemetry: Boolean = true,
        nextAction: InterNextAction = defaultNextAction,
    ) = show(context, placement, callback, InterShowOptions.DEFAULT, reportTelemetry, nextAction)

    /**
     * Shows with immutable preparation [options]. The original overload retains its default
     * dialog/800 ms behavior and compiled Java/Kotlin entry points. Options change only cosmetic
     * preparation; freshness, policy checks, callback order and [nextAction] are unchanged.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        placement: String,
        callback: InterShowCallback,
        options: InterShowOptions,
        reportTelemetry: Boolean = true,
        nextAction: InterNextAction = defaultNextAction,
    ) {
        val blockReason = showSkipReason(context, placement)
        if (blockReason != null) {
            if (blockReason == AdSkipReason.PURCHASED) cache.remove(placement)
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, blockReason.key)
            }
            callback.onSkipped(blockReason)
            callback.onComplete()
            return
        }
        val original = takeFreshEntry(placement) ?: run {
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, AdSkipReason.NOT_READY.key)
            }
            callback.onSkipped(AdSkipReason.NOT_READY)
            callback.onComplete()
            return
        }
        val token = Any()
        if (presentations.putIfAbsent(placement, token) != null) {
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, AdSkipReason.PRESENTATION_BUSY.key)
            }
            callback.onSkipped(AdSkipReason.PRESENTATION_BUSY)
            callback.onComplete()
            return
        }
        cache.remove(placement, original)
        val ad = original.cached.ad
        val committed = AtomicBoolean(false)
        val presented = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val complete = { if (completed.compareAndSet(false, true)) callback.onComplete() }
        val ownerCallback = object : AdCallback() {
            override fun getAdShowSkipReason(): AdSkipReason? {
                if (presentations[placement] !== token || terminal.get()) return AdSkipReason.NOT_READY
                AdGate.skipReason(context, enabled = true, checkNetwork = false)?.let { return it }
                // The gate can synchronously release buffers after a newly observed purchase.
                if (presentations[placement] !== token) return AdSkipReason.NOT_READY
                if (original.personalized != ConsentCenter.canPersonalize()) return AdSkipReason.NOT_READY
                if (!original.cached.isFresh) return AdSkipReason.EXPIRED
                if (!ad.isReady) return AdSkipReason.NOT_READY
                if (!InterstitialFrequency.elapsed(context)) return AdSkipReason.INTERVAL
                return null
            }

            override fun onInterstitialShow() {
                if (!terminal.get() && committed.compareAndSet(false, true)) callback.onShowed()
            }

            override fun onAdPresented() {
                if (!terminal.get() && presented.compareAndSet(false, true)) callback.onPresented()
            }

            override fun onAdShowRejected(reason: AdSkipReason) {
                if (!terminal.compareAndSet(false, true)) return
                // A cap or a background host withholds this fill; neither starts a new cache age.
                // Recheck the token after the gate in case entitlement invalidation ran there.
                if (presentations[placement] === token && original.cached.isFresh && ad.isReady &&
                    AdGate.skipReason(context, enabled = true, checkNetwork = false) == null &&
                    original.personalized == ConsentCenter.canPersonalize() &&
                    presentations[placement] === token
                ) {
                    cache.putIfAbsent(placement, original)
                }
                presentations.remove(placement, token)
                if (reportTelemetry) {
                    AdTracking.skipped(placement, AdFormat.INTERSTITIAL, reason.key)
                }
                callback.onSkipped(reason)
                complete()
            }

            override fun onNextAction() {
                if (terminal.get()) return
                when (meaningOfNextAction(nextAction, committed.get(), completed.get())) {
                    // Compatibility for a module path not yet emitting a structured rejection.
                    NextActionMeaning.MODULE_CAP -> onAdShowRejected(AdSkipReason.CAPPED_BY_MODULE)
                    // Navigation is committed just before vendor invocation, on the same tick.
                    NextActionMeaning.NEXT_SCREEN -> complete()
                    // The real close/failure still owns the outcome in AfterDismiss mode.
                    NextActionMeaning.IGNORED -> Unit
                }
            }

            override fun onAdClosed() {
                if (!terminal.compareAndSet(false, true)) return
                presentations.remove(placement, token)
                callback.onClosed()
                complete()
            }

            override fun onAdFailedToShow(adError: AdError?) {
                if (!terminal.compareAndSet(false, true)) return
                presentations.remove(placement, token)
                // Tracking reports a vendor show failure. Preserve the old UI outcome without
                // also turning the same failure into a canonical skipped event here.
                callback.onSkipped(AdSkipReason.FAILED_TO_SHOW)
                complete()
            }

            override fun onAdClicked() {
                if (terminal.get()) return
                callback.onClicked()
                notifyListener(placement) { it.onAdClicked() }
            }
        }
        ERainAd.getInstance().forceShowInterstitial(
            context,
            ad,
            TrackingAdCallback.fullscreenPresentation(
                placement, AdFormat.INTERSTITIAL, ad.interstitialAd?.adUnitId, ownerCallback, false,
            ),
            // Reloading is the caller's decision; the module doing it too double-requests.
            false,
            nextAction == InterNextAction.UnderAd,
            options,
        )
    }

    @JvmStatic
    fun release(placement: String) {
        presentations.remove(placement)
        cache.remove(placement)
        // inFlight is deliberately left alone: the walk it marks is not cancellable, and clearing
        // it here let the very next load() start a second concurrent request for the same
        // placement — the duplicate the guard exists to prevent. The in-flight fill still lands
        // and repopulates the cache.
        listeners.remove(placement)
    }

    /** Clears all buffers and completes each pending load listener once with failure. */
    @JvmStatic
    fun releaseAll() {
        val cancelled = inFlight.keys.mapNotNull { listeners[it] }
        presentations.clear()
        cache.clear()
        inFlight.clear()
        listeners.clear()
        // Clear ownership first: a terminal callback can immediately request a replacement.
        cancelled.forEach { listener -> runCatching { listener.onAdFailedToLoad(null) } }
    }

    /** Drops an expired buffer so a caller never shows a stale wrapper that no-ops. */
    private fun takeFresh(placement: String): ApInterstitialAd? = takeFreshEntry(placement)?.cached?.ad

    private fun takeFreshEntry(placement: String): BufferedAd? {
        val cached = cache[placement] ?: return null
        if (!cached.cached.isFresh || !ConsentCenter.canRequestAds() ||
            cached.personalized != ConsentCenter.canPersonalize()
        ) {
            cache.remove(placement, cached)
            return null
        }
        return cached.takeIf { it.cached.ad.isReady }
    }

    private fun notifyListener(placement: String, block: (AdCallback) -> Unit) {
        listeners[placement]?.let { runCatching { block(it) } }
    }
}
