package io.onboardkit.ui.pager

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.StepHost
import io.onboardkit.core.analytics.StepExit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step fragment with lazy business logic: the view may exist early (offscreen pre-inflate),
 * but content/ad logic runs only when the page is actually selected — and only once.
 */
abstract class LazyStepFragment : Fragment() {

    private val hasInitView = AtomicBoolean(false)
    private val businessLogicRan = AtomicBoolean(false)
    private var selectedAtMs: Long = 0L
    private var adEngaged = false
    private var awayInAd = false

    protected val stepHost: StepHost?
        get() = activity as? StepHost

    /** Fail-fast host resolution — a silent null host made every ad slot no-op before. */
    protected fun requireStepHost(): StepHost =
        stepHost ?: error("${activity?.javaClass?.simpleName} must implement StepHost")

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hasInitView.compareAndSet(false, true)) {
            onViewReady(view)
        }
    }

    internal fun dispatchSelected() {
        if (!isAdded || view == null) return
        selectedAtMs = System.currentTimeMillis()
        // Being selected again is the pager's doing, not a return from this page's ad.
        adEngaged = false
        awayInAd = false
        if (businessLogicRan.compareAndSet(false, true)) {
            onStepFirstSelected()
        }
        onStepSelected()
    }

    internal fun dispatchUnselected() {
        if (!isAdded) return
        // The pager moved on without waiting for the user; whatever they do in the ad now, it
        // is no longer this page's turn to end.
        adEngaged = false
        awayInAd = false
        onStepUnselected(dwellMs())
    }

    /**
     * The user acted on this page's ad — a click, or the vendor reporting that the ad's
     * destination took the screen. Both are read because mediation splits them: Meta's native
     * adapter reports only the open, Pangle's only the click, and every adapter takes over
     * click handling so nothing else fills the gap.
     */
    protected fun onStepAdEngaged() {
        if (OnboardingSdk.configOrNull()?.behavior?.adClickReturnCompletesStep != true) return
        adEngaged = true
    }

    /** The ad's destination taking the screen is what pauses this page. */
    override fun onPause() {
        super.onPause()
        if (!adEngaged) return
        adEngaged = false
        awayInAd = true
    }

    /**
     * Back from the ad, which completes the page exactly like its CTA. Resume is both the
     * moment the user is actually back and the only moment the host is safe to navigate; and
     * since the pager resumes only the page in front of the user, a page the flow left while
     * they were away can never complete itself behind them.
     */
    override fun onResume() {
        super.onResume()
        // The page did pause, but the vendor callback landed behind it. Disarming makes that
        // trip a no-op instead of arming the user's next one.
        adEngaged = false
        if (!awayInAd) return
        awayInAd = false
        stepHost?.next(StepExit.AD_CLICK_RETURN)
    }

    /**
     * A touch reaching this page proves the ad that armed it opened nothing: while the
     * destination is on top, none arrive. Without this an ad whose launch failed outright —
     * no browser, Play disabled, a locked-down profile — would leave the page armed for the
     * rest of its life and turn the user's next trip out of the app into a completion.
     */
    internal fun onWindowTouched() {
        adEngaged = false
    }

    /** Internal, not protected: the pager host reads it to report step completion in one place. */
    internal fun dwellMs(): Long =
        if (selectedAtMs == 0L) 0L else System.currentTimeMillis() - selectedAtMs

    /** View bootstrap; runs exactly once per view lifetime. No ad requests here. */
    protected abstract fun onViewReady(view: View)

    /** One-time content/ad bootstrap; runs on the first selection only. */
    protected open fun onStepFirstSelected() {}

    /** Runs on every selection (including returns). */
    protected open fun onStepSelected() {}

    protected open fun onStepUnselected(dwellMs: Long) {}
}
