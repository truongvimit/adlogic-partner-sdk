package com.ads.module.helper.banner

import android.app.Activity
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ads.module.R
import com.ads.module.ads.ERainAd
import com.ads.module.consent.ConsentCenter
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdsHelper
import com.ads.module.tracking.AdTracking
import com.ads.module.tracking.AdLoadAttempt
import com.ads.module.tracking.AdLoadContext
import com.ads.module.tracking.TrackingAdCallback
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import io.trackkit.AdFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives one banner placement: load dispatch per [BannerType], lifecycle-aware reload
 * (resume debounce + optional timer), proper teardown of the previous [AdView], and
 * skip/request telemetry.
 *
 * Loaders create views inside `banner_container`. This helper restores container visibility,
 * removes unauthorized or stale fills, and preserves an authorized displayed banner when a
 * replacement cannot load offline. Each tier rechecks dispatch authority; live refresh callbacks
 * remain attached to their accepted view. Cancellation invalidates pending view ownership.
 *
 * ```
 * val helper = BannerAdHelper(activity, this, BannerAdConfig(id, true, false))
 *     .attachInto(binding.frAds)
 * helper.placement = "banner_home"
 * helper.requestAds(BannerAdParam.Request)
 * ```
 */
class BannerAdHelper(
    private val activity: Activity,
    lifecycleOwner: LifecycleOwner,
    config: BannerAdConfig,
) : AdsHelper<BannerAdConfig, BannerAdParam>(activity, lifecycleOwner, config) {

    private val _bannerAdState = MutableStateFlow<AdBannerState>(
        if (canRequestAds()) AdBannerState.None else AdBannerState.Fail,
    )
    val bannerAdState: StateFlow<AdBannerState> = _bannerAdState.asStateFlow()

    /** Analytics key captured per load; null captures the first unit's registered placement. */
    var placement: String? = null

    /** Root the module's loaders search for `banner_container`; null = the Activity window. */
    private var rootView: ViewGroup? = null

    private val listeners = CopyOnWriteArrayList<AdCallback>()
    private val resumeCount = AtomicInteger(0)
    private var currentLoad: BannerLoad? = null
    private val displayedViews = mutableSetOf<AdView>()

    private class BannerLoad(val personalized: Boolean, val context: AdLoadContext) {
        val rejected = AtomicBoolean(false)
        val attempt = AdLoadAttempt(context)
        var pendingTier: BannerTier? = null

        fun fail(errorCode: Int? = null) {
            pendingTier?.finish("load_failed", errorCode)
            attempt.onFailed(errorCode)
        }
    }

    private class BannerTier(
        private val attempt: AdLoadAttempt,
        private val index: Int,
        private val unitId: String,
    ) {
        private var startedAt: Long? = null
        private val finished = AtomicBoolean(false)

        fun start() {
            if (finished.get() || startedAt != null) return
            startedAt = SystemClock.elapsedRealtime()
            attempt.onRequestStarted(unitId)
        }

        fun finish(outcome: String, errorCode: Int? = null) {
            if (!finished.compareAndSet(false, true)) return
            val start = startedAt ?: return
            attempt.onTierResult(index + 1, unitId, outcome, errorCode, SystemClock.elapsedRealtime() - start)
        }
    }

    // When the next interval reload is due; ON_STOP kills the timer, this survives it
    private var nextReloadAtMs = 0L

    private val resumeReloadRunnable = Runnable {
        // enableAutoReload placements may also recover from a Cancel (e.g. offline) here
        val active = isActiveState() || config.enableAutoReload
        if (resumeCount.get() > 1 && canRequestAds() && canReloadAd() && active) {
            val now = System.currentTimeMillis()
            if (!config.enableAutoReload || now >= nextReloadAtMs) {
                requestAds(BannerAdParam.Reload)
            } else {
                // Interval not elapsed: resume the paused timer for the remaining time
                mainHandler.removeCallbacks(autoReloadRunnable)
                mainHandler.postDelayed(autoReloadRunnable, nextReloadAtMs - now)
            }
        }
    }

    private val autoReloadRunnable = Runnable {
        if (isResumed() && canReloadAd() && _bannerAdState.value !is AdBannerState.Loading) {
            requestAds(BannerAdParam.Reload)
        }
    }

    init {
        bindLifecycle()
    }

    /** Points the helper at a view that already contains the module's banner layout ids. */
    fun setBannerContentView(root: ViewGroup): BannerAdHelper {
        rootView = root
        return this
    }

    /**
     * Resets [host] to the module's own placeholder (`layout_banner_control`) and uses it
     * as the banner root — the supported way to give a screen a banner slot.
     */
    fun attachInto(host: FrameLayout): BannerAdHelper {
        resetPlaceholder(activity, host)
        rootView = host
        return this
    }

    fun registerAdListener(adCallback: AdCallback) {
        listeners.addIfAbsent(adCallback)
    }

    fun unregisterAdListener(adCallback: AdCallback) {
        listeners.remove(adCallback)
    }

    override fun requestAds(param: BannerAdParam) {
        if (_bannerAdState.value is AdBannerState.Loading) return
        val passesUaGate = AdGate.passesUaGate(config.forceUaCheck)
        if (!(config.canShowAds && passesUaGate && canRequestAds())) {
            reportSkip(passesUaGate)
            val offline = !AdGate.isNetworkAvailable(context)
            if (offline && _bannerAdState.value !is AdBannerState.Loaded) {
                cancel()
            } else {
                // The placeholder shimmer auto-starts; a skip must stop it or it runs forever
                hideShimmer()
                setState(AdBannerState.Fail)
            }
            // A transient block (offline, momentary gate) must not end the interval chain
            armAutoReload()
            return
        }
        if (param is BannerAdParam.Reload && !canReloadAd()) return
        load()
    }

    override fun cancel() {
        currentLoad?.fail()
        currentLoad = null
        flagActive.compareAndSet(true, false)
        mainHandler.removeCallbacks(autoReloadRunnable)
        detachAdView()
        setState(AdBannerState.Cancel)
        bannerContainer()?.visibility = View.GONE
        hideShimmer()
    }

    override fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                if (!canShowAds() && isActiveState()) {
                    cancel()
                    return
                }
                resumeCount.incrementAndGet()
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.postDelayed(resumeReloadRunnable, config.timeDebounceResume)
            }

            Lifecycle.Event.ON_STOP -> mainHandler.removeCallbacks(autoReloadRunnable)

            Lifecycle.Event.ON_DESTROY -> {
                currentLoad?.fail()
                currentLoad = null
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.removeCallbacks(autoReloadRunnable)
                detachAdView()
                listeners.clear()
            }

            else -> Unit
        }
    }

    private fun load() {
        if (config.adUnitIds.isEmpty()) {
            // Failing fast beats a Loading state nothing can ever resolve
            hideShimmer()
            setState(AdBannerState.Fail)
            listeners.forEach { it.onAdFailedToLoad(null) }
            return
        }
        flagActive.set(true)
        // Every type keeps its live view until the replacement fills — collapsible included,
        // which is retired at populate time, never at request time. Tearing it down here is
        // what made a reload read as ad → shimmer → ad, and a failed reload blank the slot
        val oldViews: List<AdView> = bannerContainer()?.let { container ->
            (0 until container.childCount).mapNotNull { container.getChildAt(it) as? AdView }
        } ?: emptyList()
        setState(AdBannerState.Loading)
        // Request-time anchor; the impression callback re-stamps when it lands
        if (config.enableAutoReload) {
            nextReloadAtMs = System.currentTimeMillis() + config.autoReloadTime
        }
        placement?.let { key ->
            config.adUnitIds.forEach { AdTracking.registerPlacement(it, key) }
        }
        val loadContext = placement?.let { AdLoadContext(it, bannerFormat()) }
            ?: AdLoadContext.forAdUnit(config.idAds, bannerFormat())
        val load = BannerLoad(ConsentCenter.canPersonalize(), loadContext)
        currentLoad = load
        loadTier(0, oldViews, load)
    }

    private fun loadTier(index: Int, oldViews: List<AdView>, load: BannerLoad) {
        if (currentLoad !== load || load.rejected.get()) return
        val adUnitId = config.adUnitIds.getOrNull(index) ?: return
        val passesUaGate = AdGate.passesUaGate(config.forceUaCheck)
        if (AdGate.skipReason(context, config.canShowAds, passesUaGate) != null) {
            rejectLoad(load)
            return
        }
        val tier = BannerTier(load.attempt, index, adUnitId)
        load.pendingTier = tier
        val initialFillAccepted = AtomicBoolean(false)
        val tierFailed = AtomicBoolean(false)
        val precedingViews = bannerViews()
        var ownedViews: List<AdView>? = null
        fun tierViews(): List<AdView> = ownedViews ?: bannerViews()
            .filterNot { it in precedingViews }
            .also { ownedViews = it }

        fun discardTier() {
            removeViews(tierViews())
            restoreBannerVisibility()
        }

        val callback = TrackingAdCallback.presentationOnly(load.context, adUnitId, object : AdCallback() {
            override fun onAdRequestStarted(adUnitId: String) {
                if (currentLoad === load && !load.rejected.get()) tier.start()
            }

            override fun onAdLoaded() {
                // A rejected/failed tier must not claim a later load, even when GMA delivers
                // a callback after teardown. The loader toggled visibility before calling us;
                // restore it synchronously so an obsolete view never reaches the next frame.
                if (tierFailed.get() || load.rejected.get() ||
                    (!initialFillAccepted.get() && currentLoad !== load) ||
                    (initialFillAccepted.get() && tierViews().none { it in displayedViews })
                ) {
                    discardTier()
                    return
                }
                if (!canRetain(load)) {
                    tier.finish("loaded")
                    if (currentLoad === load) rejectLoad(load) else discardTier()
                    return
                }
                // One-shot: a later GMA auto-refresh success must not re-destroy survivors
                if (initialFillAccepted.compareAndSet(false, true)) {
                    tier.finish("loaded")
                    load.attempt.onLoaded(adUnitId)
                    removeViews(oldViews)
                    displayedViews.addAll(tierViews())
                }
                // A survivor can auto-refresh while its replacement is loading.
                if (currentLoad === load) setState(AdBannerState.Loaded)
                restoreBannerVisibility()
                // Keep the loaded fallback anchor; a real impression can re-anchor the timer.
                if (config.bannerType is BannerType.Collapsible) armAutoReload()
                listeners.forEach { it.onAdLoaded() }
            }

            override fun onAdFailedToLoad(adError: LoadAdError?) {
                if (load.rejected.get() ||
                    (!initialFillAccepted.get() && currentLoad !== load) ||
                    (initialFillAccepted.get() && tierViews().none { it in displayedViews })
                ) {
                    discardTier()
                    return
                }
                if (!canRetain(load)) {
                    tier.finish("load_failed", adError?.code)
                    if (currentLoad === load) rejectLoad(load, adError?.code) else discardTier()
                    return
                }
                // Out-of-cycle (GMA auto-refresh miss on the live AdView): keep the creative,
                // just undo the loader's container hide — never destroy or re-walk
                if (initialFillAccepted.get()) {
                    restoreBannerVisibility()
                    listeners.forEach { it.onAdFailedToLoad(adError) }
                    return
                }
                if (!tierFailed.compareAndSet(false, true)) {
                    restoreBannerVisibility()
                    return
                }
                tier.finish("load_failed", adError?.code)
                // The loader attached this tier's AdView before the request resolved; retire
                // it (but never the pre-walk survivors) or its armed listener lives on
                discardTier()
                // The loader goned the container on fail. Restore it in the SAME main-loop
                // message so a surviving banner never renders a hidden frame — no flicker
                restoreBannerVisibility()
                // Waterfall: a lower floor gets its turn before anything is surfaced
                if (index + 1 < config.adUnitIds.size) {
                    loadTier(index + 1, oldViews, load)
                    return
                }
                // Terminal must leave Loading or requestAds stays gated forever; a survivor
                // still on screen is Loaded, not Fail
                load.attempt.onFailed(adError?.code)
                setState(if (oldViews.isEmpty()) AdBannerState.Fail else AdBannerState.Loaded)
                // A no-fill must not end the interval chain — the next tick retries
                armAutoReload()
                listeners.forEach { it.onAdFailedToLoad(adError) }
            }

            override fun onAdClicked() {
                if (canRetain(load) && tierViews().any { it in displayedViews }) {
                    listeners.forEach { it.onAdClicked() }
                }
            }

            override fun onAdImpression() {
                if (canRetain(load) && tierViews().any { it in displayedViews }) {
                    armAutoReload()
                    listeners.forEach { it.onAdImpression() }
                }
            }
        })
        val root = rootView
        val erain = ERainAd.getInstance()
        when (val type = config.bannerType) {
            is BannerType.Normal ->
                if (root == null) erain.loadBanner(activity, adUnitId, callback)
                else erain.loadBannerFragment(activity, adUnitId, root, callback)

            is BannerType.LargeAnchored ->
                if (root == null) erain.loadLargeAnchoredBanner(activity, adUnitId, callback)
                else erain.loadLargeAnchoredBannerFragment(activity, adUnitId, root, callback)

            is BannerType.Inline ->
                if (root == null) erain.loadInlineBanner(activity, adUnitId, type.style, callback)
                else erain.loadBannerInlineFragment(activity, adUnitId, root, type.style, callback)

            is BannerType.InlineMaxHeight ->
                if (root == null) {
                    erain.loadInlineBanner(activity, adUnitId, type.maxHeightDp, callback)
                } else {
                    erain.loadBannerInlineFragment(
                        activity, adUnitId, root, type.maxHeightDp, callback,
                    )
                }

            is BannerType.Fixed ->
                if (root == null) {
                    erain.loadFixedSizeBanner(activity, adUnitId, type.size.adSize, callback)
                } else {
                    erain.loadFixedSizeBannerFragment(
                        activity, adUnitId, root, type.size.adSize, callback,
                    )
                }

            is BannerType.Collapsible ->
                if (root == null) {
                    erain.loadCollapsibleBanner(activity, adUnitId, type.gravity, callback)
                } else {
                    erain.loadCollapsibleBannerFragment(
                        activity, adUnitId, root, type.gravity, callback,
                    )
                }
        }
        // Capture just this vendor view before another tier or reload can attach its own.
        tierViews()
        // The loaders raise the shimmer on every request, and it is drawn over the banner.
        // With an ad still on screen that reads as ad → shimmer → ad, so undo it in the same
        // main-loop message: the live banner stays until the new one renders over it
        if (oldViews.isNotEmpty()) hideShimmer()
    }

    private fun canRetain(load: BannerLoad): Boolean =
        AdGate.skipReason(context, config.canShowAds,
            AdGate.passesUaGate(config.forceUaCheck), checkNetwork = false) == null &&
            ConsentCenter.canPersonalize() == load.personalized

    private fun rejectLoad(load: BannerLoad, errorCode: Int? = null) {
        if (currentLoad !== load || !load.rejected.compareAndSet(false, true)) return
        load.fail(errorCode)
        reportSkip(AdGate.passesUaGate(config.forceUaCheck))
        // Offline prevents a replacement request; it does not invalidate an authorized
        // creative already displayed. Lost authority/premium/personalization does.
        if (canRetain(load)) removeViews(bannerViews().filterNot { it in displayedViews })
        else detachAdView()
        restoreBannerVisibility()
        hideShimmer()
        setState(if (displayedViews.isEmpty()) AdBannerState.Fail else AdBannerState.Loaded)
        armAutoReload()
        listeners.forEach { it.onAdFailedToLoad(null) }
    }

    private fun bannerViews(): List<AdView> = bannerContainer()?.let { container ->
        (0 until container.childCount).mapNotNull { container.getChildAt(it) as? AdView }
    } ?: emptyList()

    private fun removeViews(views: List<AdView>) {
        views.forEach { view ->
            (view.parent as? ViewGroup)?.let { parent ->
                view.destroy()
                parent.removeView(view)
            }
            displayedViews.remove(view)
        }
    }

    private fun restoreBannerVisibility() {
        bannerContainer()?.let { container ->
            container.visibility = if (displayedViews.any { it.parent === container }) {
                View.VISIBLE
            } else View.GONE
        }
    }

    private fun armAutoReload() {
        if (!config.enableAutoReload || !canReloadAd()) return
        nextReloadAtMs = System.currentTimeMillis() + config.autoReloadTime
        mainHandler.removeCallbacks(autoReloadRunnable)
        mainHandler.postDelayed(autoReloadRunnable, config.autoReloadTime)
    }

    private fun setState(state: AdBannerState) {
        _bannerAdState.value = state
    }

    private fun bannerContainer(): FrameLayout? {
        val root = rootView
        return if (root != null) root.findViewById(R.id.banner_container)
        else activity.findViewById(R.id.banner_container)
    }

    private fun shimmerContainer(): ShimmerFrameLayout? {
        val root = rootView
        return if (root != null) root.findViewById(R.id.shimmer_container_banner)
        else activity.findViewById(R.id.shimmer_container_banner)
    }

    private fun hideShimmer() {
        shimmerContainer()?.let {
            it.stopShimmer()
            it.visibility = View.GONE
        }
    }

    private fun detachAdView() {
        displayedViews.clear()
        val container = bannerContainer() ?: return
        destroyAdViews(container)
    }

    private fun reportSkip(passesUaGate: Boolean) {
        val key = placement ?: return
        val reason = AdGate.skipReason(context, config.canShowAds, passesUaGate) ?: return
        AdTracking.skipped(key, bannerFormat(), reason.key)
    }

    private fun bannerFormat(): AdFormat =
        if (config.bannerType is BannerType.Collapsible) AdFormat.COLLAPSIBLE_BANNER else AdFormat.BANNER

    companion object {

        /**
         * Destroys any [AdView] under [host]'s `banner_container` and resets [host] to the
         * module's shimmer placeholder — the teardown apps used to reimplement by reaching
         * into the module's resources.
         */
        @JvmStatic
        fun resetPlaceholder(activity: Activity, host: FrameLayout) {
            try {
                host.findViewById<FrameLayout>(R.id.banner_container)?.let { destroyAdViews(it) }
                val placeholder =
                    LayoutInflater.from(activity).inflate(R.layout.layout_banner_control, null)
                host.removeAllViews()
                host.addView(placeholder)
            } catch (_: Exception) {
            }
        }

        private fun destroyAdViews(container: ViewGroup) {
            val victims = (0 until container.childCount)
                .mapNotNull { container.getChildAt(it) as? AdView }
            victims.forEach {
                it.destroy()
                container.removeView(it)
            }
        }
    }
}
