package io.onboardkit.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObFragmentAdStepBinding
import io.onboardkit.ui.pager.LazyStepFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen native step (OB3). No app content — the ad IS the page. Guarantees an exit:
 * if remote hides Skip while auto-next is off, Skip is forced visible anyway. App-resume ads
 * are suppressed while this page shows so two ads never stack.
 */
class AdStepFragment : LazyStepFragment() {

    private var binding: ObFragmentAdStepBinding? = null
    private var skipJob: Job? = null
    private var autoNextJob: Job? = null
    private var adBound = false
    private var adFailed = false
    private val impressionHandled = AtomicBoolean(false)

    private val stepId: StepId
        get() = StepId(requireArguments().getString(ARG_STEP_ID).orEmpty())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ObFragmentAdStepBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewReady(view: View) {
        val b = binding ?: return
        b.obFallbackImage.setImageDrawable(
            requireContext().packageManager.getApplicationIcon(requireContext().applicationInfo),
        )
        b.obSkipButton.setOnClickListener {
            requireStepHost().next(if (adFailed) StepExit.AD_FAILED else StepExit.SKIP)
        }
    }

    override fun onStepFirstSelected() {
        requestAd()
        scheduleSkipButton()
    }

    override fun onStepSelected() {
        if (adBound) scheduleAutoNext()
    }

    override fun onStepUnselected(dwellMs: Long) {
        skipJob?.cancel()
        autoNextJob?.cancel()
    }

    private fun definition(): AdFullScreenStepDefinition? =
        OnboardingSdk.configOrNull()?.stepById(stepId) as? AdFullScreenStepDefinition

    private fun requestAd() {
        val b = binding ?: return
        val activity = activity ?: return
        val placement = AdPlacement.StepFullScreen(stepId)
        activity.showNativeAd(
            placement = placement,
            // nativeUnitFor, not fullScreenStepNative: a host that gave this page its own entry in
            // `stepNatives` is what the guard and the preload chain both read, and asking for the
            // shared slot instead reported no_ad_unit for a page that had one.
            unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement),
            container = b.obNativeContainer,
            onBound = { adBound = true },
            onShown = { onAdImpression() },
            onUnavailable = { onAdFailed() },
            onAdEngaged = { onStepAdEngaged() },
        )
    }

    /**
     * Reached twice on the common path — once from the provider's listener notification inside
     * `bindNative`, once from [bindAd]'s own post-bind branch — so both the event and the auto-next
     * timer are latched. Before the latch OB3 reported two impressions and armed two timers.
     */
    private fun onAdImpression() {
        if (!impressionHandled.compareAndSet(false, true)) return
        OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.StepFullScreen(stepId).key))
        scheduleAutoNext()
    }

    private fun onAdFailed() {
        adFailed = true
        showFallback()
        // Not gated on autoNextEnabled any more. That flag decides how long a page waits with an
        // ad on it; a page with no ad has nothing to wait for, and leaving it up meant an empty
        // screen mid-flow until the user found Skip. The host handles the case where this answer
        // arrives while the pager is still settling onto the page.
        requireStepHost().skipAdStep(stepId)
    }

    private fun showFallback() {
        val b = binding ?: return
        b.obNativeContainer.visibility = View.GONE
        b.obFullscreenFallback.visibility = View.VISIBLE
        b.obSkipButton.visibility = View.VISIBLE
        skipJob?.cancel()
    }

    private fun scheduleSkipButton() {
        val b = binding ?: return
        val definition = definition() ?: return
        val flags = OnboardingSdk.flags()
        val skipAllowed = definition.showSkipButton && flags.showSkipOb3
        // Always keep one exit path: no skip + no auto-next would trap the user
        val mustForceSkip = !skipAllowed && !definition.autoNextEnabled
        if (!skipAllowed && !mustForceSkip) return
        val delaySec = flags.skipButtonDelaySec.takeIf { it >= 0 }
            ?: definition.skipButtonDelaySec.toLong()
        skipJob?.cancel()
        skipJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delaySec * 1_000)
            b.obSkipButton.visibility = View.VISIBLE
        }
    }

    /**
     * The countdown runs only while the page is in front of the user. A plain `delay` kept
     * counting through an ad click, advancing the pager while the user was still in the
     * destination — so the page they clicked from was gone before they got back, and it
     * navigated the host from the background.
     */
    private fun scheduleAutoNext() {
        val definition = definition() ?: return
        if (!definition.autoNextEnabled) return
        autoNextJob?.cancel()
        autoNextJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(definition.autoNextDelayMs)
                stepHost?.next(StepExit.AUTO_NEXT)
            }
        }
    }

    override fun onDestroyView() {
        skipJob?.cancel()
        autoNextJob?.cancel()
        binding = null
        adBound = false
        impressionHandled.set(false)
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STEP_ID = "ob_arg_step_id"
        private const val ARG_POSITION = "ob_arg_position"

        fun newInstance(stepId: StepId, position: Int): AdStepFragment =
            AdStepFragment().apply {
                arguments = bundleOf(ARG_STEP_ID to stepId.value, ARG_POSITION to position)
            }
    }
}
