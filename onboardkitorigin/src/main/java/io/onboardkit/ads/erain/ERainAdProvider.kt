package io.onboardkit.ads.erain

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.billing.AppPurchase
import com.ads.module.funtion.AdCallback
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.LoadAdError
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.OnboardingAdProvider
import io.onboardkit.config.AdTierStrategy
import io.onboardkit.config.BannerAdUnit
import io.onboardkit.config.InterstitialAdUnit
import io.trackkit.PlacementRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [OnboardingAdProvider] backed by the project's `:ads` module (ERainAd/AdMob).
 *
 * Waterfall: every placement walks its own ordered tier list, highest floor first, with the
 * per-unit [AdTierStrategy] deciding whether tiers are tried one at a time
 * ([AdTierStrategy.CASCADE]) or all at once with the highest fill winning
 * ([AdTierStrategy.PARALLEL]).
 *
 * One in-flight request per placement, and the in-flight marker is removed on BOTH success and
 * failure so a failed load can retry later instead of returning null forever.
 *
 * All race state is keyed per placement rather than held in fields, so concurrent placements
 * (the preload chain runs several) cannot read each other's tiers.
 */
class ERainAdProvider : OnboardingAdProvider {

    private val nativeBuffers = ConcurrentHashMap<String, ApNativeAd>()
    private val nativeInFlight = ConcurrentHashMap.newKeySet<String>()
    private val nativeRaces = ConcurrentHashMap<String, TierRace<ApNativeAd>>()
    private val interstitials = ConcurrentHashMap<String, ApInterstitialAd>()
    private val interInFlight = ConcurrentHashMap.newKeySet<String>()
    private val interRaces = ConcurrentHashMap<String, TierRace<ApInterstitialAd>>()

    /**
     * One observer per placement — the screen currently showing it. A list here grew one wrapper
     * per recycled fragment instance: every stale wrapper re-reported ad_show / ad_load_failed
     * (each carries its own latch) and pinned its dead Activity until releaseAll.
     */
    private val listeners = ConcurrentHashMap<String, AdEventListener>()

    override fun isPremium(context: Context): Boolean =
        runCatching { AppPurchase.getInstance().isPurchased(context) }.getOrDefault(false)

    // ── Native ──

    override fun preloadNative(activity: Activity, request: NativeAdRequest) {
        val key = request.placement.key
        if (isNativeReady(request.placement)) return
        val ids = request.unit.loadOrder
        if (ids.isEmpty()) {
            Log.w(TAG, "Native $key has no usable ad unit id — skipping")
            return
        }
        // AdMob's paid-event callback only knows the ad unit; this is the only place that knows
        // which onboarding screen asked for it, so the mapping is registered before the request.
        ids.forEach { PlacementRegistry.register(it, key) }
        if (!nativeInFlight.add(key)) return
        when (request.unit.strategy) {
            AdTierStrategy.CASCADE -> loadNativeCascade(activity, request, ids, 0)
            AdTierStrategy.PARALLEL -> loadNativeParallel(activity, request, ids)
        }
    }

