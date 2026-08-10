package io.onboardkit.ui.pager

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import io.onboardkit.core.StepHost
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step fragment with lazy business logic: the view may exist early (offscreen pre-inflate),
 * but content/ad logic runs only when the page is actually selected — and only once.
 */
abstract class LazyStepFragment : Fragment() {

    private val hasInitView = AtomicBoolean(false)
    private val businessLogicRan = AtomicBoolean(false)
    private var selectedAtMs: Long = 0L

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
        if (businessLogicRan.compareAndSet(false, true)) {
            onStepFirstSelected()
        }
        onStepSelected()
    }

    internal fun dispatchUnselected() {
        if (!isAdded) return
        onStepUnselected(dwellMs())
    }

    protected fun dwellMs(): Long =
        if (selectedAtMs == 0L) 0L else System.currentTimeMillis() - selectedAtMs

    /** View bootstrap; runs exactly once per view lifetime. No ad requests here. */
    protected abstract fun onViewReady(view: View)

    /** One-time content/ad bootstrap; runs on the first selection only. */
    protected open fun onStepFirstSelected() {}

    /** Runs on every selection (including returns). */
    protected open fun onStepSelected() {}

    protected open fun onStepUnselected(dwellMs: Long) {}
}
