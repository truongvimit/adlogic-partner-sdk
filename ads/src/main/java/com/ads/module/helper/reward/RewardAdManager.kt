package com.ads.module.helper.reward

import android.app.Activity
import android.content.Context
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.CachedAd
import com.ads.module.tracking.AdTracking
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import io.trackkit.AdFormat
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

    private val cache = ConcurrentHashMap<String, CachedAd<RewardedAd>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    // One observer per placement; the freshest caller hears the in-flight outcome
    private val listeners = ConcurrentHashMap<String, AdCallback>()

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
        listener?.let { listeners[placement] = it }
        cache[placement]?.takeIf { it.isFresh }?.let { cached ->
            notifyListener(placement) { it.onRewardAdLoaded(cached.ad) }
            return
        }
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason = AdGate.skipReason(
            context, enabled && ids.isNotEmpty(), passesUaGate = true, checkNetwork = false,
        )
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, skipReason.key)
            notifyListener(placement) { it.onAdFailedToLoad(null) }
            return
        }
        if (!inFlight.add(placement)) return
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdTracking.request(placement, AdFormat.REWARDED, ids.first())
        AdWaterfall.loadReward(
            context,
            ids,
            tierTimeoutMs,
            object : AdCallback() {
                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    inFlight.remove(placement)
                    if (rewardedAd == null) {
                        notifyListener(placement) { it.onAdFailedToLoad(null) }
                        return
                    }
                    cache[placement] = CachedAd(rewardedAd)
                    notifyListener(placement) { it.onRewardAdLoaded(rewardedAd) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
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
        if (!cached.isFresh) {
            cache.remove(placement)
            return false
        }
        return true
    }

    /** Shows the buffered ad. Single-use: the buffer is dropped before `show()`. */
    @JvmStatic
    fun show(activity: Activity, placement: String, callback: RewardShowCallback) {
        val cached = cache.remove(placement)?.takeIf { it.isFresh }
        if (cached == null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.NOT_READY.key)
            callback.onFailedToShow(0)
            return
        }
        showInternal(activity, cached.ad, callback)
    }

    /**
     * The classic gate → request → load → show chain in one call: [onSuccess] only after
     * the user earned and the ad closed, [onFailed] on every other outcome.
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
        AdTracking.request(placement, AdFormat.REWARDED, ids.first())
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdWaterfall.loadReward(
            activity,
            ids,
            tierTimeoutMs,
            object : AdCallback() {
                override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                    showInternal(
                        activity, rewardedAd,
                        object : RewardShowCallback() {
                            override fun onClosed(earned: Boolean) {
                                inFlight.remove(placement)
                                if (earned) onSuccess.run() else onFailed.run()
                            }

                            override fun onFailedToShow(codeError: Int) {
                                inFlight.remove(placement)
                                onFailed.run()
                            }
                        },
                    )
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    inFlight.remove(placement)
                    onFailed.run()
                }
            },
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

    private fun showInternal(activity: Activity, ad: RewardedAd?, callback: RewardShowCallback) {
        // The module answers purchased users with a lone onUserEarnedReward(null) and no
        // terminal callback; map that to a completed earn so the caller is never stranded
        if (AdGate.isPurchased(activity)) {
            callback.onEarned(null)
            callback.onClosed(earned = true)
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
