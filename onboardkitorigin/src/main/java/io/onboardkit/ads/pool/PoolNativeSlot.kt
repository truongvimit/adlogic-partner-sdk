package io.onboardkit.ads.pool

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
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.tracked
import io.onboardkit.ui.pool.StepSlotCallbacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One native slot of the pooled flow. It renders the pool's selection while [owner] is at least
 * [minState] and inflates a fresh view on every re-entry, replay included, as DashDrama does.
 * Every slot hides while ads are refused for the whole flow; an [Kind.OWNER] slot also hides on
 * its own placement's config refusal, while a [Kind.NON_OWNER] slot keeps showing what it borrows.
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

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> rerender()
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
        events = OnboardingPool.addEventListener(::onEvent)
        collector = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(minState) {
                binder.forceFreshOnNextRender()
                OnboardingPool.selection.collect { selection ->
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
     */
    fun setFrozen(frozen: Boolean) {
        if (frozen == this.frozen) return
        this.frozen = frozen
        if (!frozen) {
            binder.forceFreshOnNextRender()
            rerender()
        }
    }

    /** Frozen or below its min state, the slot neither renders nor hears its ad's events. */
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
            is PoolEvent.Clicked -> if (isLive() && event.ad === binder.boundAd()) tracked.onClicked()
            // A config refusal publishes nothing, so no selection arrives to hide the owner slot.
            is PoolEvent.Skipped -> if (kind == Kind.OWNER && event.key == placement.key &&
                event.result is LoadResult.SkippedByConfig
            ) {
                rerender()
            }
            else -> Unit
        }
    }

    private fun teardown() {
        if (tornDown) return
        tornDown = true
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

    /** Reports fo_ad_bound for [placement]; a click also tells app-resume the user is leaving. */
    private fun trackedFor(placement: AdPlacement): AdEventListener = placement.tracked(
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
}
