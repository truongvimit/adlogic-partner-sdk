package io.onboardkit.ads.pool

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.AdRemoteConfig
import com.ads.module.config.toNativeStyle
import com.ads.module.helper.adnative.NativeAdStyle
import com.ads.module.helper.adnative.NativeClickAction
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.PoolCancellable
import com.ads.module.helper.adnative.pool.PoolEvent
import com.ads.module.helper.adnative.pool.PoolSelection
import com.ads.module.helper.adnative.pool.PoolSlotBinder
import com.ads.module.helper.adnative.pool.PoolSlotBinder.Outcome
import com.ads.module.helper.adnative.pool.PoolState
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.carouselBase
import io.onboardkit.ads.tracked
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.ui.pool.StepSlotCallbacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One native slot of the pooled flow. It renders the pool's selection while [owner] is at least
 * [minState] and inflates a fresh view on every re-entry, replay included, as DashDrama does.
 * Every slot hides while ads are refused for the whole flow; an [Kind.OWNER] slot also hides on
 * its own placement's config refusal, while a [Kind.NON_OWNER] slot keeps showing what it borrows.
 *
 * A slot bound while its screen is only STARTED (a pager page kept offscreen, or a screen behind a
 * translucent one) takes the ad's registration from the view the
 * user sees; the RESUMED slot showing that ad takes it back, so impressions and clicks count where
 * the ad is visible.
 */