    private fun loadNativeCascade(
        activity: Activity,
        request: NativeAdRequest,
        ids: List<String>,
        index: Int,
    ) {
        val key = request.placement.key
        if (index >= ids.size) {
            failNative(key, "cascade exhausted ${ids.size} tier(s)")
            return
        }
        ERainAd.getInstance().loadNativeAdResultCallback(
            activity,
            ids[index],
            request.layoutRes,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    publishNative(key, nativeAd)
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    Log.w(TAG, "Native $key tier $index failed: ${error?.message}")
                    loadNativeCascade(activity, request, ids, index + 1)
                }

                override fun onAdClicked() {
                    notifyListeners(key) { it.onClicked() }
                }
            },
        )
    }

    private fun loadNativeParallel(
        activity: Activity,
        request: NativeAdRequest,
        ids: List<String>,
    ) {
        val key = request.placement.key
        val race = TierRace<ApNativeAd>(ids.size)
        nativeRaces[key] = race
        ids.forEachIndexed { index, adUnitId ->
            ERainAd.getInstance().loadNativeAdResultCallback(
                activity,
                adUnitId,
                request.layoutRes,
                object : AdCallback() {
                    override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                        when (val outcome = race.onFill(index, nativeAd)) {
                            is RaceOutcome.Won -> {
                                race.drainLosers().forEach(::destroyNative)
                                publishNative(key, outcome.value)
                            }
                            // Buffered: a higher tier is still deciding and may still beat it
                            RaceOutcome.Pending -> Unit
                            // Every tier already settled — this fill has nowhere to go
                            RaceOutcome.Late, RaceOutcome.Lost -> destroyNative(nativeAd)
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError?) {
                        Log.w(TAG, "Native $key tier $index failed: ${error?.message}")
                        when (val outcome = race.onFail(index)) {
                            is RaceOutcome.Won -> {
                                race.drainLosers().forEach(::destroyNative)
                                publishNative(key, outcome.value)
                            }
                            RaceOutcome.Lost -> failNative(key, "all ${ids.size} tier(s) failed")
                            RaceOutcome.Pending, RaceOutcome.Late -> Unit
                        }
                    }

                    override fun onAdClicked() {
                        notifyListeners(key) { it.onClicked() }
                    }
                },
            )
        }
    }

    private fun publishNative(key: String, ad: ApNativeAd) {
        nativeBuffers.put(key, ad)?.let(::destroyNative)
        nativeRaces.remove(key)
        nativeInFlight.remove(key)
        notifyListeners(key) { it.onLoaded() }
    }

    private fun failNative(key: String, reason: String) {
        nativeRaces.remove(key)
        nativeInFlight.remove(key)
        Log.w(TAG, "Native $key unfilled: $reason")
        notifyListeners(key) { it.onFailedToLoad() }
    }

    private fun destroyNative(ad: ApNativeAd) {
        runCatching { ad.admobNativeAd?.destroy() }
    }

    override fun isNativeReady(placement: AdPlacement): Boolean =
        nativeBuffers[placement.key] != null

    override fun isNativeLoading(placement: AdPlacement): Boolean =
        placement.key in nativeInFlight

    override fun bindNative(
        activity: Activity,
        placement: AdPlacement,
        container: ViewGroup,
        shimmer: View?,
        listener: AdEventListener?,
    ): Boolean {
        listener?.let { addListener(placement.key, it) }
        val ad = nativeBuffers.remove(placement.key) ?: return false
        val frame = container as? FrameLayout ?: return false
        val shimmerFrame = shimmer as? ShimmerFrameLayout
        if (shimmerFrame != null) {
            ERainAd.getInstance().populateNativeAdView(activity, ad, frame, shimmerFrame)
        } else {
            val holder = ShimmerFrameLayout(activity)
            ERainAd.getInstance().populateNativeAdView(activity, ad, frame, holder)
        }
        // GMA counts the impression once the view tree is bound and visible
        notifyListeners(placement.key) { it.onImpression() }
        return true
    }

    override fun releaseNative(placement: AdPlacement) {
        val key = placement.key
        nativeBuffers.remove(key)?.let(::destroyNative)
        // A parallel race can still be holding buffered losers for this placement
        nativeRaces.remove(key)?.drainAll()?.forEach(::destroyNative)
        // Draining marks the race decided, so its callbacks resolve to Late and neither
        // publishNative nor failNative ever runs — without this line the in-flight marker stayed
        // set forever and every future preload for the placement was silently skipped.
        nativeInFlight.remove(key)
        listeners.remove(key)
    }

    // ── Interstitial ──

    override fun loadInterstitial(
        context: Context,
        placement: AdPlacement,
        unit: InterstitialAdUnit,
        listener: AdEventListener?,
    ) {
        val key = placement.key
        listener?.let { addListener(key, it) }
        if (isInterstitialReady(placement)) return
        val ids = unit.loadOrder
        if (ids.isEmpty()) {
            Log.w(TAG, "Interstitial $key has no usable ad unit id — skipping")
            notifyListeners(key) { it.onFailedToLoad() }
            return
        }
        ids.forEach { PlacementRegistry.register(it, key) }
        if (!interInFlight.add(key)) return
        when (unit.strategy) {
            AdTierStrategy.CASCADE -> loadInterCascade(context, key, ids, 0)
            AdTierStrategy.PARALLEL -> loadInterParallel(context, key, ids)
        }
    }

    private fun loadInterCascade(context: Context, key: String, ids: List<String>, index: Int) {
        if (index >= ids.size) {
            failInterstitial(key, "cascade exhausted ${ids.size} tier(s)")
            return
        }
        val ad = ERainAd.getInstance().getInterstitialAds(
            context,
            ids[index],
            object : AdCallback() {
                override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                    if (apInterstitialAd != null) publishInterstitial(key, apInterstitialAd)
                }

                override fun onAdFailedToLoad(error: LoadAdError?) {
                    Log.w(TAG, "Interstitial $key tier $index failed: ${error?.message}")
                    loadInterCascade(context, key, ids, index + 1)
                }
            },
        )
        // Safe in cascade: only one tier is ever outstanding, so this cannot clobber a
        // higher-floor wrapper. Deliberately not done in parallel mode for that reason.
        if (ad != null) interstitials[key] = ad
    }

    private fun loadInterParallel(context: Context, key: String, ids: List<String>) {
        val race = TierRace<ApInterstitialAd>(ids.size)
        interRaces[key] = race
        ids.forEachIndexed { index, adUnitId ->
            ERainAd.getInstance().getInterstitialAds(
                context,
                adUnitId,
                object : AdCallback() {
                    override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
                        // The ads SDK reports a phantom null fill for purchased/capped users;
                        // treat it as this tier failing rather than as a win
                        if (apInterstitialAd == null) {
                            settleInterFail(key, race, index, ids.size, "null wrapper")
                            return
                        }
                        when (val outcome = race.onFill(index, apInterstitialAd)) {
                            is RaceOutcome.Won -> publishInterstitial(key, outcome.value)
                            RaceOutcome.Pending, RaceOutcome.Late, RaceOutcome.Lost -> Unit
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError?) {
                        settleInterFail(key, race, index, ids.size, error?.message.orEmpty())
                    }
                },
            )
        }
    }

    private fun settleInterFail(
        key: String,
        race: TierRace<ApInterstitialAd>,
        index: Int,
        tierCount: Int,
        reason: String,
    ) {
        Log.w(TAG, "Interstitial $key tier $index failed: $reason")
        when (val outcome = race.onFail(index)) {
            is RaceOutcome.Won -> publishInterstitial(key, outcome.value)
            RaceOutcome.Lost -> failInterstitial(key, "all $tierCount tier(s) failed")
            RaceOutcome.Pending, RaceOutcome.Late -> Unit
        }
    }

    private fun publishInterstitial(key: String, ad: ApInterstitialAd) {
        interstitials[key] = ad
        interRaces.remove(key)
        interInFlight.remove(key)
        notifyListeners(key) { it.onLoaded() }
    }

    private fun failInterstitial(key: String, reason: String) {
        interRaces.remove(key)
        interInFlight.remove(key)
        Log.w(TAG, "Interstitial $key unfilled: $reason")
        notifyListeners(key) { it.onFailedToLoad() }
    }

    override fun isInterstitialReady(placement: AdPlacement): Boolean =
        interstitials[placement.key]?.isReady == true

    override fun showInterstitial(
        activity: Activity,
        placement: AdPlacement,
        onNextAction: () -> Unit,
    ) {
        val ad = interstitials[placement.key]
        if (ad == null || !ad.isReady) {
            onNextAction()
            return
        }
        interstitials.remove(placement.key)
        ERainAd.getInstance().forceShowInterstitial(
            activity,
            ad,
            object : AdCallback() {
                override fun onNextAction() {
                    onNextAction()
                }
            },
            false,
        )
    }

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
            },
        )
    }

    override fun suppressAppResume(activityClass: Class<out Activity>) {
        runCatching { AppOpenManager.getInstance().disableAppResumeWithActivity(activityClass) }
    }

    override fun allowAppResume(activityClass: Class<out Activity>) {
        runCatching { AppOpenManager.getInstance().enableAppResumeWithActivity(activityClass) }
    }

    override fun releaseAll() {
        nativeBuffers.values.forEach(::destroyNative)
        nativeBuffers.clear()
        nativeRaces.values.forEach { race -> race.drainAll().forEach(::destroyNative) }
        nativeRaces.clear()
        nativeInFlight.clear()
        interstitials.clear()
        interRaces.clear()
        interInFlight.clear()
        listeners.clear()
    }

    private fun addListener(key: String, listener: AdEventListener) {
        listeners[key] = listener
    }

    private fun notifyListeners(key: String, block: (AdEventListener) -> Unit) {
        listeners[key]?.let { runCatching { block(it) } }
    }

    private companion object {
        const val TAG = "OnboardKit.ERainAds"
    }
}

