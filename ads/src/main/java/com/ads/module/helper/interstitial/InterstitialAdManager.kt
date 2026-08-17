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
 */
open class InterShowCallback {

    /** The ad is committed to the screen. */
    open fun onShowed() {}

    /** The ad was displayed and dismissed. */
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
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        if (!inFlight.add(placement)) return
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
     * Shows the buffered ad for [placement].
     *
     * Single-use: the buffer is dropped before `show()` so one fill can never show twice.
     * The module's interval/counter caps surface as [AdSkipReason.CAPPED_BY_MODULE] — it
     * answers those with a bare `onNextAction`, told apart by the commit marker.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        placement: String,
        callback: InterShowCallback,
        reportTelemetry: Boolean = true,
    ) {
        val ad = takeFresh(placement)
        // Every show call consumes the buffer, blocked ones included — a skip must force the
        // next load through the gate again instead of resurrecting a pre-skip fill
        cache.remove(placement)
        val blockReason = when {
            ad == null -> AdSkipReason.NOT_READY
            AdGate.isPurchased(context) -> AdSkipReason.PURCHASED
            else -> null
        }
        if (blockReason != null || ad == null) {
            val reason = blockReason ?: AdSkipReason.NOT_READY
            if (reportTelemetry) {
                AdTracking.skipped(placement, AdFormat.INTERSTITIAL, reason.key)
            }
            callback.onSkipped(reason)
            callback.onComplete()
            return
        }
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
                    // completed-guard: onAdFailedToShow is chased by a bare onNextAction when
                    // openActivityAfterShowInterAds is off — that pair is one failure, not a cap
                    if (!committed.get() && !completed.get()) {
                        // No commit marker: the module short-circuited before showing
                        if (reportTelemetry) {
                            AdTracking.skipped(
                                placement, AdFormat.INTERSTITIAL, AdSkipReason.CAPPED_BY_MODULE.key,
                            )
                        }
                        callback.onSkipped(AdSkipReason.CAPPED_BY_MODULE)
                    }
                    complete()
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
        )
    }

    @JvmStatic
    fun release(placement: String) {
        cache.remove(placement)
        inFlight.remove(placement)
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
