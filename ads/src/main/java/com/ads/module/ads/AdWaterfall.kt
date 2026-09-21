package com.ads.module.ads

import android.content.Context
import android.util.Log
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.engine.NativeEngine
import com.ads.module.engine.adMainScope
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Requests one ad unit at a time, highest floor first, and stops at the first fill.
 *
 * The list *is* the waterfall: index 0 is the high floor, the last entry is the all-price
 * fallback. Pass one id and it behaves exactly like a plain load. Blank and repeated ids are
 * dropped, so a half-filled remote payload cannot open a hole in the order.
 *
 * All state lives on the stack of a single call, which is what lets several placements load at
 * once.
 */
object AdWaterfall {

    private const val TAG = "AdWaterfall"

    /** Per-ad-unit request timeout. Matches the audited REQUEST_AD_TIMEOUT. */
    const val DEFAULT_TIER_TIMEOUT_MS = 30_000L

    private fun canContinue(context: Context, callback: AdCallback): Boolean {
        // A fallback is another vendor request, so it needs current authority too.
        // Keep rewarded's offline behavior; each helper owns its network policy.
        if (AdGate.skipReason(context, true, true, false) == null) return true
        callback.onAdFailedToLoad(null)
        return false
    }

    /**
     * Walks `adUnitIds` until one native fills. A process-owned preload must not retain a
     * departed Activity.
     *
     * @param callback `onNativeAdLoaded` on the first fill, `onAdFailedToLoad` once every tier
     *                 has failed. Clicks are forwarded from whichever tier won.
     */
    internal fun loadNative(
        context: Context,
        adUnitIds: List<String?>?,
        layoutRes: Int,
        tierTimeoutMs: Long,
        callback: AdCallback,
    ) {
        val tiers = usableIds(adUnitIds)
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadNative: no usable ad unit id")
            callback.onAdFailedToLoad(null)
            return
        }
        loadNativeTier(context, tiers, layoutRes, tierTimeoutMs, 0, callback)
    }

    private fun loadNativeTier(
        activity: Context,
        tiers: List<String>,
        layoutRes: Int,
        tierTimeoutMs: Long,
        index: Int,
        callback: AdCallback,
    ) {
        if (index >= tiers.size) {
            Log.w(TAG, "loadNative: all " + tiers.size + " tier(s) failed")
            callback.onAdFailedToLoad(null)
            return
        }
        if (!canContinue(activity, callback)) return
        val tier = Tier(tierTimeoutMs) {
            loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1, callback)
        }
        NativeEngine.load(activity, tiers[index], layoutRes,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    // A fill from a tier the waterfall already moved past has nowhere to go
                    if (!tier.settle()) {
                        nativeAd.admobNativeAd?.destroy()
                        return
                    }
                    callback.onNativeAdLoaded(nativeAd)
                }

                override fun onAdFailedToLoad(i: LoadAdError?) {
                    if (!tier.settle()) return
                    Log.w(TAG, "loadNative tier " + (index + 1) + "/" + tiers.size +
                        " failed: " + (i?.message ?: "null"))
                    loadNativeTier(activity, tiers, layoutRes, tierTimeoutMs, index + 1, callback)
                }

                override fun onAdClicked() {
                    callback.onAdClicked()
                }

                override fun onAdOpened() {
                    callback.onAdOpened()
                }

                override fun onAdImpression() {
                    // Only the winning tier's view can render, so no settle() gate needed
                    callback.onAdImpression()
                }
            })
    }

    /**
     * Walks `adUnitIds` until one interstitial fills.
     *
     * @param callback `onApInterstitialLoad` on the first fill, `onAdFailedToLoad` once every
     *                 tier has failed.
     */
    internal fun loadInterstitial(
        context: Context,
        adUnitIds: List<String?>?,
        tierTimeoutMs: Long,
        callback: AdCallback,
    ) {
        val tiers = usableIds(adUnitIds)
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadInterstitial: no usable ad unit id")
            callback.onAdFailedToLoad(null)
            return
        }
        loadInterstitialTier(context, tiers, tierTimeoutMs, 0, callback)
    }

    private fun loadInterstitialTier(
        context: Context,
        tiers: List<String>,
        tierTimeoutMs: Long,
        index: Int,
        callback: AdCallback,
    ) {
        if (index >= tiers.size) {
            Log.w(TAG, "loadInterstitial: all " + tiers.size + " tier(s) failed")
            callback.onAdFailedToLoad(null)
            return
        }
        if (!canContinue(context, callback)) return
        val tier = Tier(tierTimeoutMs) {
            loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback)
        }
        ERainAd.getInstance().getInterstitialAds(context, tiers[index], object : AdCallback() {
            override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                if (!tier.settle()) return
                // A null wrapper is how the module reports a purchased or capped user; that is
                // this tier declining, not a fill.
                if (apInterstitialAd == null) {
                    loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback)
                    return
                }
                callback.onApInterstitialLoad(apInterstitialAd)
            }

            override fun onAdFailedToLoad(i: LoadAdError?) {
                if (!tier.settle()) return
                Log.w(TAG, "loadInterstitial tier " + (index + 1) + "/" + tiers.size +
                    " failed: " + (i?.message ?: "null"))
                loadInterstitialTier(context, tiers, tierTimeoutMs, index + 1, callback)
            }
        })
    }

    /**
     * Walks `adUnitIds` until one rewarded ad fills.
     *
     * @param callback `onRewardAdLoaded` on the first fill, `onAdFailedToLoad` once every tier
     *                 has failed.
     */
    internal fun loadReward(
        context: Context,
        adUnitIds: List<String?>?,
        tierTimeoutMs: Long,
        callback: AdCallback,
    ) {
        val tiers = usableIds(adUnitIds)
        if (tiers.isEmpty()) {
            Log.w(TAG, "loadReward: no usable ad unit id")
            callback.onAdFailedToLoad(null)
            return
        }
        loadRewardTier(context, tiers, tierTimeoutMs, 0, callback)
    }

    private fun loadRewardTier(
        context: Context,
        tiers: List<String>,
        tierTimeoutMs: Long,
        index: Int,
        callback: AdCallback,
    ) {
        if (index >= tiers.size) {
            Log.w(TAG, "loadReward: all " + tiers.size + " tier(s) failed")
            callback.onAdFailedToLoad(null)
            return
        }
        if (!canContinue(context, callback)) return
        val tier = Tier(tierTimeoutMs) {
            loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback)
        }
        val tierCallback = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                if (!tier.settle()) return
                // The module answers a purchased user with silence, not null — the tier timeout
                // covers that; a null here is still a decline, not a fill.
                if (rewardedAd == null) {
                    loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback)
                    return
                }
                callback.onRewardAdLoaded(rewardedAd)
            }

            override fun onAdFailedToLoad(i: LoadAdError?) {
                if (!tier.settle()) return
                Log.w(TAG, "loadReward tier " + (index + 1) + "/" + tiers.size +
                    " failed: " + (i?.message ?: "null"))
                loadRewardTier(context, tiers, tierTimeoutMs, index + 1, callback)
            }
        }
        try {
            ERainAd.getInstance().initRewardAds(context, tiers[index], tierCallback)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            // A dispatch failure must settle the same tier as a vendor load failure; otherwise its
            // already-armed timeout can start another request after the caller has finished.
            tierCallback.onAdFailedToLoad(null)
        }
    }

    /** The ids actually worth requesting: declared order, minus blanks and repeats. */
    @JvmStatic
    fun usableIds(adUnitIds: List<String?>?): List<String> {
        if (adUnitIds == null) return ArrayList()
        val unique = LinkedHashSet<String>()
        for (id in adUnitIds) {
            if (id != null && id.trim().isNotEmpty()) unique.add(id)
        }
        return ArrayList(unique)
    }

    /**
     * One step of the waterfall. The vendor callback and the timeout race; the first one decides
     * and the other is ignored, so a floor that never answers cannot stall the tiers below it.
     */
    private class Tier(timeoutMs: Long, advance: () -> Unit) {
        private val settled = AtomicBoolean(false)
        private val timeout: Job = adMainScope.launch {
            delay(timeoutMs)
            if (settled.compareAndSet(false, true)) {
                Log.w(TAG, "tier timed out after " + timeoutMs + "ms")
                advance()
            }
        }

        // settled, not the cancel, is what rejects a late vendor callback: cancel only stops the timer.
        fun settle(): Boolean {
            if (!settled.compareAndSet(false, true)) return false
            timeout.cancel()
            return true
        }
    }
}