/** Result of feeding one tier's outcome into a [TierRace]. */
internal sealed interface RaceOutcome<out T> {
    /** Higher tiers are still undecided; a fill (if any) stays buffered. */
    data object Pending : RaceOutcome<Nothing>

    /** [value] is the highest floor that filled — deliver it. */
    data class Won<T>(val value: T) : RaceOutcome<T>

    /** Every tier failed. */
    data object Lost : RaceOutcome<Nothing>

    /** The race was already decided; the caller owns (and must discard) whatever it passed in. */
    data object Late : RaceOutcome<Nothing>
}

/**
 * Priority resolution for a parallel tier load.
 *
 * A tier only wins once every higher-priority tier has failed, so the winner is always the
 * highest floor that actually filled — never simply whichever callback landed first, which is
 * the mistake the `:ads` module's 4-tier helpers are prone to. Callbacks arrive on the main
 * thread today, but the ads SDK makes no such guarantee across tiers, hence the locking.
 */
internal class TierRace<T : Any>(private val size: Int) {

    private val lock = Any()
    private val fills = HashMap<Int, T>()
    private val failed = HashSet<Int>()
    private var decided = false

    fun onFill(index: Int, value: T): RaceOutcome<T> = synchronized(lock) {
        if (decided) return RaceOutcome.Late
        fills[index] = value
        decide()
    }

    fun onFail(index: Int): RaceOutcome<T> = synchronized(lock) {
        if (decided) return RaceOutcome.Late
        failed += index
        decide()
    }

    /** Buffered fills that lost the race, cleared so they are released exactly once. */
    fun drainLosers(): List<T> = drainAll()

    /** Everything still buffered — used when a placement is released mid-race. */
    fun drainAll(): List<T> = synchronized(lock) {
        decided = true
        val remaining = fills.values.toList()
        fills.clear()
        remaining
    }

    private fun decide(): RaceOutcome<T> {
        for (index in 0 until size) {
            val fill = fills[index]
            if (fill != null) {
                decided = true
                fills.remove(index)
                return RaceOutcome.Won(fill)
            }
            // This tier has not reported yet, so a higher floor may still win
            if (index !in failed) return RaceOutcome.Pending
        }
        decided = true
        return RaceOutcome.Lost
    }
}
