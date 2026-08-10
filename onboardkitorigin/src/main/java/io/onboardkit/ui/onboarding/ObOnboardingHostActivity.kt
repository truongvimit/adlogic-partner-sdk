package io.onboardkit.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.core.FinishReason
import io.onboardkit.core.StepHost
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityOnboardingBinding
import io.onboardkit.flow.ExitDecision
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.ob5.ObFullScreenAdActivity
import io.onboardkit.ui.pager.StepPage
import io.onboardkit.ui.pager.StepPagerAdapter
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Pager host. Steps are gated per remote flag in the fixed configured order; an empty result
 * completes the flow instead of stranding the user on an empty pager. Swipe is locked by
 * default (buttons navigate); back goes one step backwards and exits only from the first step.
 */
class ObOnboardingHostActivity : BaseOnboardActivity(), StepHost {

    private lateinit var binding: ObActivityOnboardingBinding
    private lateinit var pagerAdapter: StepPagerAdapter

    private val _currentIndex = MutableStateFlow(0)
    private val _totalSteps = MutableStateFlow(0)
    private var lastSelectedPosition = -1

    override val currentIndex: StateFlow<Int> get() = _currentIndex
    override val totalSteps: StateFlow<Int> get() = _totalSteps

    private var enabledStepIds: List<StepId> = emptyList()

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = sdk.requireConfig()
        enabledStepIds = FlowNavigator.enabledSteps(config, sdk.flags())
        if (enabledStepIds.isEmpty()) {
            finishFlow(FinishReason.EMPTY_FLOW)
            return
        }

        pagerAdapter = StepPagerAdapter(this)
        binding.obStepPager.adapter = pagerAdapter
        binding.obStepPager.isUserInputEnabled = !config.behavior.lockPagerSwipe
        binding.obStepPager.offscreenPageLimit = 1
        pagerAdapter.submit(buildPages(), visibleIndex = -1)
        _totalSteps.value = enabledStepIds.size

        binding.obStepPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    dispatchPageChange(position)
                }
            },
        )

        val resume = intent.getIntExtra(EXTRA_RESUME_INDEX, 0)
            .coerceIn(0, enabledStepIds.size - 1)
        if (resume > 0) binding.obStepPager.setCurrentItem(resume, false)

        // Hot-swap: a mid-flow remote change rebuilds only the pages not currently visible
        lifecycleScope.launch {
            sdk.remoteOrNull()?.flags?.drop(1)?.collect { rebuildPendingPages() }
        }
    }

    private fun buildPages(): List<StepPage> {
        val config = sdk.requireConfig()
        val contentVariant = sdk.flags().templateContent
        return enabledStepIds.mapNotNull { id ->
            config.stepById(id)?.let { def ->
                val variant = when (def.type) {
                    StepType.CONTENT -> contentVariant
                    StepType.AD_FULL_SCREEN -> "fullscreen"
                }
                StepPage(def, variant)
            }
        }
    }

    /** Hot-swap entry: rebuilds not-yet-visible pages when a better ad variant is available. */
    internal fun rebuildPendingPages() {
        pagerAdapter.submit(buildPages(), visibleIndex = binding.obStepPager.currentItem)
    }

    private fun dispatchPageChange(position: Int) {
        if (lastSelectedPosition >= 0 && lastSelectedPosition != position) {
            pagerAdapter.fragmentAt(lastSelectedPosition)?.dispatchUnselected()
        }
        lastSelectedPosition = position
        _currentIndex.value = position

        val stepId = enabledStepIds.getOrNull(position) ?: return
        OnboardingSdk.session.recordStepShown(stepId)
        OnboardingSdk.emitEvent(OnboardingEvent.StepViewed(stepId, position))
        OnboardingSdk.track(AnalyticsEvent.StepViewed(stepId, position))
        sdk.preload().onStepSelected(this, enabledStepIds, position)

        // The page may not be attached yet on first layout; post until the fragment exists
        binding.obStepPager.post {
            pagerAdapter.fragmentAt(position)?.dispatchSelected()
        }
    }

    // ── StepHost ──

    override fun next() {
        val position = binding.obStepPager.currentItem
        enabledStepIds.getOrNull(position)?.let { stepId ->
            // SDK scope, not lifecycleScope: the last step finishes this Activity right away.
            val store = sdk.stateStoreOrNull()
            if (store != null) sdk.scope().launch { store.markStepCompleted(stepId) }
            OnboardingSdk.emitEvent(OnboardingEvent.StepCompleted(stepId, 0))
        }
        if (position < enabledStepIds.size - 1) {
            binding.obStepPager.setCurrentItem(position + 1, true)
        } else {
            resolveExit()
        }
    }

    override fun back(): Boolean {
        val position = binding.obStepPager.currentItem
        if (position <= 0) return false
        binding.obStepPager.setCurrentItem(position - 1, true)
        return true
    }

    override fun skipTo(stepId: StepId) {
        val index = enabledStepIds.indexOf(stepId)
        if (index >= 0) binding.obStepPager.setCurrentItem(index, true)
    }

    override fun finishFlow(reason: FinishReason) {
        lifecycleScope.launch {
            presentAfterOnboardingPaywall()
            OnboardingSdk.completeFlow(this@ObOnboardingHostActivity)
            finish()
        }
    }

    override fun setProgressVisible(visible: Boolean) {
        // Indicators live inside content steps; full-screen ad steps simply have none.
    }

    // ── Exit handoff ──

    private fun resolveExit() {
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val decision = FlowNavigator.decideExit(
            flags = sdk.flags(),
            config = config,
            hasReusableSplashInterstitial =
            provider?.isInterstitialReady(AdPlacement.SplashInterstitial) == true,
            isOb5NativeReady = provider?.isNativeReady(AdPlacement.Ob5) == true,
        )
        when (decision) {
            ExitDecision.ShowReusedInterstitialThenComplete -> {
                provider?.showInterstitial(this, AdPlacement.SplashInterstitial) {
                    finishFlow(FinishReason.COMPLETED)
                } ?: finishFlow(FinishReason.COMPLETED)
            }

            ExitDecision.GoToOb5 -> {
                ObFullScreenAdActivity.start(this)
                finish()
            }

            ExitDecision.GoToQuestion -> {
                ObQuestionActivity.start(this, QuestionSource.NEW_USER)
                finish()
            }

            ExitDecision.Complete -> finishFlow(FinishReason.COMPLETED)
        }
    }

    private suspend fun presentAfterOnboardingPaywall() {
        val gate = sdk.paywall() ?: return
        if (gate.shouldShow(PaywallPlacement.AFTER_ONBOARDING)) {
            when (gate.present(this, PaywallPlacement.AFTER_ONBOARDING)) {
                PaywallOutcome.Purchased,
                PaywallOutcome.Dismissed,
                PaywallOutcome.ContinueWithAds,
                -> Unit
            }
        }
    }

    override fun handleBack() {
        val config = sdk.configOrNull()
        if (config?.behavior?.backNavigatesBack == true && back()) return
        finishAffinity()
    }

    internal fun stepIdAt(position: Int): StepId? = enabledStepIds.getOrNull(position)

    companion object {
        private const val EXTRA_RESUME_INDEX = "ob_extra_resume_index"

        fun start(activity: Activity, resumeIndex: Int) {
            activity.startActivity(
                Intent(activity, ObOnboardingHostActivity::class.java)
                    .putExtra(EXTRA_RESUME_INDEX, resumeIndex),
            )
        }
    }
}
