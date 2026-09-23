package com.ads.module.helper.reward

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.ads.AdWaterfall
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.settings.AdBehavior
import com.ads.module.engine.RewardEngine
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
import java.util.concurrent.atomic.AtomicBoolean

/** Outcomes of one rewarded presentation, in the order GMA reports them. */
open class RewardShowCallback {

    /** GMA confirmed that the rewarded ad reached the screen. */
    open fun onShown() {}

    /** GMA recorded an impression. */
    open fun onImpression() {}

    /** The vendor confirmed a reward. Some mediation sources can report it after [onClosed]. */
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

    /**
     * Host fallback for `rewarded.buffer.after_close`; remote and asset config outrank it.
     * Off by default: a shown ad is not replaced unless the host asks for it.
     */
    @JvmStatic
    var bufferAfterClose: Boolean = AdBehavior.defaultBool("rewarded.buffer.after_close")

    private val cache = ConcurrentHashMap<String, CachedAd<RewardedAd>>()
    private val requests = ConcurrentHashMap<String, LoadRequest>()
    private val presentations = ConcurrentHashMap<String, Presentation>()

    private class LoadRequest {
        val listeners = mutableListOf<AdCallback>()
    }

    private class Presentation(
        var host: Activity?,
        val onSuccess: Runnable,
        val onFailed: Runnable,
    ) {
        var showing = false
        val settled = AtomicBoolean(false)
        var loadListener: AdCallback? = null
        var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    }

    /** Buffers an ad using exactly the same placement cache and request as [load]. */
    @JvmStatic
    @JvmOverloads
    fun preload(
        context: Context,
        placement: String,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        tierTimeoutMs: Long = AdBehavior.defaultNumber("rewarded.load.tier_timeout_ms"),
        listener: AdCallback? = null,
    ) = load(context, placement, adUnitIds, enabled, tierTimeoutMs, listener)

