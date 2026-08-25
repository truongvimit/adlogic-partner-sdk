package com.ads.module.helper.interstitial

import android.content.Context
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.CachedAd
import com.ads.module.tracking.AdTracking
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
    /** Off when the caller owns request/skip analytics itself (OnboardKit's AdTelemetry). */
    val reportTelemetry: Boolean = true,
)

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
        listener?.let { listeners[placement] = it }
        // Re-requesting over a ready ad burns a request and the fill
        cache[placement]?.takeIf { it.isFresh && it.ad.isReady }?.let { cached ->
            notifyListener(placement) { it.onApInterstitialLoad(cached.ad) }
            return
        }
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason =
            AdGate.skipReason(context, options.enabled && ids.isNotEmpty(), options.passesUaGate)
        if (skipReason != null) {
            if (options.reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, skipReason.key)
            }
            notifyListener(placement) { it.onAdFailedToLoad(null) }
            return
        }
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
                    if (apInterstitialAd == null) {
                        notifyListener(placement) { it.onAdFailedToLoad(null) }
                        return
                    }
                    cache[placement] = CachedAd(apInterstitialAd)
                    notifyListener(placement) { it.onApInterstitialLoad(apInterstitialAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    inFlight.remove(placement)
                    notifyListener(placement) { it.onAdFailedToLoad(adError) }
                }
            },
        )
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
    fun showSkipReason(context: Context, placement: String): AdSkipReason? = when {
        AdGate.isPurchased(context) -> AdSkipReason.PURCHASED
        !InterstitialFrequency.elapsed(context) -> AdSkipReason.CAPPED_BY_MODULE
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
     * Single-use: the buffer is dropped before `show()` so one fill can never show twice.
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
        // Decided BEFORE the buffer is touched. The interval rule lives downstream in
        // ERainAd.forceShowInterstitial, which answers a blocked show with a bare onNextAction —
        // so a tap one second inside the interval used to reach this method, drop a perfectly
        // good fill, and only then be refused. A withheld ad is not a spent ad.
        val blockReason = showSkipReason(context, placement)
        if (blockReason != null) {
            // PURCHASED still drops it: a bought entitlement must not leave a showable ad behind.
            // CAPPED_BY_MODULE and NOT_READY do not — the first still has an ad worth keeping,
            // the second has nothing to drop.
            if (blockReason == AdSkipReason.PURCHASED) cache.remove(placement)
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, blockReason.key)
            }
            callback.onSkipped(blockReason)
            callback.onComplete()
            return
        }
        // Committed to showing: single-use, so the buffer is dropped before show() to make one
        // fill impossible to show twice.
        val ad = takeFresh(placement) ?: run {
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, AdSkipReason.NOT_READY.key)
            }
            callback.onSkipped(AdSkipReason.NOT_READY)
            callback.onComplete()
            return
        }
        cache.remove(placement)
        val committed = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val complete = { if (completed.compareAndSet(false, true)) callback.onComplete() }
        ERainAd.getInstance().forceShowInterstitial(
            context,
            ad,
            object : AdCallback() {
                override fun onInterstitialShow() {
                    committed.set(true)
                    callback.onShowed()
                }

                override fun onNextAction() {
                    when (meaningOfNextAction(nextAction, committed.get(), completed.get())) {
                        NextActionMeaning.MODULE_CAP -> {
                            if (reportTelemetry) {
                                AdTracking.skipped(
                                    placement,
                                    AdFormat.INTERSTITIAL,
                                    AdSkipReason.CAPPED_BY_MODULE.key,
                                )
                            }
                            callback.onSkipped(AdSkipReason.CAPPED_BY_MODULE)
                            complete()
                        }
                        // The ad is on screen; the next screen starts underneath it.
                        NextActionMeaning.NEXT_SCREEN -> complete()
                        // onAdClosed / onAdFailedToShow owns the outcome in this mode.
                        NextActionMeaning.IGNORED -> Unit
                    }
                }

                override fun onAdClosed() {
                    callback.onClosed()
                    complete()
                }

                override fun onAdFailedToShow(adError: AdError?) {
                    if (reportTelemetry) {
                        AdTracking.skipped(
                            placement, AdFormat.INTERSTITIAL, AdSkipReason.FAILED_TO_SHOW.key,
                        )
                    }
                    callback.onSkipped(AdSkipReason.FAILED_TO_SHOW)
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
        cache.remove(placement)
        // inFlight is deliberately left alone: the walk it marks is not cancellable, and clearing
        // it here let the very next load() start a second concurrent request for the same
        // placement — the duplicate the guard exists to prevent. The in-flight fill still lands
        // and repopulates the cache.
        listeners.remove(placement)
    }

    @JvmStatic
    fun releaseAll() {
        cache.clear()
        inFlight.clear()
        listeners.clear()
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

    private fun notifyListener(placement: String, block: (AdCallback) -> Unit) {
        listeners[placement]?.let { runCatching { block(it) } }
    }
}
