package io.onboardkit.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showInterstitial
import io.onboardkit.core.FinishReason
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepHost
import io.onboardkit.core.StepId
import io.onboardkit.core.StepType
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityOnboardingBinding
import io.onboardkit.flow.ExitDecision
import io.onboardkit.flow.FlowNavigator
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

    override val screenName: String = "ob_onboarding"

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
        enabledStepIds = FlowNavigator.enabledSteps(config, sdk.flags(), sdk.guard().isPremium(this))
        ObLog.d(ObLog.Section.SCREEN, "ob_onboarding steps=${enabledStepIds.map { it.value }}")
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
        // variantKey is the remote template bucket the page was built with — without it a funnel
        // difference between two remote buckets is unattributable
        OnboardingSdk.track(
            AnalyticsEvent.StepViewed(stepId, position, pagerAdapter.pageAt(position)?.variantKey),
        )
        sdk.preload().onStepSelected(this, enabledStepIds, position)

        // The page may not be attached yet on first layout; post until the fragment exists
        binding.obStepPager.post {
            pagerAdapter.fragmentAt(position)?.dispatchSelected()
        }
    }

    // ── StepHost ──

    /**
     * The single completion point for a step, whichever page type it was.
     *
     * Content steps used to report their own completion while ad steps reported none at all, so
     * `fo_step_complete` silently under-counted by the number of OB3-style pages in the flow. Dwell
     * is read off the fragment here rather than passed in, so both page types measure it the same
     * way.
     */
    override fun next(exitReason: String?) {
        // Mid-transition next() is always a duplicate: a CTA double-tap, or a late ad callback
        // firing while the pager is already animating away. Letting it through completed the NEXT
        // step with zero dwell and jumped a page.
        if (binding.obStepPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val position = binding.obStepPager.currentItem
        enabledStepIds.getOrNull(position)?.let { stepId ->
            val dwellMs = pagerAdapter.fragmentAt(position)?.dwellMs() ?: 0L
            // SDK scope, not lifecycleScope: the last step finishes this Activity right away.
            val store = sdk.stateStoreOrNull()
            if (store != null) sdk.scope().launch { store.markStepCompleted(stepId) }
            OnboardingSdk.emitEvent(OnboardingEvent.StepCompleted(stepId, dwellMs))
            OnboardingSdk.track(
                AnalyticsEvent.StepCompleted(stepId, position, dwellMs, exitReason ?: StepExit.CTA),
            )
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

    override fun finishFlow(reason: FinishReason) {
        lifecycleScope.launch {
            presentAfterOnboardingPaywall()
            OnboardingSdk.completeFlow(this@ObOnboardingHostActivity)
            finish()
        }
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
        ObLog.d(ObLog.Section.NAV, "ob_onboarding exit_decision=$decision")
        when (decision) {
            ExitDecision.ShowReusedInterstitialThenComplete ->
                showInterstitial(
                    AdPlacement.SplashInterstitial,
                    onFinished = { finishFlow(FinishReason.COMPLETED) },
                )

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

    /** Outcome is intentionally ignored: the flow completes either way. */
    private suspend fun presentAfterOnboardingPaywall() {
        sdk.presentPaywall(this, PaywallPlacement.AFTER_ONBOARDING)
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
