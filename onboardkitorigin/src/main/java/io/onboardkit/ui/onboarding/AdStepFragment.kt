package io.onboardkit.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.AdFullScreenStepDefinition
import io.onboardkit.config.NativeTemplate
import io.onboardkit.core.StepId
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObFragmentAdStepBinding
import io.onboardkit.ui.pager.LazyStepFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        b.obSkipButton.setOnClickListener { requireStepHost().next() }
    }

    override fun onStepFirstSelected() {
        val activity = activity ?: return
        OnboardingSdk.provider()?.suppressAppResume(activity.javaClass)
        requestAd()
        scheduleSkipButton()
    }

    override fun onStepSelected() {
        val activity = activity ?: return
        OnboardingSdk.provider()?.suppressAppResume(activity.javaClass)
        if (adBound) scheduleAutoNext()
    }

    override fun onStepUnselected(dwellMs: Long) {
        val activity = activity ?: return
        OnboardingSdk.provider()?.allowAppResume(activity.javaClass)
        skipJob?.cancel()
        autoNextJob?.cancel()
    }

    private fun definition(): AdFullScreenStepDefinition? =
        OnboardingSdk.configOrNull()?.stepById(stepId) as? AdFullScreenStepDefinition

    private fun requestAd() {
        val b = binding ?: return
        val activity = activity ?: return
        val config = OnboardingSdk.configOrNull() ?: return
        val provider = OnboardingSdk.provider()
        val placement = AdPlacement.StepFullScreen(stepId)
        val unit = config.ads.fullScreenStepNative
        if (provider == null || unit == null ||
            !OnboardingSdk.policy().canShowNative(activity, placement)
        ) {
            showFallback()
            return
        }
        val listener = object : AdEventListener {
            override fun onLoaded() {
                activity.runOnUiThread { bindAd() }
            }

            override fun onFailedToLoad() {
                activity.runOnUiThread { onAdFailed() }
            }

            override fun onImpression() {
                activity.runOnUiThread { onAdImpression() }
            }
        }
        if (!bindAd(listener)) {
            provider.preloadNative(
                activity,
                NativeAdRequest(
                    placement,
                    unit,
                    NativeTemplates.layoutFor(NativeTemplate.FULL_SCREEN),
                ),
            )
        }
    }

    private fun bindAd(listener: AdEventListener? = null): Boolean {
        val b = binding ?: return false
        val activity = activity ?: return false
        if (adBound) return true
        val bound = OnboardingSdk.provider()?.bindNative(
            activity,
            AdPlacement.StepFullScreen(stepId),
            b.obNativeContainer,
            b.obNativeShimmer.root,
            listener,
        ) == true
        if (bound) {
            adBound = true
            onAdImpression()
        }
        return bound
    }

    private fun onAdImpression() {
        OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.StepFullScreen(stepId).key))
        scheduleAutoNext()
    }

    private fun onAdFailed() {
        adFailed = true
        showFallback()
        val definition = definition()
        if (definition?.autoNextEnabled == true) {
            // Failed ad page has nothing to show — advance immediately
            stepHost?.next()
        }
    }

    private fun showFallback() {
        val b = binding ?: return
        b.obNativeShimmer.root.visibility = View.GONE
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

    private fun scheduleAutoNext() {
        val definition = definition() ?: return
        if (!definition.autoNextEnabled) return
        autoNextJob?.cancel()
        autoNextJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(definition.autoNextDelayMs)
            stepHost?.next()
        }
    }

    override fun onDestroyView() {
        skipJob?.cancel()
        autoNextJob?.cancel()
        activity?.let { OnboardingSdk.provider()?.allowAppResume(it.javaClass) }
        binding = null
        adBound = false
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