    /** [preload] with the waterfall and gates from `ad_config.json`. */
    @JvmStatic
    @JvmOverloads
    fun preload(context: Context, placement: String, listener: AdCallback? = null) =
        load(context, placement, listener)

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
        tierTimeoutMs: Long = AdBehavior.defaultNumber("rewarded.load.tier_timeout_ms"),
        listener: AdCallback? = null,
    ) {
        cache[placement]?.takeIf { it.isFresh }?.let { cached ->
            listener?.let { runCatching { it.onRewardAdLoaded(cached.ad) } }
            return
        }
        val ids = AdWaterfall.usableIds(adUnitIds)
        val skipReason = AdGate.skipReason(
            context, enabled && ids.isNotEmpty(), passesUaGate = true, checkNetwork = false,
        )
        if (skipReason != null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, skipReason.key)
            listener?.let { runCatching { it.onAdFailedToLoad(null) } }
            return
        }
        requests[placement]?.let { request ->
            listener?.let { request.listeners += it }
            return
        }
        val request = LoadRequest()
        listener?.let { request.listeners += it }
        requests[placement] = request
        ids.forEach { AdTracking.registerPlacement(it, placement) }
        AdTracking.request(placement, AdFormat.REWARDED, ids.first())
        val behavior = AdBehavior.values("rewarded", placement)
        val maxAgeMs = behavior.long("cache.max_age_ms", AdBehavior.defaultNumber("rewarded.cache.max_age_ms"))
        try {
            AdWaterfall.loadReward(
                context.applicationContext,
                ids,
                behavior.long("load.tier_timeout_ms", tierTimeoutMs),
                object : AdCallback() {
                    override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                        if (!requests.remove(placement, request)) return
                        if (rewardedAd == null) {
                            notifyListeners(request) { it.onAdFailedToLoad(null) }
                            return
                        }
                        cache[placement] = CachedAd(rewardedAd, maxAgeMs = maxAgeMs)
                        notifyListeners(request) { it.onRewardAdLoaded(rewardedAd) }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError?) {
                        if (!requests.remove(placement, request)) return
                        notifyListeners(request) { it.onAdFailedToLoad(adError) }
                    }
                },
            )
        } catch (_: RuntimeException) {
            if (requests.remove(placement, request)) {
                notifyListeners(request) { it.onAdFailedToLoad(null) }
            }
        }
    }

    /**
     * The same load, with the waterfall and the placement's gates read from `ad_config.json`.
     */
    @JvmStatic
    @JvmOverloads
    fun load(context: Context, placement: String, listener: AdCallback? = null) = load(
        context,
        placement,
        AdGate.adUnitIds(placement),
        enabled = AdGate.placementEnabled(placement) && AdGate.placementPassesUaGate(placement),
        listener = listener,
    )

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

    /** Shows the buffered ad on a resumed host; a background rejection keeps the fill for retry. */
    @JvmStatic
    fun show(activity: Activity, placement: String, callback: RewardShowCallback) {
        // Only for a placement the payload declares; an explicitly loaded key the config never
        // mentions stays the caller's decision.
        val blocked = AdRemoteConfig.getInstance().takeIf { it.declares(placement) }
            ?.let { AdGate.placementSkipReason(activity, placement, checkNetwork = false) }
        if (blocked != null) {
            // A bought entitlement must not leave a showable ad behind; a transient gate — consent
            // form on screen, UA not yet attributed — still has a fill worth keeping.
            if (blocked == AdSkipReason.PURCHASED) cache.remove(placement)
            AdTracking.skipped(placement, AdFormat.REWARDED, blocked.key)
            callback.onFailedToShow(0)
            return
        }
        if (!canShowOn(activity)) {
            AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.SHOW_IN_BACKGROUND.key)
            callback.onFailedToShow(0)
            return
        }
        val cached = cache.remove(placement)?.takeIf { it.isFresh }
        if (cached == null) {
            AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.NOT_READY.key)
            callback.onFailedToShow(0)
            return
        }
        showInternal(activity, placement, cached.ad, callback)
    }

    /**
     * [show] for a caller that only reacts to the outcome: [onComplete] runs exactly once with
     * whether the user earned. A failure to show reports `false` — nothing was earned either way.
     *
     * Take the [RewardShowCallback] overload instead when the screen needs `onEarned` while the ad
     * is still up, the vendor error code, or clicks.
     */
    @JvmStatic
    fun show(activity: Activity, placement: String, onComplete: (earned: Boolean) -> Unit) =
        show(activity, placement, completionOnly(onComplete))

    /** Keeps the completion-only adapter once-only even when driven independently. */
    private fun completionOnly(action: (Boolean) -> Unit) = object : RewardShowCallback() {
        private val settled = AtomicBoolean(false)

        override fun onClosed(earned: Boolean) {
            if (settled.compareAndSet(false, true)) action(earned)
        }

        override fun onFailedToShow(codeError: Int) {
            if (settled.compareAndSet(false, true)) action(false)
        }
    }

    /**
     * Uses the placement's buffered ad, waits for its current load, or starts one if needed.
     * [onSuccess] runs only after the user earned and the ad closed; [onFailed] covers every
     * other outcome, including a duplicate call while this placement is waiting or showing.
     * Leaving the host before dispatch cancels this presentation; a late fill remains cached
     * for a new explicit trigger. Once dispatched, only the vendor settles the outcome.
     */
    @JvmStatic
    @JvmOverloads
    fun loadAndShow(
        activity: Activity,
        placement: String,
        adUnitIds: List<String>,
        enabled: Boolean = true,
        tierTimeoutMs: Long = AdBehavior.defaultNumber("rewarded.load.tier_timeout_ms"),
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
        if (!canShowOn(activity)) {
            AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.SHOW_IN_BACKGROUND.key)
            onFailed.run()
            return
        }
        val presentation = Presentation(activity, onSuccess, onFailed)
        if (presentations.putIfAbsent(placement, presentation) != null) {
            onFailed.run()
            return
        }
        observePendingHost(placement, presentation)
        val listener = object : AdCallback() {
            override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
                if (presentations[placement] !== presentation || presentation.settled.get()) return
                val host = presentation.host
                if (host == null || !canShowOn(host)) {
                    cancelPendingPresentation(placement, presentation)
                    return
                }
                // Another explicit show or a reentrant load listener may already have spent
                // or released this fill. Never show the callback's raw ad a second time.
                val cached = cache[placement]?.takeIf { it.ad === rewardedAd }
                if (cached == null || !cache.remove(placement, cached) || !cached.isFresh) {
                    finishPresentation(placement, presentation, earned = false)
                    return
                }
                presentation.showing = true
                detachPendingPresentation(placement, presentation)
                showInternal(
                    host, placement, cached.ad,
                    object : RewardShowCallback() {
                        override fun onClosed(earned: Boolean) {
                            finishPresentation(placement, presentation, earned)
                        }

                        override fun onFailedToShow(codeError: Int) {
                            finishPresentation(placement, presentation, earned = false)
                        }
                    },
                )
            }

            override fun onAdFailedToLoad(adError: LoadAdError?) {
                finishPresentation(placement, presentation, earned = false)
            }
        }
        presentation.loadListener = listener
        load(
            activity.applicationContext,
            placement,
            ids,
            enabled,
            tierTimeoutMs,
            listener,
        )
    }

    /**
     * The same chain, with the waterfall and the placement's gates read from `ad_config.json`.
     */
    @JvmStatic
    fun loadAndShow(
        activity: Activity,
        placement: String,
        onSuccess: Runnable,
        onFailed: Runnable,
    ) = loadAndShow(
        activity,
        placement,
        AdGate.adUnitIds(placement),
        enabled = AdGate.placementEnabled(placement) && AdGate.placementPassesUaGate(placement),
        onSuccess = onSuccess,
        onFailed = onFailed,
    )

    @JvmStatic
    fun release(placement: String) {
        cache.remove(placement)
        val request = requests.remove(placement)
        val presentation = presentations[placement]?.takeUnless { it.showing }
        presentation?.let { finishPresentation(placement, it, earned = false) }
        request?.let { notifyListeners(it) { listener -> listener.onAdFailedToLoad(null) } }
    }

    @JvmStatic
    fun releaseAll() {
        cache.clear()
        // Detach the old generation before notifying callers: a callback can start a new load.
        val pendingRequests = requests.values.toList()
        requests.clear()
        val pendingPresentations = presentations.filterValues { !it.showing }
        pendingPresentations.forEach { (placement, presentation) ->
            presentations.remove(placement, presentation)
        }
        pendingPresentations.forEach { (placement, presentation) ->
            finishPresentation(placement, presentation, earned = false)
        }
        pendingRequests.forEach { request ->
            notifyListeners(request) { it.onAdFailedToLoad(null) }
        }
    }

    private fun showInternal(activity: Activity, placement: String, ad: RewardedAd?, callback: RewardShowCallback) {
        // The module answers purchased users with a lone onUserEarnedReward(null) and no
        // terminal callback; map that to a completed earn so the caller is never stranded
        if (AdGate.isPurchased(activity)) {
            runCatching { callback.onEarned(null) }
            runCatching { callback.onClosed(earned = true) }
            return
        }
        var earned = false
        val settled = AtomicBoolean(false)
        val vendorCallback = object : RewardCallback {
            override fun onRewardedAdShown() {
                if (!settled.get()) runCatching { callback.onShown() }
            }

            override fun onAdImpression() {
                if (!settled.get()) runCatching { callback.onImpression() }
            }

            override fun onUserEarnedReward(item: RewardItem?) {
                // Preserve 5.3.2's event ordering: close is a snapshot; a mediation source
                // can still report its real earned event afterwards without revising close.
                if (earned) return
                earned = true
                runCatching { callback.onEarned(item) }
            }

            override fun onRewardedAdClosed() {
                if (!settled.compareAndSet(false, true)) return
                runCatching { callback.onClosed(earned) }
                bufferNext(activity.applicationContext, placement)
            }

            override fun onRewardedAdFailedToShow(codeError: Int) {
                if (settled.compareAndSet(false, true)) runCatching { callback.onFailedToShow(codeError) }
            }

            override fun onAdClicked() {
                if (!settled.get()) runCatching { callback.onClicked() }
            }
        }
        try {
            RewardEngine.show(activity, ad, vendorCallback)
        } catch (_: RuntimeException) {
            vendorCallback.onRewardedAdFailedToShow(0)
        }
    }

    /**
     * Loads the next rewarded ad once this one closes, when the host opted in. A placement the
     * remote config never declares is the caller's own key, so it keeps owning the loading.
     */
    private fun bufferNext(context: Context, placement: String) {
        if (!AdBehavior.values("rewarded", placement).boolean("buffer.after_close", bufferAfterClose)) return
        if (!AdRemoteConfig.getInstance().declares(placement)) return
        load(context, placement)
    }

    private fun finishPresentation(placement: String, presentation: Presentation, earned: Boolean) {
        if (!presentation.settled.compareAndSet(false, true)) return
        presentations.remove(placement, presentation)
        detachPendingPresentation(placement, presentation)
        runCatching {
            if (earned) presentation.onSuccess.run() else presentation.onFailed.run()
        }
    }

    private fun canShowOn(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed ||
            !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return false
        return if (activity is LifecycleOwner) {
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } else {
            // Process lifecycle deliberately debounces stop. A plain Activity must still own
            // the focused window rather than pass during that background grace period.
            activity.hasWindowFocus()
        }
    }

    private fun observePendingHost(placement: String, presentation: Presentation) {
        val host = presentation.host ?: return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                if (presentation.host === activity) cancelPendingPresentation(placement, presentation)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (presentation.host === activity) cancelPendingPresentation(placement, presentation)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        presentation.lifecycleCallbacks = callbacks
        host.application.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun cancelPendingPresentation(placement: String, presentation: Presentation) {
        if (presentation.showing || presentation.settled.get()) return
        AdTracking.skipped(placement, AdFormat.REWARDED, AdSkipReason.SHOW_IN_BACKGROUND.key)
        finishPresentation(placement, presentation, earned = false)
    }

    private fun detachPendingPresentation(placement: String, presentation: Presentation) {
        presentation.loadListener?.let { requests[placement]?.listeners?.remove(it) }
        presentation.loadListener = null
        presentation.lifecycleCallbacks?.let { callbacks ->
            presentation.host?.application?.unregisterActivityLifecycleCallbacks(callbacks)
        }
        presentation.lifecycleCallbacks = null
        presentation.host = null
    }

    private fun notifyListeners(request: LoadRequest, block: (AdCallback) -> Unit) {
        val listeners = request.listeners.toList()
        request.listeners.clear()
        listeners.forEach { runCatching { block(it) } }
    }
}
