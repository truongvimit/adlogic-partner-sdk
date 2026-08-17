package com.ads.module.helper.banner

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ads.module.R
import com.ads.module.ads.ERainAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdsHelper
import com.ads.module.tracking.AdTracking
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
 * The module's loaders own the shimmer/visibility plumbing inside `banner_container`;
 * this helper owns everything around them that apps used to hand-roll.
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

    /** Analytics key. When set, the helper reports request/skip events itself. */
    var placement: String? = null

    /** Root the module's loaders search for `banner_container`; null = the Activity window. */
    private var rootView: ViewGroup? = null

    private val listeners = CopyOnWriteArrayList<AdCallback>()
    private val resumeCount = AtomicInteger(0)

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
        // A collapsible AdView must never be reused in place. Other types keep the live view
        // until the replacement fills, so a failed reload never blanks the slot
        val oldViews: List<AdView> = if (config.bannerType is BannerType.Collapsible) {
            detachAdView()
            emptyList()
        } else {
            bannerContainer()?.let { container ->
                (0 until container.childCount).mapNotNull { container.getChildAt(it) as? AdView }
            } ?: emptyList()
        }
        setState(AdBannerState.Loading)
        // Request-time anchor; the impression callback re-stamps when it lands
        if (config.enableAutoReload) {
            nextReloadAtMs = System.currentTimeMillis() + config.autoReloadTime
        }
        placement?.let { key ->
            config.adUnitIds.forEach { AdTracking.registerPlacement(it, key) }
            AdTracking.request(key, AdFormat.BANNER, config.idAds)
        }
        loadTier(0, oldViews)
    }

    private fun loadTier(index: Int, oldViews: List<AdView>) {
        val adUnitId = config.adUnitIds.getOrNull(index) ?: return
        val retired = AtomicBoolean(false)
        val callback = object : AdCallback() {
            override fun onAdLoaded() {
                // One-shot: a later GMA auto-refresh success must not re-destroy survivors
                if (retired.compareAndSet(false, true)) {
                    oldViews.forEach { view ->
                        view.destroy()
                        (view.parent as? ViewGroup)?.removeView(view)
                    }
                }
                setState(AdBannerState.Loaded)
                // Collapsible loaders never forward onAdImpression; arm the timer here
                if (config.bannerType is BannerType.Collapsible) armAutoReload()
                listeners.forEach { it.onAdLoaded() }
            }

            override fun onAdFailedToLoad(adError: LoadAdError?) {
                // Out-of-cycle (GMA auto-refresh miss on the live AdView): keep the creative,
                // just undo the loader's container hide — never destroy or re-walk
                if (_bannerAdState.value !is AdBannerState.Loading) {
                    if (_bannerAdState.value is AdBannerState.Loaded) {
                        bannerContainer()?.visibility = View.VISIBLE
                    }
                    listeners.forEach { it.onAdFailedToLoad(adError) }
                    return
                }
                // The loader attached this tier's AdView before the request resolved; retire
                // it (but never the pre-walk survivors) or its armed listener lives on
                bannerContainer()?.let { container ->
                    (0 until container.childCount)
                        .mapNotNull { container.getChildAt(it) as? AdView }
                        .filterNot { it in oldViews }
                        .forEach {
                            it.destroy()
                            container.removeView(it)
                        }
                }
                // The loader goned the container on fail. Restore it in the SAME main-loop
                // message so a surviving banner never renders a hidden frame — no flicker
                if (oldViews.isNotEmpty()) {
                    bannerContainer()?.visibility = View.VISIBLE
                }
                // Waterfall: a lower floor gets its turn before anything is surfaced
                if (index + 1 < config.adUnitIds.size) {
                    loadTier(index + 1, oldViews)
                    return
                }
                // Terminal must leave Loading or requestAds stays gated forever; a survivor
                // still on screen is Loaded, not Fail
                setState(if (oldViews.isEmpty()) AdBannerState.Fail else AdBannerState.Loaded)
                // A no-fill must not end the interval chain — the next tick retries
                armAutoReload()
                listeners.forEach { it.onAdFailedToLoad(adError) }
            }

            override fun onAdClicked() {
                listeners.forEach { it.onAdClicked() }
            }

            override fun onAdImpression() {
                armAutoReload()
                listeners.forEach { it.onAdImpression() }
            }
        }
        val root = rootView
        val erain = ERainAd.getInstance()
        when (val type = config.bannerType) {
            is BannerType.Normal ->
                if (root == null) erain.loadBanner(activity, adUnitId, callback)
                else erain.loadBannerFragment(activity, adUnitId, root, callback)

            is BannerType.Inline ->
                if (root == null) erain.loadInlineBanner(activity, adUnitId, type.style, callback)
                else erain.loadBannerInlineFragment(activity, adUnitId, root, type.style, callback)

            is BannerType.Collapsible ->
                if (root == null) {
                    erain.loadCollapsibleBanner(activity, adUnitId, type.gravity, callback)
                } else {
                    erain.loadCollapsibleBannerFragment(
                        activity, adUnitId, root, type.gravity, callback,
                    )
                }
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
        val container = bannerContainer() ?: return
        destroyAdViews(container)
    }

    private fun reportSkip(passesUaGate: Boolean) {
        val key = placement ?: return
        val reason = AdGate.skipReason(context, config.canShowAds, passesUaGate) ?: return
        AdTracking.skipped(key, AdFormat.BANNER, reason.key)
    }

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