@MainThread
internal class PoolNativeSlot(
    private val owner: LifecycleOwner,
    placement: AdPlacement,
    private val kind: Kind,
    private val minState: Lifecycle.State,
    private val container: FrameLayout,
    private val callbacks: StepSlotCallbacks? = null,
    private val destroyOnTeardown: Boolean = true,
    /**
     * Below [minState] at start, show the template's skeleton instead of an empty container: a
     * pager page binds only once RESUMED, after its slide, and would otherwise grow under the user.
     */
    private val placeholderUntilLive: Boolean = false,
    /** What the slot renders; an LFO carousel's second page passes [OnboardingPool.carouselSelection]. */
    private val selection: () -> Flow<PoolSelection<ApNativeAd>> = { OnboardingPool.selection },
    /** [selection] as it is now, for an unfreeze. */
    private val currentSelection: () -> PoolSelection<ApNativeAd>? = {
        OnboardingPool.engineOrNull()?.currentSelection()
    },
) {
    enum class Kind { OWNER, NON_OWNER }

    /** Picks the template, the style and, for an owner, the refusal the slot hides on. */
    var placement: AdPlacement = placement
        private set

    private var binder = binderFor(placement)

    /** Binders replaced by [setPlacement], torn down once the new one has drawn over their view. */
    private val retired = mutableListOf<PoolSlotBinder>()
    private var tracked = trackedFor(placement)

    /** fo_ad_bound is once per slot, for the placement the slot had at its first bind. */
    private var boundReported = false
    private var latest: PoolSelection<ApNativeAd>? = null
    private var lastOutcome: Outcome? = null
    private var lastHiddenReason: AdSkipReason? = null
    private var shownAd: ApNativeAd? = null
    private var frozen = false
    private var started = false
    private var tornDown = false
    private var events: PoolCancellable? = null
    private var collector: Job? = null
    private val reclaim = Runnable(::reclaimRegistration)
    private var reclaimPosted = false

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                takeBackRegistration()
                rerender()
            }
            Lifecycle.Event.ON_DESTROY -> teardown()
            else -> Unit
        }
    }

    fun start() {
        if (started) return
        started = true
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            teardown()
            return
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        if (placeholderUntilLive && !isLive() && refusal() == null &&
            binder.placeholder() == Outcome.SHIMMER
        ) {
            report(Outcome.SHIMMER, AdSkipReason.NO_FILL)
        }
        live += this
        events = OnboardingPool.addEventListener(::onEvent)
        collector = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(minState) {
                binder.forceFreshOnNextRender()
                selection().collect { selection ->
                    latest = selection
                    if (!frozen) render(selection)
                }
            }
        }
    }

    /** Moves the slot to another template and style key, e.g. the LFO from Language1 to Language2. */
    fun setPlacement(placement: AdPlacement) {
        if (placement == this.placement || tornDown) return
        this.placement = placement
        retired += binder
        binder = binderFor(placement)
        tracked = trackedFor(placement)
        lastOutcome = null
        rerender()
    }

    /**
     * While frozen the slot keeps what it shows and ignores the pool, e.g. behind a modal that
     * binds the same ad. Unfreezing re-inflates: the modal's view took that ad's registration.
     * It draws the pool's state of now: under `async_selector` the owner change that usually comes
     * with an unfreeze reaches [latest] a message later, and the modal's own ad would flash here.
     */
    fun setFrozen(frozen: Boolean) {
        if (frozen == this.frozen) return
        this.frozen = frozen
        if (!frozen) {
            currentSelection()?.let { latest = it }
            binder.forceFreshOnNextRender()
            rerender()
        }
    }

    /** True when the ad this slot shows already made its impression, here or on another screen. */
    fun showsImpressedAd(): Boolean {
        val ad = binder.boundAd() ?: return false
        return OnboardingPool.engineOrNull()?.debugSnapshot()?.loaders
            ?.any { it.ad === ad && it.state is PoolState.Showing } == true
    }

    /** The ad this slot's view holds, shown or hidden; null under a skeleton. */
    fun shownAd(): ApNativeAd? = binder.boundAd()

    /** The container shows an ad, not a skeleton and not hidden. */
    fun showsAd(): Boolean = binder.boundAd() != null && container.visibility == View.VISIBLE

    /** Ends the slot before its owner does, e.g. a carousel page taken down mid-screen. */
    fun stop() = teardown()

    /** Frozen or below its min state, the slot neither renders nor hears its ad's impressions. */
    private fun isLive(): Boolean = !frozen && owner.lifecycle.currentState.isAtLeast(minState)

    private fun rerender() {
        if (!isLive()) return
        latest?.let(::render)
    }

    private fun render(selection: PoolSelection<ApNativeAd>) {
        if (tornDown) return
        val refusal = refusal()
        val outcome = binder.render(if (refusal != null) PoolSelection.hidden() else selection)
        if (outcome == Outcome.IGNORED) return
        retired.forEach(PoolSlotBinder::teardown)
        retired.clear()
        report(outcome, refusal ?: AdSkipReason.NO_FILL)
        if (outcome == Outcome.BOUND_NEW && !resumed()) binder.boundAd()?.let(::handBackRegistration)
    }

    private fun resumed(): Boolean = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** Every RESUMED slot that shows [ad] re-inflates once the binds of this emission are done. */
    private fun handBackRegistration(ad: ApNativeAd) {
        live.forEach { slot ->
            if (slot !== this && slot.binder.boundAd() === ad && !slot.reclaimPosted) {
                slot.reclaimPosted = true
                main.post(slot.reclaim)
            }
        }
    }

    private fun reclaimRegistration() {
        reclaimPosted = false
        if (resumed() && takeBackRegistration()) rerender()
    }

    /** Arms a fresh inflation when the next render draws the same ad whose registration another view took. */
    private fun takeBackRegistration(): Boolean {
        if (frozen || tornDown || !binder.lostRegistration()) return false
        val ad = binder.boundAd() ?: return false
        if (latest?.ad !== ad || refusal() != null) return false
        binder.forceFreshOnNextRender()
        return true
    }

    private fun refusal(): AdSkipReason? {
        OnboardingSdk.guard().resumeEntrySkipReason(container.context)?.let { return it }
        if (kind == Kind.NON_OWNER) return null
        val result = OnboardingPool.lastResult(placement) as? LoadResult.SkippedByConfig ?: return null
        return skipReasonOf(result.reason) ?: AdSkipReason.ADS_OFF_IN_CONFIG
    }

    private fun report(outcome: Outcome, hiddenReason: AdSkipReason) {
        val previous = lastOutcome
        lastOutcome = outcome
        when (outcome) {
            Outcome.SHIMMER -> if (previous != Outcome.SHIMMER) callbacks?.onLoading?.invoke()
            Outcome.BOUND_NEW -> {
                if (!boundReported) {
                    boundReported = true
                    tracked.onLoaded()
                }
                callbacks?.onBound?.invoke()
            }
            // A hidden slot keeps its view; showing it again is a bind for the page.
            Outcome.BOUND_SAME -> if (previous == Outcome.HIDDEN) callbacks?.onBound?.invoke()
            Outcome.HIDDEN -> if (previous != Outcome.HIDDEN || hiddenReason != lastHiddenReason) {
                lastHiddenReason = hiddenReason
                callbacks?.onUnavailable?.invoke(hiddenReason)
            }
            Outcome.IGNORED -> Unit
        }
    }

    private fun onEvent(event: PoolEvent<ApNativeAd>) {
        if (tornDown) return
        when (event) {
            // Another slot may show the same ad; a frozen or paused one keeps its view but not the event.
            is PoolEvent.Impression -> if (isLive() && event.ad === binder.boundAd() && event.ad !== shownAd) {
                shownAd = event.ad
                callbacks?.onShown?.invoke()
            }
            // The vendor reports a tap from the ad's registered view, some adapters only once the
            // tap's destination has paused the screen: whatever its state, that view's slot hears it.
            is PoolEvent.Clicked -> if (!frozen && event.ad === binder.boundAd() && binder.holdsRegistration()) {
                tracked.onClicked()
            }
            // A config refusal publishes no selection. Hide the owner directly from the event so
            // a page that was pre-bound while STARTED cannot keep borrowing an ad after its own
            // placement is disabled.
            is PoolEvent.Skipped -> if (kind == Kind.OWNER && event.key == placement.key &&
                event.result is LoadResult.SkippedByConfig
            ) {
                render(PoolSelection.hidden())
            }
            else -> Unit
        }
    }

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        live -= this
        main.removeCallbacks(reclaim)
        reclaimPosted = false
        collector?.cancel()
        events?.cancel()
        owner.lifecycle.removeObserver(lifecycleObserver)
        retired.forEach(PoolSlotBinder::teardown)
        retired.clear()
        binder.teardown()
    }

    private fun binderFor(placement: AdPlacement) = PoolSlotBinder(
        container,
        NativeTemplates.layoutForPlacement(placement),
        { styleFor(placement) },
        destroyOnTeardown,
    )

    /**
     * Reports fo_ad_bound for [placement], a carousel's second page under its LFO; a click also
     * tells app-resume the user is leaving.
     */
    private fun trackedFor(placement: AdPlacement): AdEventListener = placement.carouselBase.tracked(
        object : AdEventListener {
            override fun onClicked() {
                callbacks?.onAdEngaged?.invoke(NativeClickAction.NONE)
            }
        },
    )

    private fun styleFor(placement: AdPlacement): NativeAdStyle? =
        OnboardingSdk.configuredPlacementKey(placement)?.let { key ->
            AdRemoteConfig.getInstance().unit(key).toNativeStyle()
        }

    private companion object {
        val main = Handler(Looper.getMainLooper())

        /** Started slots not torn down yet; main thread only. */
        val live = LinkedHashSet<PoolNativeSlot>()
    }
}

/**
 * LFO, its confirm popup, Privacy, Goal and the resume screen bind from STARTED, as DashDrama does:
 * a fill under the interstitial is drawn already, and GMA reports its impression once the screen
 * is back on top.
 */
internal val SCREEN_SLOT_MIN_STATE = Lifecycle.State.STARTED
