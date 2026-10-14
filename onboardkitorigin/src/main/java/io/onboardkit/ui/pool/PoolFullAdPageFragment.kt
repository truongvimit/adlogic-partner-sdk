package io.onboardkit.ui.pool

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.ads.module.helper.adnative.pool.LoadResult
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.FullScreenSkipPosition
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObFragmentAdStepBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.applyFullScreenSkip
import io.onboardkit.ui.pager.LazyStepFragment

/**
 * A full-screen page of the pooled pager (DashDrama J6/f). The pool's selection fills it from
 * RESUMED, X appears a fixed time after the page first resumes, and a page left with nothing to
 * show while selected skips itself. Auto-next belongs to the pager, which counts it from selection.
 */
internal class PoolFullAdPageFragment : LazyStepFragment() {

    private var binding: ObFragmentAdStepBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val revealSkip = Runnable { binding?.obSkipButton?.visibility = View.VISIBLE }
    private var skipScheduled = false
    private var onScreen = false
    private var completed = false
    private var shownReported = false
    private var pooled = false

    /** The slot's last answer was "nothing to show": hidden selection or a refusal. */
    private var unavailable = false

    private val stepId: StepId
        get() = StepId(requireArguments().getString(ARG_STEP_ID).orEmpty())

    private val snapshot: NativeFlowSnapshot
        get() = (activity as? ObPoolFlowActivity)?.snapshot ?: OnboardingSettings.nativeFlowSnapshot()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ObFragmentAdStepBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewReady(view: View) {
        val b = binding ?: return
        val definition = OnboardingSdk.configOrNull()?.stepById(stepId) as? AdFullScreenStepDefinition
        b.obSkipButton.applyFullScreenSkip(
            definition?.skipButtonStyle ?: OnboardingSdk.requireConfig().ads.fullScreenSkipStyle,
            definition?.skipButtonPosition ?: FullScreenSkipPosition.RIGHT,
        )
        b.obSkipButton.visibility = View.GONE
        b.obSkipButton.setOnClickListener { complete(StepExit.SKIP) }
        val viewVersion = stepViewVersion
        pooled = (activity as? ObPoolFlowActivity)?.bindStepSlot(
            this,
            AdPlacement.StepFullScreen(stepId),
            b.obNativeContainer,
            StepSlotCallbacks(
                onLoading = { if (isCurrentStepView(viewVersion)) unavailable = false },
                onBound = { if (isCurrentStepView(viewVersion)) unavailable = false },
                onShown = { if (isCurrentStepView(viewVersion)) onAdShown() },
                onUnavailable = { if (isCurrentStepView(viewVersion)) onAdUnavailable() },
                onAdEngaged = { action -> if (isCurrentStepView(viewVersion)) onStepAdEngaged(action) },
            ),
        ) == true
        if (!pooled) onAdUnavailable()
    }

    override fun onStepSelected() {
        onScreen = true
        completed = false
        // A slot bound offscreen may have answered before this selection's own claim; the new
        // selection reaches it later.
        if (unavailable && slotWouldShowNow()) unavailable = false
        if (unavailable) complete(StepExit.AD_FAILED)
    }

    /** What the slot would render now: no refusal, and a selection that shows an ad or a shimmer. */
    private fun slotWouldShowNow(): Boolean {
        if (!pooled) return false
        if (OnboardingSdk.guard().resumeEntrySkipReason(requireContext()) != null) return false
        if (OnboardingPool.lastResult(AdPlacement.StepFullScreen(stepId)) is LoadResult.SkippedByConfig) return false
        return OnboardingPool.engineOrNull()?.currentSelection()?.show == true
    }

    override fun onStepUnselected(dwellMs: Long) {
        onScreen = false
    }

    /** DD J6/c: an empty page moves on, but only once it is the page the user is on. */
    private fun onAdUnavailable() {
        unavailable = true
        if (onScreen) complete(StepExit.AD_FAILED)
    }

    private fun onAdShown() {
        if (shownReported || !onScreen || completed) return
        shownReported = true
        OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.StepFullScreen(stepId).key))
    }

    /** DD J6/d.java: X shows a fixed time after the first resume, whatever the ad does meanwhile. */
    override fun onResume() {
        super.onResume()
        if (skipScheduled) return
        skipScheduled = true
        handler.postDelayed(revealSkip, snapshot.fullscreenSkipDelayMs)
    }

    override fun onAdClickReturn() {
        complete(StepExit.AD_CLICK_RETURN)
    }

    private fun complete(reason: String) {
        if (completed) return
        completed = true
        requireStepHost().completeAdStep(stepId, reason)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(revealSkip)
        skipScheduled = false
        onScreen = false
        unavailable = false
        pooled = false
        shownReported = false
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STEP_ID = "ob_arg_step_id"
        private const val ARG_POSITION = "ob_arg_position"

        fun newInstance(stepId: StepId, position: Int): PoolFullAdPageFragment =
            PoolFullAdPageFragment().apply {
                arguments = bundleOf(ARG_STEP_ID to stepId.value, ARG_POSITION to position)
            }
    }
}
