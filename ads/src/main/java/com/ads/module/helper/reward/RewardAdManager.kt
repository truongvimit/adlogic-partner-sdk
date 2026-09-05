package com.ads.module.helper.reward

import android.app.Activity
import android.content.Context
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.consent.ConsentCenter
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.CachedAd
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.AdLoadContext
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import io.trackkit.AdFormat
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Outcomes of one rewarded presentation, in the order GMA reports them. */
open class RewardShowCallback {

    /** The user watched far enough to earn. Grant the reward from [onClosed]. */
    open fun onEarned(item: RewardItem?) {}

    /** Terminal: the ad is gone; [earned] says whether [onEarned] fired before it. */
    open fun onClosed(earned: Boolean) {}

    /** Terminal: the ad never reached the screen. */
    open fun onFailedToShow(codeError: Int) {}

    open fun onClicked() {}
}

/**
 * Placement-keyed rewarded store: load-and-cache or load-and-show, with the gate and
 * telemetry every app used to hand-roll.
 *
 * Reward gating never checks the network — a request that fails offline already reports
 * through `onAdFailedToLoad`, and blocking earn flows on a connectivity probe costs real
 * user goodwill.
 *
 * Main-thread only — the legacy GMA SDK requires load/show calls there.
 */
object RewardAdManager {

    private val cache = ConcurrentHashMap<String, BufferedReward>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val loadGenerations = ConcurrentHashMap<String, Long>()
    private var nextLoadGeneration = 0L
    // One observer per placement; the freshest caller hears the in-flight outcome
    private val listeners = ConcurrentHashMap<String, AdCallback>()
    private val presentations = ConcurrentHashMap<String, Presentation>()

    private class BufferedReward(ad: RewardedAd, val context: Context, val personalized: Boolean) {
        val cached = CachedAd(ad)
        val ad: RewardedAd get() = cached.ad
        val isFresh: Boolean get() = cached.isFresh && personalized == ConsentCenter.canPersonalize()
    }

    private class Presentation(activity: Activity, onSuccess: Runnable, onFailed: Runnable) {
        val activityReference = WeakReference(activity)
        val context: Context = activity.applicationContext
        val personalized = ConsentCenter.canPersonalize()
        var showing = false
        var completion: ((Boolean) -> Unit)? = { earned ->
            if (earned) onSuccess.run() else onFailed.run()
        }
    }

    /**
     * Buffers one rewarded ad for [placement], walking [adUnitIds] highest floor first;
     * idempotent while ready or in flight.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        context: Context,
        placement: String,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS,
        listener: AdCallback? = null,
    ) {
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason = AdGate.skipReason(
            context, enabled && ids.isNotEmpty(), passesUaGate = true, checkNetwork = false,
        )
        if (skipReason != null) {
            release(placement)
            AdTracking.skipped(placement, AdFormat.REWARDED, skipReason.key)
            listener?.let { runCatching { it.onAdFailedToLoad(null) } }
            return
        }
        // The gate can synchronously release seeded-premium buffers. Register the incoming
        // callback afterwards, and never let a cached value bypass current authorization.
        listener?.let { listeners[placement] = it }
        cache[placement]?.takeIf { it.isFresh }?.let { cached ->
            notifyListener(placement) { it.onRewardAdLoaded(cached.ad) }
            return
        }
        cache.remove(placement)
        if (!inFlight.add(placement)) return
        val generation = ++nextLoadGeneration
        loadGenerations[placement] = generation
        val personalized = ConsentCenter.canPersonalize()
        val applicationContext = context.applicationContext
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdWaterfall.loadReward(
            context,
            ids,
            tierTimeoutMs,
            AdLoadContext(placement, AdFormat.REWARDED),
            object : AdCallback() {
                override fun canAcceptLoadedAd(): Boolean =
                    loadGenerations[placement] == generation &&
                        AdGate.skipReason(applicationContext, enabled = true, checkNetwork = false) == null &&
                        ConsentCenter.canPersonalize() == personalized && loadGenerations[placement] == generation

                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    if (loadGenerations[placement] != generation) return
                    val allowed = AdGate.skipReason(applicationContext, enabled = true, checkNetwork = false) == null &&
                        ConsentCenter.canPersonalize() == personalized
                    // Evaluating the gate may itself release this request after a purchase.
                    if (!loadGenerations.remove(placement, generation)) return
                    inFlight.remove(placement)
                    if (rewardedAd == null || !allowed) {
                        notifyListener(placement) { it.onAdFailedToLoad(null) }
                        return
                    }
                    cache[placement] = BufferedReward(rewardedAd, applicationContext, personalized)
                    notifyListener(placement) { it.onRewardAdLoaded(rewardedAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    if (!loadGenerations.remove(placement, generation)) return
                    inFlight.remove(placement)
                    notifyListener(placement) { it.onAdFailedToLoad(adError) }
                }
            },
        )
    }

    /** True when a fresh rewarded ad is buffered for [placement]. */
    @JvmStatic
    fun isReady(placement: String): Boolean {
        val cached = cache[placement] ?: return false
        if (!cached.isFresh || AdGate.skipReason(cached.context, enabled = true, checkNetwork = false) != null) {
            cache.remove(placement, cached)
            return false
        }
        return true
    }

