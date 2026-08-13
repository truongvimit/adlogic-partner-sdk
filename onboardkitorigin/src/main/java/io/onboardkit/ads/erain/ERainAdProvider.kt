package io.onboardkit.ads.erain

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.billing.AppPurchase
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdmobHelper
import com.ads.module.util.SharePreferenceUtils
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.ObInterstitialCallback
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.trackkit.PlacementRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [OnboardingAdProvider], backed by the project's `:ads` module (AdMob legacy).
 *
 * Each placement walks its own ad unit list top to bottom — highest floor first, next id only
 * once the current one fails to fill. One request in flight per placement, and the in-flight
 * marker is cleared on both success and failure so a placement that failed can be retried.
 *
 * Buffered ads carry their load time: GMA drops an unshown ad after roughly an hour, and a stale
 * wrapper reports itself as ready right up until `show()` silently does nothing.
 */
class ERainAdProvider(
    /**
     * How long one ad unit may take before the waterfall moves to the next floor.
     *
     * `30 s` is the audited `REQUEST_AD_TIMEOUT`; the whole waterfall is bounded by the caller's
     * own budget (`ob_splash_ad_budget_ms`, `60 s`, the audited `LOAD_AD_TIMEOUT`). Lowering this
     * trades fill rate for speed — measure before you do.
     */
    private val tierTimeoutMs: Long = AdWaterfall.DEFAULT_TIER_TIMEOUT_MS,
) : OnboardingAdProvider {

    private val natives = ConcurrentHashMap<String, CachedAd<ApNativeAd>>()
    private val nativeInFlight = ConcurrentHashMap.newKeySet<String>()
    private val interstitials = ConcurrentHashMap<String, CachedAd<ApInterstitialAd>>()
    private val interInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * One observer per placement — the screen currently showing it. A list here grew a wrapper per
     * recycled fragment, and every stale wrapper re-reported load events against a dead Activity.
     */
    private val listeners = ConcurrentHashMap<String, AdEventListener>()

    override fun isPremium(context: Context): Boolean =
        runCatching { AppPurchase.getInstance().isPurchased(context) }.getOrDefault(false)

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        val key = request.placement.key
        if (isNativeReady(request.placement)) return
        val ids = request.unit.loadOrder
        if (ids.isEmpty()) {
            ObLog.w(ObLog.Section.LOAD, "$key skip — no usable ad unit id")
            return
        }
        // GMA's paid-event callback only knows the ad unit id; this is the only place that knows
        // which onboarding screen asked for it.
        ids.forEach { PlacementRegistry.register(it, key) }
        if (!nativeInFlight.add(key)) return
        ObLog.d(ObLog.Section.LOAD, "$key native waterfall tiers=${ids.size} top=${shortId(ids.first())}")
        AdWaterfall.loadNative(
            activity,
            ids,
            request.layoutRes,
            tierTimeoutMs,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    publishNative(key, nativeAd)
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    failNative(key, "all ${ids.size} tier(s) failed")
                }

                override fun onAdClicked() {
                    notifyListener(key) { it.onClicked() }
                }
            },
        )
    }

    private fun publishNative(key: String, ad: ApNativeAd) {
        ObLog.d(ObLog.Section.LOAD, "$key native FILLED")
        natives.put(key, CachedAd(ad))?.let { destroyNative(it.ad) }
        nativeInFlight.remove(key)
        notifyListener(key) { it.onLoaded() }
    }

    private fun failNative(key: String, reason: String) {
        ObLog.w(ObLog.Section.LOAD, "$key native UNFILLED — $reason")
        nativeInFlight.remove(key)
        notifyListener(key) { it.onFailedToLoad() }
    }

    private fun destroyNative(ad: ApNativeAd) {
        runCatching { ad.admobNativeAd?.destroy() }
    }

    override fun isNativeReady(placement: AdPlacement): Boolean = takeFreshNative(placement) != null

    override fun isNativeLoading(placement: AdPlacement): Boolean =
        placement.key in nativeInFlight

    /** Drops the buffer when it has gone stale, so a caller never binds an expired ad. */
    private fun takeFreshNative(placement: AdPlacement): CachedAd<ApNativeAd>? {
        val cached = natives[placement.key] ?: return null
        if (cached.isFresh) return cached
        ObLog.w(ObLog.Section.LOAD, "${placement.key} native EXPIRED — dropping buffer")
        natives.remove(placement.key)
        destroyNative(cached.ad)
        return null
    }

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        listener?.let { listeners[placement.key] = it }
        val cached = takeFreshNative(placement) ?: return false
        val frame = container as? FrameLayout ?: return false
        natives.remove(placement.key)
        val shimmerFrame = shimmer as? ShimmerFrameLayout ?: ShimmerFrameLayout(activity)
        ERainAd.getInstance().populateNativeAdView(activity, cached.ad, frame, shimmerFrame)
        // GMA counts the impression once the view tree is bound and visible
        notifyListener(placement.key) { it.onImpression() }
        return true
    }

    override fun releaseNative(placement: AdPlacement) {
        val key = placement.key
        natives.remove(key)?.let { destroyNative(it.ad) }
        nativeInFlight.remove(key)
        listeners.remove(key)
    }

    override fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener?,
    ) {
        val key = placement.key
        listener?.let { listeners[key] = it }
        // Report the buffered ad instead of staying silent: a caller waiting on onLoaded would
        // otherwise hang until its own budget expired.
        if (isInterstitialReady(placement)) {
            notifyListener(key) { it.onLoaded() }
            return
        }
        val ids = unit.loadOrder
        if (ids.isEmpty()) {
            ObLog.w(ObLog.Section.LOAD, "$key skip — no usable ad unit id")
            notifyListener(key) { it.onFailedToLoad() }
            return
        }
        ids.forEach { PlacementRegistry.register(it, key) }
        // A load is already running; its completion notifies the listener registered above.
        if (!interInFlight.add(key)) return
        ObLog.d(ObLog.Section.LOAD, "$key inter waterfall tiers=${ids.size} top=${shortId(ids.first())}")
        AdWaterfall.loadInterstitial(
            context,
            ids,
            tierTimeoutMs,
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    if (apInterstitialAd == null) {
                        failInterstitial(key, "waterfall returned no ad")
                        return
                    }
                    publishInterstitial(key, apInterstitialAd)
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    failInterstitial(key, "all ${ids.size} tier(s) failed")
                }
            },
        )
    }

    private fun publishInterstitial(key: String, ad: ApInterstitialAd) {
        ObLog.d(ObLog.Section.LOAD, "$key inter FILLED id=${shortId(ad.interstitialAd?.adUnitId)}")
        interstitials[key] = CachedAd(ad)
        interInFlight.remove(key)
        notifyListener(key) { it.onLoaded() }
    }

    private fun failInterstitial(key: String, reason: String) {
        ObLog.w(ObLog.Section.LOAD, "$key inter UNFILLED — $reason")
        interInFlight.remove(key)
        notifyListener(key) { it.onFailedToLoad() }
    }

    override fun isInterstitialReady(placement: AdPlacement): Boolean =
        takeFreshInterstitial(placement) != null

    private fun takeFreshInterstitial(placement: AdPlacement): ApInterstitialAd? {
        val cached = interstitials[placement.key] ?: return null
        if (!cached.isFresh) {
            ObLog.w(ObLog.Section.LOAD, "${placement.key} inter EXPIRED — dropping buffer")
            interstitials.remove(placement.key)
            return null
        }
        return cached.ad.takeIf { it.isReady }
    }

    /**
     * Maps the module's show callbacks onto the two moments the flow cares about.
     *
     * | module | mapped to |
     * |---|---|
     * | `onInterstitialShow` | commit marker only — tells the two `onNextAction` cases apart |
     * | `onNextAction` after commit | [ObInterstitialCallback.onNextAction] — ad is on screen |
     * | `onNextAction` without commit | terminal: the interval / click-cap short circuit |
     * | `onAdClosed` | terminal: dismissed |
     * | `onAdFailedToShow` | terminal: failed |
     *
     * `onInterstitialShow` is deliberately not the prewarm point: it lands 800 ms before the
     * module re-checks that the Activity is still RESUMED, so starting the next screen on it makes
     * that check fail and the ad is dropped. See [ERainTuning].
     */
    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        callback: ObInterstitialCallback,
    ) {
        val ad = takeFreshInterstitial(placement)
        if (ad == null) {
            callback.onAdSkipped(AdSkipReason.NOT_READY)
            return
        }
        // Full-screen ads are single use; drop the buffer before showing so it can never show twice
        interstitials.remove(placement.key)
        val committed = AtomicBoolean(false)
        ERainAd.getInstance().forceShowInterstitial(
            activity,
            ad,
            object : AdCallback() {
                override fun onInterstitialShow() {
                    committed.set(true)
                }

                override fun onNextAction() {
                    if (committed.get()) {
                        callback.onNextAction()
                    } else {
                        // No commit marker means the module short circuited before showing
                        ObLog.w(ObLog.Section.SHOW, "${placement.key} declined by the ads module")
                        callback.onAdSkipped(AdSkipReason.CAPPED_BY_ADS_MODULE)
                    }
                }

                override fun onAdClosed() {
                    callback.onAdClosed()
                }

                override fun onAdFailedToShow(adError: AdError?) {
                    ObLog.w(ObLog.Section.SHOW, "${placement.key} onAdFailedToShow: ${adError?.message}")
                    callback.onAdSkipped(AdSkipReason.FAILED_TO_SHOW)
                }

                override fun onAdClicked() {
                    notifyListener(placement.key) { it.onClicked() }
                }
            },
            false,
        )
    }

    override fun lastInterstitialShownAtMs(context: Context): Long =
        runCatching { SharePreferenceUtils.getLastImpressionInterstitialTime(context) }
            .getOrDefault(0L)

    override fun clicksToday(context: Context, adUnitId: String): Int =
        runCatching { AdmobHelper.getNumClickAdsPerDay(context, adUnitId) }.getOrDefault(0)

    override fun loadBanner(activity: Activity, unit: BannerAdUnit, listener: AdEventListener?) {
        ERainAd.getInstance().loadBanner(
            activity,
            unit.id,
            object : AdCallback() {
                override fun onAdLoaded() {
                    listener?.onLoaded()
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    listener?.onFailedToLoad()
                }

                override fun onAdClicked() {
                    listener?.onClicked()
                }
            },
        )
    }

    override fun suppressAppResume(activityClass: Class<out Activity>) {
        runCatching { AppOpenManager.getInstance().disableAppResumeWithActivity(activityClass) }
    }

    override fun releaseAll() {
        natives.values.forEach { destroyNative(it.ad) }
        natives.clear()
        nativeInFlight.clear()
        interstitials.clear()
        interInFlight.clear()
        listeners.clear()
    }

    private fun notifyListener(key: String, block: (AdEventListener) -> Unit) {
        listeners[key]?.let { runCatching { block(it) } }
    }

    /** Ad unit ids are long and share a prefix; the tail is what identifies them in a log. */
    private fun shortId(id: String?): String =
        id?.takeIf { it.isNotBlank() }?.takeLast(ID_TAIL_LENGTH) ?: "-"

    private companion object {
        const val ID_TAIL_LENGTH = 10

    }
}

/**
 * A buffered ad and when it arrived.
 *
 * GMA discards an unshown interstitial or native after about an hour, and the wrapper keeps
 * reporting itself ready afterwards — the expiry has to be tracked here.
 */
internal class CachedAd<T : Any>(
    val ad: T,
    private val loadedAtMs: Long = System.currentTimeMillis(),
) {
    val isFresh: Boolean get() = System.currentTimeMillis() - loadedAtMs < MAX_AGE_MS

    private companion object {
        /** GMA discards an unshown interstitial or native after one hour. */
        const val MAX_AGE_MS = 60 * 60 * 1_000L
    }
}
