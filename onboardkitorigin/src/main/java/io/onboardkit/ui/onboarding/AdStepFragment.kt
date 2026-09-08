package io.onboardkit.ui.onboarding

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObFragmentAdStepBinding
import io.onboardkit.ui.applyFullScreenSkipStyle
import io.onboardkit.ui.pager.LazyStepFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Full-screen native step (OB3). No app content — the ad IS the page. Guarantees an exit:
 * if remote hides Skip while auto-next is off, Skip is forced visible anyway. App-resume ads
 * are suppressed while this page shows so two ads never stack.
 */
class AdStepFragment : LazyStepFragment() {

    private var binding: ObFragmentAdStepBinding? = null
    private var skipJob: Job? = null
    private var autoNextJob: Job? = null
    private var selected = false
    private var completed = false
    private var autoNextDeadlineMs: Long? = null
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
        b.obSkipButton.applyFullScreenSkipStyle(
            definition()?.skipButtonStyle ?: OnboardingSdk.requireConfig().ads.fullScreenSkipStyle,
        )
        b.obSkipButton.setOnClickListener {
            completeStep(if (adFailed) StepExit.AD_FAILED else StepExit.SKIP)
        }
    }

    override fun onStepSelected() {
        if (selected) return
        selected = true
        completed = false
        scheduleAutoNext()
        requestAd()
        if (!completed) scheduleSkipButton()
    }

    override fun onStepUnselected(dwellMs: Long) {
        selected = false
        skipJob?.cancel()
        autoNextJob?.cancel()
        OnboardingSdk.provider()?.releaseNative(AdPlacement.StepFullScreen(stepId))
        autoNextDeadlineMs = null
        adFailed = false
        impressionHandled.set(false)
    }

    private fun definition(): AdFullScreenStepDefinition? =
        OnboardingSdk.configOrNull()?.stepById(stepId) as? AdFullScreenStepDefinition

    private fun requestAd() {
        val b = binding ?: return
        val activity = activity ?: return
        b.obFullscreenFallback.visibility = View.GONE
        b.obNativeContainer.visibility = View.VISIBLE
        b.obSkipButton.visibility = View.GONE
        val placement = AdPlacement.StepFullScreen(stepId)
        activity.showNativeAd(
            placement = placement,
            // nativeUnitFor, not fullScreenStepNative: a host that gave this page its own entry in
            // `stepNatives` is what the guard and the preload chain both read, and asking for the
            // shared slot instead reported no_ad_unit for a page that had one.
            unit = OnboardingSdk.configOrNull()?.ads?.nativeUnitFor(placement),
            container = b.obNativeContainer,
            onShown = { onAdImpression() },
            onUnavailable = { onAdFailed() },
            onAdEngaged = { onStepAdEngaged() },
        )
    }

    /** A provider may repeat its impression callback; emit the page signal once per visit. */
    private fun onAdImpression() {
        if (!selected || completed || !impressionHandled.compareAndSet(false, true)) return
        OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.StepFullScreen(stepId).key))
    }

    private fun onAdFailed() {
        if (!selected || completed) return
        adFailed = true
        showFallback()
        // Not gated on autoNextEnabled any more. That flag decides how long a page waits with an
        // ad on it; a page with no ad has nothing to wait for, and leaving it up meant an empty
        // screen mid-flow until the user found Skip. The host handles the case where this answer
        // arrives while the pager is still settling onto the page.
        completeStep(StepExit.AD_FAILED)
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
            ?: definition.skipButtonDelaySec.toLong().coerceAtLeast(0)
        skipJob?.cancel()
        skipJob = viewLifecycleOwner.lifecycleScope.launch {
            delay((delaySec * 1_000).milliseconds)
            b.obSkipButton.visibility = View.VISIBLE
        }
    }

    /** One deadline per visit. Pausing the Activity does not cancel or restart it. */
    private fun scheduleAutoNext() {
        val definition = definition() ?: return
        if (!definition.autoNextEnabled) return
        val durationMs = definition.autoNextDelayMs.coerceAtLeast(0)
        autoNextDeadlineMs = SystemClock.elapsedRealtime() + durationMs
        autoNextJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(durationMs.milliseconds)
            completeStep(StepExit.AUTO_NEXT)
        }
    }

    override fun onResume() {
        super.onResume()
        // Android can suspend the process/CPU while away. Catch up before starting a new wait.
        if (autoNextDeadlineMs?.let { SystemClock.elapsedRealtime() >= it } == true) {
            completeStep(StepExit.AUTO_NEXT)
        }
    }

    private fun completeStep(reason: String) {
        if (!selected || completed) return
        completed = true
        skipJob?.cancel()
        autoNextJob?.cancel()
        requireStepHost().completeAdStep(stepId, reason)
    }

    override fun onDestroyView() {
        selected = false
        skipJob?.cancel()
        autoNextJob?.cancel()
        binding = null
        autoNextDeadlineMs = null
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