    /**
     * Shows the buffered ad. Single-use: the buffer is dropped before `show()`.
     * Premium users receive an automatic earn/close even if entitlement invalidation cleared it.
     */
    @JvmStatic
    fun show(activity: Activity, placement: String, callback: RewardShowCallback) {
        // Premium invalidation may already have removed the buffer; preserve the rewarded
        // presentation API's automatic earn/close outcome without requiring an ad object.
        if (AdGate.isPurchased(activity)) {
            cache.remove(placement)
            showInternal(activity, null, callback)
            return
        }
        val cached = cache.remove(placement)?.takeIf { it.isFresh }
        if (cached == null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.NOT_READY.key)
            callback.onFailedToShow(0)
            return
        }
        showInternal(activity, cached.ad, callback)
    }

    /**
     * The classic gate → request → load → show chain in one call. [onSuccess] follows earning
     * and dismissal. If a new premium grant releases a still-loading request, it automatically
     * earns without presenting an ad; other cancelled loads invoke [onFailed]. Once presentation
     * starts, release preserves the real earn/dismiss outcome rather than completing it early.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        placement: String,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS,
        onSuccess: Runnable,
        onFailed: Runnable,
    ) {
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason = AdGate.skipReason(
            activity, enabled && ids.isNotEmpty(), passesUaGate = true, checkNetwork = false,
        )
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, skipReason.key)
            onFailed.run()
            return
        }
        // The guard spans load AND show — loadAndShow's contract is one presentation
        if (!inFlight.add(placement)) return
        val presentation = Presentation(activity, onSuccess, onFailed)
        presentations[placement] = presentation
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdWaterfall.loadReward(
            activity,
            ids,
            tierTimeoutMs,
            AdLoadContext(placement, AdFormat.REWARDED),
            object : AdCallback() {
                override fun canAcceptLoadedAd(): Boolean {
                    if (presentations[placement] !== presentation) return false
                    val allowed = AdGate.skipReason(presentation.context, enabled = true,
                        checkNetwork = false) == null &&
                        ConsentCenter.canPersonalize() == presentation.personalized
                    val owner = presentation.activityReference.get()
                    return allowed && owner != null && !owner.isFinishing && !owner.isDestroyed &&
                        presentations[placement] === presentation
                }

                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    if (presentations[placement] !== presentation) return
                    val purchased = AdGate.isPurchased(presentation.context)
                    if (presentations[placement] !== presentation) return
                    if (!purchased && ConsentCenter.canPersonalize() != presentation.personalized) {
                        finishPresentation(placement, presentation, false)
                        return
                    }
                    val owner = presentation.activityReference.get()
                    if (owner == null || owner.isFinishing || owner.isDestroyed) {
                        finishPresentation(placement, presentation, false)
                        return
                    }
                    presentation.showing = true
                    showInternal(
                        owner, rewardedAd,
                        object : RewardShowCallback() {
                            override fun onClosed(earned: Boolean) {
                                finishPresentation(placement, presentation, earned)
                            }

                            override fun onFailedToShow(codeError: Int) {
                                finishPresentation(placement, presentation, false)
                            }
                        },
                    )
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    if (presentations[placement] === presentation) {
                        finishPresentation(placement, presentation, false)
                    }
                }
            },
        )
    }

    /**
     * Drops buffered/in-flight loading work and settles its listener. A pending [loadAndShow]
     * succeeds for a newly premium user and fails otherwise. An already presenting instance
     * keeps its actual terminal callback; it cannot clear a replacement load for this placement.
     */
    @JvmStatic
    fun release(placement: String) {
        cache.remove(placement)
        inFlight.remove(placement)
        val cancelledLoad = loadGenerations.remove(placement)
        val listener = listeners.remove(placement)
        val presentation = presentations.remove(placement)
        if (cancelledLoad != null) listener?.let { runCatching { it.onAdFailedToLoad(null) } }
        if (presentation != null && !presentation.showing) {
            finishPresentation(placement, presentation, AdGate.isPurchased(presentation.context))
        }
    }

    /** Applies [release] to every owned placement, including pending load-and-show requests. */
    @JvmStatic
    fun releaseAll() {
        (cache.keys + inFlight + loadGenerations.keys + listeners.keys + presentations.keys).toSet().forEach(::release)
    }

    private fun finishPresentation(placement: String, presentation: Presentation, earned: Boolean) {
        val completion = presentation.completion ?: return
        presentation.completion = null
        if (presentations.remove(placement, presentation)) inFlight.remove(placement)
        runCatching { completion(earned) }
    }

    private fun showInternal(activity: Activity, ad: RewardedAd?, callback: RewardShowCallback) {
        // The module answers purchased users with a lone onUserEarnedReward(null) and no
        // terminal callback; map that to a completed earn so the caller is never stranded
        if (AdGate.isPurchased(activity)) {
            callback.onEarned(null)
            callback.onClosed(earned = true)
            return
        }
        if (AdGate.skipReason(activity, enabled = true, checkNetwork = false) != null) {
            callback.onFailedToShow(0)
            return
        }
        var earned = false
        ERainAd.getInstance().showRewardAds(
            activity,
            ad,
            object : RewardCallback {
                override fun onUserEarnedReward(item: RewardItem?) {
                    earned = true
                    callback.onEarned(item)
                }

                override fun onRewardedAdClosed() {
                    callback.onClosed(earned)
                }

                override fun onRewardedAdFailedToShow(codeError: Int) {
                    callback.onFailedToShow(codeError)
                }

                override fun onAdClicked() {
                    callback.onClicked()
                }
            },
        )
    }

    private fun notifyListener(placement: String, block: (AdCallback) -> Unit) {
        listeners[placement]?.let { runCatching { block(it) } }
    }
}
