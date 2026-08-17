package com.ads.module.helper.adnative

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ads.module.ads.AdWaterfall
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdOptionVisibility
import com.ads.module.helper.AdsHelper
import com.ads.module.tracking.AdTracking
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.LoadAdError
import io.trackkit.AdFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives one native placement end to end: waterfall load, preload consumption, view
 * binding, shimmer toggling, reload-on-resume, and skip/request telemetry.
 *
 * The app hands over views once and calls [requestAds]; everything after that — including
 * hiding the slot for purchased users and keeping the old ad on a failed reload — is owned
 * here.
 *
 * ```
 * val helper = NativeAdHelper(
 *     activity, this, NativeAdConfig(ids, true, true, R.layout.native_home))
 *     .setNativeContentView(binding.frAds)
 *     .setShimmerLayoutView(binding.shimmer)
 * helper.placement = "native_home"
 * helper.requestAds(NativeAdParam.Request)
 * ```
 */
class NativeAdHelper(
    private val activity: Activity,
    lifecycleOwner: LifecycleOwner,
    config: NativeAdConfig,
) : AdsHelper<NativeAdConfig, NativeAdParam>(activity, lifecycleOwner, config) {

    /** Swaps the default `populateNativeAdView` for the app's own styling/binding. */
    fun interface NativeAdBinder {
        fun bind(
            activity: Activity,
            nativeAd: ApNativeAd,
            container: FrameLayout,
            shimmer: ShimmerFrameLayout?,
        )
    }

    private val _nativeAdState = MutableStateFlow<AdNativeState>(AdNativeState.None)
    val nativeAdState: StateFlow<AdNativeState> = _nativeAdState.asStateFlow()

    var nativeAd: ApNativeAd? = null
        private set

    /** Analytics key. When set, the helper reports request/skip events itself. */
    var placement: String? = null

    var adVisibility: AdOptionVisibility = AdOptionVisibility.GONE

    /** Minimum gap after the last bind before a reload may fire. */
    var maxValueDebounceAdLoaded: Long = DEFAULT_DEBOUNCE_AD_LOADED_MS
        set(value) {
            require(value > 0) { "maxValueDebounceAdLoaded must be > 0" }
            field = value
        }

    var isEnablePreload: Boolean = false
        private set

    var preloadKey: String = NativeAdPreload.getInstance().keyOf(config)
        private set

    var preloadClientOption: NativeAdPreloadClientOption = NativeAdPreloadClientOption()
        private set

    private var contentView: FrameLayout? = null
    private var shimmerView: ShimmerFrameLayout? = null
    private var binder = NativeAdBinder { act, ad, container, shimmer ->
        ERainAd.getInstance()
            .populateNativeAdView(act, ad, container, shimmer ?: ShimmerFrameLayout(act))
    }

    private val listeners = CopyOnWriteArrayList<AdCallback>()
    private val resumeCount = AtomicInteger(0)
    private var timeShowAdRecent = 0L
    private var reloadByTimeMs = 0L

    private val resumeReloadRunnable = Runnable {
        if (resumeCount.get() > 1 && canRequestAds() && canReloadAd() && isActiveState()) {
            requestAds(NativeAdParam.Reload)
        }
    }

    // Impressions of preload-consumed ads surface through the executor, not the waterfall
    private val preloadObserver = object : AdCallback() {
        override fun onAdImpression() {
            if (isActiveState() && nativeAd != null) {
                onAdImpressionInternal()
                listeners.forEach { it.onAdImpression() }
            }
        }
    }

    private val reloadByTimeRunnable = Runnable {
        if (conditionReloadAdAvailable() && isResumed()) requestAds(NativeAdParam.Reload)
    }

    init {
        bindLifecycle()
    }

    fun setNativeContentView(container: FrameLayout): NativeAdHelper {
        contentView = container
        applyDefaultVisibility()
        // A view attached after the fill would otherwise stay blank until the next reload
        (nativeAdState.value as? AdNativeState.Loaded)?.let { bindLoadedAd(it.nativeAd) }
        return this
    }

    fun setShimmerLayoutView(shimmer: ShimmerFrameLayout): NativeAdHelper {
        shimmerView = shimmer
        applyDefaultVisibility()
        return this
    }

    fun setNativeAdBinder(nativeAdBinder: NativeAdBinder): NativeAdHelper {
        binder = nativeAdBinder
        return this
    }

    @JvmOverloads
    fun setEnablePreload(isEnable: Boolean, key: String = preloadKey): NativeAdHelper {
        val preload = NativeAdPreload.getInstance()
        preload.unregisterAdCallback(preloadKey, preloadObserver)
        isEnablePreload = isEnable
        preloadKey = key
        if (isEnable) preload.registerAdCallback(key, preloadObserver)
        return this
    }

    fun setPreloadAdOption(option: NativeAdPreloadClientOption): NativeAdHelper {
        preloadClientOption = option
        return this
    }

    /** Re-loads the ad [durationInMillis] after each bind, while the screen is resumed. */
    @JvmOverloads
    fun applyReloadByTime(durationInMillis: Long = DEFAULT_RELOAD_BY_TIME_MS): NativeAdHelper {
        require(durationInMillis > 0) { "durationInMillis must be > 0" }
        reloadByTimeMs = durationInMillis
        return this
    }

    fun registerAdListener(adCallback: AdCallback) {
        listeners.addIfAbsent(adCallback)
    }

    fun unregisterAdListener(adCallback: AdCallback) {
        listeners.remove(adCallback)
    }

    fun unregisterAllAdListener() {
        listeners.clear()
    }

    override fun requestAds(param: NativeAdParam) {
        if (_nativeAdState.value is AdNativeState.Loading) return
        val passesUaGate = AdGate.passesUaGate(config.forceUaCheck)
        val preloadHit = param is NativeAdParam.Request && isEnablePreload &&
            NativeAdPreload.getInstance().isPreloadAvailable(preloadKey)
        // canShowAds() (not the raw config flag): Ready/preload waive the network
        // requirement only — a purchased user must never get a buffered ad bound
        val accepted = canShowAds() && config.adUnitIds.isNotEmpty() && passesUaGate &&
            (canRequestAds() || param is NativeAdParam.Ready || preloadHit)
        if (!accepted) {
            reportSkip(passesUaGate)
            val offline = !AdGate.isNetworkAvailable(context)
            if (offline) listeners.forEach { it.onAdFailedToLoad(null) }
            // Offline with nothing on screen is a dead slot; otherwise keep what is shown
            if (offline && nativeAd == null) cancel()
            else setState(AdNativeState.Fail)
            return
        }
        when (param) {
            is NativeAdParam.Request -> {
                flagActive.set(true)
                createOrConsumePreload()
            }

            is NativeAdParam.Reload -> {
                if (!conditionReloadAdAvailable()) return
                if (isEnablePreload && preloadClientOption.preloadOnResume) {
                    createOrConsumePreload()
                } else {
                    loadFromNetwork()
                }
            }

            is NativeAdParam.Ready -> {
                flagActive.set(true)
                onLoadedAd(param.nativeAd)
            }
        }
    }

    override fun cancel() {
        flagActive.compareAndSet(true, false)
        mainHandler.removeCallbacks(reloadByTimeRunnable)
        setState(AdNativeState.Cancel)
    }

    /** Reload is allowed only after the last bind has been visible for a minimum time. */
    fun conditionReloadAdAvailable(): Boolean =
        _nativeAdState.value !is AdNativeState.Loading &&
            System.currentTimeMillis() - timeShowAdRecent > maxValueDebounceAdLoaded &&
            canReloadAd() &&
            isActiveState()

    override fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> applyDefaultVisibility()

            Lifecycle.Event.ON_RESUME -> {
                if (!canShowAds() && isActiveState()) {
                    cancel()
                    return
                }
                resumeCount.incrementAndGet()
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.postDelayed(resumeReloadRunnable, config.timeDebounceResume)
            }

            Lifecycle.Event.ON_DESTROY -> {
                // Deactivate first: a late fill must hit the !isActiveState() path and die
                flagActive.set(false)
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.removeCallbacks(reloadByTimeRunnable)
                NativeAdPreload.getInstance().unregisterAdCallback(preloadKey, preloadObserver)
                listeners.clear()
                nativeAd?.let { destroyNative(it) }
                nativeAd = null
                contentView = null
                shimmerView = null
            }

            else -> Unit
        }
    }

    private fun createOrConsumePreload() {
        if (!isEnablePreload) {
            loadFromNetwork()
            return
        }
        val preload = NativeAdPreload.getInstance()
        val buffered = preload.pollAdNative(preloadKey)
        when {
            // Buffered ad binds without a round trip — straight to Loaded, no Loading flash
            buffered != null -> onLoadedAd(buffered)

            preload.isPreloadInProgress(preloadKey) -> {
                setState(AdNativeState.Loading)
                preload.awaitNext(preloadKey) { ad ->
                    if (ad != null) onLoadedAd(ad) else onFailedToLoad()
                }
            }

            else -> loadFromNetwork()
        }
    }

    private fun loadFromNetwork() {
        if (!canRequestAds()) {
            if (!AdGate.isNetworkAvailable(context) && nativeAd == null) cancel()
            else setState(AdNativeState.Fail)
            return
        }
        setState(AdNativeState.Loading)
        placement?.let { key ->
            config.adUnitIds.forEach { AdTracking.registerPlacement(it, key) }
            AdTracking.request(key, AdFormat.NATIVE, config.idAds)
        }
        AdWaterfall.loadNative(
            activity,
            config.adUnitIds,
            config.layoutId,
            config.tierTimeoutMs,
            object : AdCallback() {
                override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
                    onLoadedAd(nativeAd)
                }

                override fun onAdFailedToLoad(adError: LoadAdError?) {
                    onFailedToLoad()
                    listeners.forEach { it.onAdFailedToLoad(adError) }
                }

                override fun onAdClicked() {
                    listeners.forEach { it.onAdClicked() }
                }

                override fun onAdImpression() {
                    onAdImpressionInternal()
                    listeners.forEach { it.onAdImpression() }
                }
            },
        )
    }

    /** GMA counted the impression — the reload timers anchor here, not on bind. */
    private fun onAdImpressionInternal() {
        timeShowAdRecent = System.currentTimeMillis()
        if (reloadByTimeMs > 0) {
            mainHandler.removeCallbacks(reloadByTimeRunnable)
            mainHandler.postDelayed(reloadByTimeRunnable, reloadByTimeMs)
        }
    }

    private fun onLoadedAd(ad: ApNativeAd) {
        if (!isActiveState()) {
            destroyNative(ad)
            return
        }
        val previous = nativeAd
        nativeAd = ad
        if (previous != null && previous !== ad) destroyNative(previous)
        setState(AdNativeState.Loaded(ad))
        listeners.forEach { it.onNativeAdLoaded(ad) }
    }

    private fun onFailedToLoad() {
        if (!isActiveState()) return
        val survivor = nativeAd
        if (survivor == null) {
            setState(AdNativeState.Fail)
        } else {
            // Failed reload keeps the old ad. Write the flow directly: going through
            // setState would re-bind the same ad; staying Loading would gate every
            // future request behind the re-entry guard
            _nativeAdState.value = AdNativeState.Loaded(survivor)
        }
    }

    private fun setState(state: AdNativeState) {
        _nativeAdState.value = state
        applyState(state)
    }

    private fun applyState(state: AdNativeState) {
        val showContent = state !is AdNativeState.Cancel && state !is AdNativeState.Fail &&
            canShowAds()
        contentView?.let { checkAdVisibility(it, showContent) }
        val showShimmer = state is AdNativeState.Loading && nativeAd == null
        shimmerView?.let { shimmer ->
            if (showShimmer) {
                shimmer.visibility = View.VISIBLE
                shimmer.startShimmer()
            } else {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
            }
        }
        if (state is AdNativeState.Loaded) bindLoadedAd(state.nativeAd)
    }

    private fun bindLoadedAd(ad: ApNativeAd) {
        val container = contentView ?: return
        runCatching { binder.bind(activity, ad, container, shimmerView) }
        // Bind is the baseline anchor; the impression callback re-anchors when it lands
        onAdImpressionInternal()
        refillAfterShow()
    }

    private fun refillAfterShow() {
        if (!isEnablePreload || !preloadClientOption.preloadAfterShow) return
        val preload = NativeAdPreload.getInstance()
        if (preload.getNativeAdBuffer(preloadKey).isEmpty() &&
            !preload.isPreloadInProgress(preloadKey)
        ) {
            preload.preloadWithKey(preloadKey, activity, config, preloadClientOption.preloadBuffer)
        }
    }

    private fun applyDefaultVisibility() {
        val hasAd = nativeAd != null
        contentView?.let { checkAdVisibility(it, canRequestAds() || hasAd) }
        shimmerView?.let { checkAdVisibility(it, canRequestAds() && !hasAd) }
    }

    private fun checkAdVisibility(view: View, visible: Boolean) {
        view.visibility = when {
            visible -> View.VISIBLE
            adVisibility == AdOptionVisibility.INVISIBLE -> View.INVISIBLE
            else -> View.GONE
        }
    }

    private fun reportSkip(passesUaGate: Boolean) {
        val key = placement ?: return
        val enabled = config.canShowAds && config.adUnitIds.isNotEmpty()
        val reason = AdGate.skipReason(context, enabled, passesUaGate) ?: return
        AdTracking.skipped(key, AdFormat.NATIVE, reason.key)
    }

    private fun destroyNative(ad: ApNativeAd) {
        runCatching { ad.admobNativeAd?.destroy() }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_AD_LOADED_MS: Long = 3_000L
        const val DEFAULT_RELOAD_BY_TIME_MS: Long = 15_000L
    }
}
