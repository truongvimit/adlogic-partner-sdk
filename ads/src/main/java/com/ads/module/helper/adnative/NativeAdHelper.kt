package com.ads.module.helper.adnative

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.google.android.gms.ads.nativead.NativeAdView
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.helper.AdGate
import com.ads.module.helper.AdOptionVisibility
import com.ads.module.helper.AdsHelper
import com.ads.module.tracking.AdTracking
import com.facebook.shimmer.Shimmer
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
 * While loading, the slot shows a shimmer skeleton. By default it is derived from the ad
 * layout itself ([NativeAdConfig.autoShimmer]); an explicit skeleton via [setShimmerLayoutView]
 * or [setShimmerLayout] takes precedence.
 *
 * ```
 * // Auto shimmer — skeleton derived from R.layout.native_home:
 * val helper = NativeAdHelper(
 *     activity, this, NativeAdConfig(ids, true, true, R.layout.native_home))
 *     .setNativeContentView(binding.frAds)
 *     .setNativeStyle(NativeAdStyle(ctaHeightDp = 44))   // optional; styles ad + skeleton
 * // Hand-made skeleton instead: .setShimmerLayoutView(binding.shimmer)
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
    private var nativeStyle: NativeAdStyle? = null

    /** Skeleton the helper itself created and inserted; app-supplied views never land here. */
    private var generatedShimmer: ShimmerFrameLayout? = null

    @LayoutRes
    private var shimmerLayoutId: Int? = null

    private var autoShimmerDecorator: ((ShimmerFrameLayout) -> Unit)? = null
    private var binder = NativeAdBinder { act, ad, container, shimmer ->
        NativeAdStyler.populate(act, ad, nativeStyle, container, shimmer)
    }

    private val listeners = CopyOnWriteArrayList<AdCallback>()
    private val resumeCount = AtomicInteger(0)
    private var timeShowAdRecent = 0L
    private var reloadByTimeMs = 0L
    private var nextReloadAtMs = 0L
    private var restoring = false
    private var restoreChecked = false
    private var pendingRestoration: NativePresentationStore.Presentation? = null
    private var awaitingHost = false
    private var restartOnResume = false
    private var requestVersion = 0L
    /** Disable when a containing integration owns request analytics. */
    var reportTelemetry: Boolean = true
    /** A consumed presentation awaiting its recreated host; it must not trigger a preload. */
    val isRestoringPresentation: Boolean get() = pendingRestoration != null
    private val presentationStore by lazy {
        (activity as? ViewModelStoreOwner)?.let { ViewModelProvider(it)[NativePresentationStore::class.java] }
    }

    private val resumeReloadRunnable = Runnable {
        if (reloadByTimeMs > 0) {
            armReload()
            return@Runnable
        }
        if (resumeCount.get() > 1 && canRequestAds() && canReloadAd() && isActiveState()) {
            requestAds(NativeAdParam.Reload)
        }
    }

    // Follow the consumed ad itself; another preload at this placement must not redirect its events.
    private val preloadObserver = object : AdCallback() {
        override fun onAdImpression() {
            if (isActiveState() && nativeAd != null) {
                listeners.forEach { it.onAdImpression() }
            }
        }

        override fun onAdClicked() {
            if (isActiveState() && nativeAd != null) {
                listeners.forEach { it.onAdClicked() }
            }
        }

        override fun onAdOpened() {
            if (isActiveState() && nativeAd != null) {
                listeners.forEach { it.onAdOpened() }
            }
        }
    }

    private val reloadByTimeRunnable = Runnable { reloadWhenVisible() }

    private fun reloadWhenVisible() {
        if (!isResumed() || !isActiveState() || !canReloadAd()) return
        if (contentView?.isShown != true) {
            mainHandler.postDelayed(reloadByTimeRunnable, reloadByTimeMs.coerceAtLeast(maxValueDebounceAdLoaded))
        } else if (conditionReloadAdAvailable()) requestAds(NativeAdParam.Reload)
    }

    init {
        bindLifecycle()
    }

    fun setNativeContentView(container: FrameLayout): NativeAdHelper {
        contentView = container
        applyDefaultVisibility()
        // A container handed over mid-load gets its skeleton created, shown, AND animated
        // now — applyState also applies Loading visibility, which applyDefaultVisibility
        // would get wrong offline
        if (_nativeAdState.value is AdNativeState.Loading) applyState(AdNativeState.Loading)
        // A view attached after the fill would otherwise stay blank until the next reload
        nativeAd?.takeIf { it.isUsable }?.let { bindLoadedAd(it) }
        if (awaitingHost && isActiveState() && isResumed() && !restorePresentation()) requestSharedAd()
        return this
    }

    fun setShimmerLayoutView(shimmer: ShimmerFrameLayout): NativeAdHelper {
        dropGeneratedShimmer()
        shimmerView = shimmer
        applyDefaultVisibility()
        // Swapped in mid-load: show and animate the replacement now
        if (_nativeAdState.value is AdNativeState.Loading) applyState(AdNativeState.Loading)
        return this
    }

    /**
     * Presentation for this placement, applied by the SDK to BOTH the loaded ad (default
     * binder) and the auto-derived skeleton — the two always share geometry, so the
     * skeleton→ad swap never shifts. Null (default) leaves the layout exactly as its XML
     * declares. Call again before [requestAds] to restyle the next load; a custom
     * [setNativeAdBinder] takes over ad-side styling itself (the skeleton still follows
     * this style).
     */
    fun setNativeStyle(style: NativeAdStyle?): NativeAdHelper {
        nativeStyle = style
        return this
    }

    /**
     * Escape hatch for styling the auto-generated skeleton beyond what [NativeAdStyle]
     * expresses — runs after the internal [setNativeStyle] pass. Never invoked for
     * explicit shimmers: those are already the app's own design. View ids survive the
     * skeleton transform, so findViewById-based code works on it directly.
     */
    fun setAutoShimmerDecorator(decorator: (ShimmerFrameLayout) -> Unit): NativeAdHelper {
        autoShimmerDecorator = decorator
        return this
    }

    /**
     * Explicit skeleton layout, inflated into the content view at first Loading. Beats
     * [NativeAdConfig.autoShimmer]; a [setShimmerLayoutView] view beats both. A root that
     * is not a ShimmerFrameLayout is wrapped, never class-cast.
     */
    fun setShimmerLayout(@LayoutRes layoutId: Int): NativeAdHelper {
        dropGeneratedShimmer()
        shimmerLayoutId = layoutId
        // Swapped in mid-load: inflate and animate the replacement now
        if (_nativeAdState.value is AdNativeState.Loading) applyState(AdNativeState.Loading)
        return this
    }

    fun setNativeAdBinder(nativeAdBinder: NativeAdBinder): NativeAdHelper {
        binder = nativeAdBinder
        return this
    }

    @JvmOverloads
    fun setEnablePreload(isEnable: Boolean, key: String = preloadKey): NativeAdHelper {
        isEnablePreload = isEnable
        preloadKey = key
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

    /** Explicit show: after a successful bind, calling again requests a different ad. */
    fun show() = requestAds(NativeAdParam.Request)

    /** Binds only an unused cache entry or a configuration-restored presentation. */
    fun bindAvailable(): Boolean {
        if (_nativeAdState.value is AdNativeState.Loading) return false
        if (!canShowAds() || !AdGate.passesUaGate(config.forceUaCheck)) return false
        flagActive.set(true)
        if (restorePresentation()) return nativeAd != null
        val ad = NativeAdManager.poll(storeKey) ?: return false
        onLoadedAd(ad)
        return nativeAd === ad
    }

    override fun requestAds(param: NativeAdParam) {
        if (_nativeAdState.value is AdNativeState.Loading) return
        val passesUaGate = AdGate.passesUaGate(config.forceUaCheck)
        val preloadHit = NativeAdPreload.getInstance().isPreloadAvailable(storeKey)
        // canShowAds() (not the raw config flag): Ready/preload waive the network
        // requirement only — a purchased user must never get a buffered ad bound
        val accepted = canShowAds() && config.adUnitIds.isNotEmpty() && passesUaGate &&
            (canRequestAds() || param is NativeAdParam.Ready || preloadHit)
        if (!accepted) {
            reportSkip(passesUaGate)
            val offline = !AdGate.isNetworkAvailable(context)
            if (offline) listeners.forEach { it.onAdFailedToLoad(null) }
            if (offline && nativeAd?.isUsable == true && canShowAds() && passesUaGate) onFailedToLoad()
            else cancel()
            return
        }
        when (param) {
            is NativeAdParam.Request -> {
                flagActive.set(true)
                if (!restorePresentation()) requestSharedAd()
            }

            is NativeAdParam.Reload -> {
                if (!conditionReloadAdAvailable()) return
                requestSharedAd()
            }

            is NativeAdParam.Ready -> {
                flagActive.set(true)
                NativeAdManager.claim(param.nativeAd)
                onLoadedAd(param.nativeAd)
            }
        }
    }

    override fun cancel() {
        requestVersion++
        pendingRestoration?.ad?.let(::destroyNative)
        pendingRestoration = null
        awaitingHost = false
        restartOnResume = false
        loadSubscription?.cancel()
        eventSubscription?.cancel()
        flagActive.compareAndSet(true, false)
        mainHandler.removeCallbacks(reloadByTimeRunnable)
        mainHandler.removeCallbacks(resumeReloadRunnable)
        nativeAd?.let(::destroyNative)
        nativeAd = null
        detachAdViews()
        dropGeneratedShimmer()
        setState(AdNativeState.Cancel)
    }

    /** Reload is allowed only after the last bind has been visible for a minimum time. */
    fun conditionReloadAdAvailable(): Boolean =
        _nativeAdState.value !is AdNativeState.Loading &&
            android.os.SystemClock.elapsedRealtime() - timeShowAdRecent > maxValueDebounceAdLoaded &&
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
                if (restartOnResume) {
                    restartOnResume = false
                    show()
                } else if (awaitingHost && isActiveState() && contentView != null) {
                    if (!restorePresentation()) requestSharedAd()
                } else if (nativeAd != null) armReload()
                resumeCount.incrementAndGet()
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.postDelayed(resumeReloadRunnable, config.timeDebounceResume)
            }

            Lifecycle.Event.ON_PAUSE -> mainHandler.removeCallbacks(reloadByTimeRunnable)

            Lifecycle.Event.ON_STOP -> {
                if (!activity.isChangingConfigurations) {
                    val restart = isActiveState()
                    cancel()
                    restartOnResume = restart
                }
            }

            Lifecycle.Event.ON_DESTROY -> {
                if (activity.isChangingConfigurations && isActiveState()) {
                    presentationStore?.let { store ->
                        store.retain(storeKey, pendingRestoration ?: NativePresentationStore.Presentation(
                            nativeAd, timeShowAdRecent, nextReloadAtMs,
                            _nativeAdState.value is AdNativeState.Loading))
                        nativeAd = null
                        pendingRestoration = null
                    }
                }
                requestVersion++
                detachAdViews()
                loadSubscription?.cancel()
                eventSubscription?.cancel()
                // Any callback already being dispatched must return its unbound fill to the store.
                flagActive.set(false)
                mainHandler.removeCallbacks(resumeReloadRunnable)
                mainHandler.removeCallbacks(reloadByTimeRunnable)
                listeners.clear()
                nativeAd?.let { destroyNative(it) }
                nativeAd = null
                dropGeneratedShimmer()
                contentView = null
                shimmerView = null
            }

            else -> Unit
        }
    }

    private var loadSubscription: NativeAdManager.Subscription? = null
    private var eventSubscription: NativeAdManager.Subscription? = null
    private val storeKey: String get() = if (isEnablePreload) preloadKey else placement ?: preloadKey

    private fun requestSharedAd() {
        awaitingHost = false
        setState(AdNativeState.Loading)
        loadSubscription?.cancel()
        val version = ++requestVersion
        val key = storeKey
        val subscription = NativeAdManager.acquire(activity, key, config, reportTelemetry && placement != null) { ad ->
            when {
                version != requestVersion || !isActiveState() -> ad?.let { NativeAdManager.returnUnused(key, it) }
                ad != null -> onLoadedAd(ad)
                else -> {
                    onFailedToLoad()
                    listeners.toList().forEach { runCatching { it.onAdFailedToLoad(null) } }
                }
            }
        }
        if (version == requestVersion) loadSubscription = subscription else subscription.cancel()
    }

    private fun restorePresentation(): Boolean {
        val saved = pendingRestoration ?: run {
            if (restoreChecked) return false
            restoreChecked = true
            presentationStore?.take(storeKey) ?: return false
        }
        if (!isResumed() || contentView == null) {
            pendingRestoration = saved
            awaitingHost = true
            setState(AdNativeState.Loading)
            return true
        }
        pendingRestoration = null
        awaitingHost = false
        timeShowAdRecent = saved.shownAtMs
        nextReloadAtMs = saved.refreshAtMs
        saved.ad?.let { ad ->
            if (ad.isUsable) {
                restoring = true
                try { onLoadedAd(ad) } finally { restoring = false }
            } else destroyNative(ad)
        }
        if (saved.awaiting || nativeAd == null) requestSharedAd()
        return true
    }

    /** A successful new bind anchors refresh; reattaching the same presentation preserves it. */
    private fun onAdImpressionInternal() {
        if (restoring) {
            armReload()
            return
        }
        timeShowAdRecent = android.os.SystemClock.elapsedRealtime()
        nextReloadAtMs = if (reloadByTimeMs > 0) timeShowAdRecent + reloadByTimeMs else 0L
        armReload()
    }

    private fun armReload() {
        mainHandler.removeCallbacks(reloadByTimeRunnable)
        if (reloadByTimeMs <= 0 || !isResumed() || !isActiveState() || !canReloadAd()) return
        val due = maxOf(nextReloadAtMs, timeShowAdRecent + maxValueDebounceAdLoaded + 1)
        val delay = (due - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        mainHandler.postDelayed(reloadByTimeRunnable, delay)
    }

    private fun detachAdViews() {
        contentView?.let { container ->
            (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<NativeAdView>().forEach { it.destroy() }
            container.removeAllViews()
        }
    }

    private fun onLoadedAd(ad: ApNativeAd) {
        if (isActiveState() && canShowAds() && ad.isUsable && (contentView == null || !isResumed())) {
            NativeAdManager.returnUnused(storeKey, ad)
            awaitingHost = true
            setState(AdNativeState.Loading)
            return
        }
        if (!canShowAds()) {
            destroyNative(ad)
            cancel()
            return
        }
        if (!isActiveState() || !ad.isUsable) {
            destroyNative(ad)
            onFailedToLoad()
            return
        }
        val previous = nativeAd
        if (!bindLoadedAd(ad)) {
            if (previous !== ad) destroyNative(ad)
            onFailedToLoad()
            listeners.forEach { it.onAdFailedToShow(null) }
            return
        }
        nativeAd = ad
        eventSubscription?.cancel()
        eventSubscription = NativeAdManager.observe(ad, preloadObserver)
        setState(AdNativeState.Loaded(ad))
        onAdImpressionInternal()
        if (previous != null && previous !== ad) destroyNative(previous)
        refillAfterShow()
        listeners.forEach { it.onNativeAdLoaded(ad) }
    }

    private fun onFailedToLoad() {
        if (!isActiveState()) return
        if (reloadByTimeMs > 0 && isResumed() && canReloadAd()) {
            nextReloadAtMs = android.os.SystemClock.elapsedRealtime() + reloadByTimeMs
            armReload()
        }
        nativeAd?.takeUnless { it.isUsable }?.let { expired ->
            destroyNative(expired)
            nativeAd = null
            detachAdViews()
        }
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
        if (state is AdNativeState.Loading) ensureShimmer()
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
    }

    private fun bindLoadedAd(ad: ApNativeAd): Boolean {
        val container = contentView ?: return false
        ad.layoutCustomNative = config.layoutId
        if (runCatching { binder.bind(activity, ad, container, shimmerView) }
                .onFailure { android.util.Log.w("NativeAdHelper", "Cannot bind $storeKey", it) }.isFailure) return false
        if (generatedShimmer != null && generatedShimmer?.parent !== contentView) dropGeneratedShimmer()
        return true
    }

    /**
     * Auto-shimmer runs at the exact seam that decides shimmer visibility, so every path
     * that can show Loading is covered — and nothing is built when it would never be shown
     * (instant preload hit, purchased user, explicit shimmer already provided).
     */
    private fun ensureShimmer() {
        val container = contentView ?: return
        // A skeleton left behind by a container handoff or a destroyed view tree (Fragment
        // recreate) would keep the live slot empty for the whole load window. Parent
        // identity is exact for the helper's own skeleton — it is always a direct child of
        // the container it serves. App-supplied views are never second-guessed: their
        // lifecycle belongs to the app, exactly as before this feature.
        generatedShimmer?.let { generated ->
            if (shimmerView === generated && generated.parent !== container) {
                dropGeneratedShimmer()
            }
        }
        // canShowAds, not canRequestAds: Loading only starts with a request or an in-flight
        // preload behind it, so the skeleton must show even if the network dropped since
        if (shimmerView != null || nativeAd != null || !canShowAds()) return
        val shimmer = shimmerLayoutId?.let { inflateShimmerLayout(it, container) }
            ?: if (config.autoShimmer) {
                NativeAdShimmer.from(activity, config.layoutId).also { skeleton ->
                    nativeStyle?.let { runCatching { NativeAdStyler.applyLayout(skeleton, it) } }
                    autoShimmerDecorator?.let { runCatching { it(skeleton) } }
                }
            } else {
                return
            }
        container.addView(shimmer)
        generatedShimmer = shimmer
        shimmerView = shimmer
    }

    private fun inflateShimmerLayout(@LayoutRes layoutId: Int, parent: ViewGroup): ShimmerFrameLayout? =
        runCatching {
            val view = LayoutInflater.from(activity).inflate(layoutId, parent, false)
            view as? ShimmerFrameLayout ?: ShimmerFrameLayout(activity).apply {
                // Root margins stay on the wrapper; the child gets fresh params — sharing
                // one instance would apply the margins twice
                layoutParams = view.layoutParams
                setShimmer(Shimmer.AlphaHighlightBuilder().setAutoStart(false).build())
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }.getOrNull()

    private fun dropGeneratedShimmer() {
        generatedShimmer?.let { generated ->
            if (shimmerView === generated) shimmerView = null
            (generated.parent as? ViewGroup)?.removeView(generated)
        }
        generatedShimmer = null
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
        NativeAdManager.dispose(ad)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_AD_LOADED_MS: Long = 3_000L
        const val DEFAULT_RELOAD_BY_TIME_MS: Long = 15_000L
    }
}
