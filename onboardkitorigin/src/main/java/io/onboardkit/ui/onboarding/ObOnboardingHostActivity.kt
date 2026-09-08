package io.onboardkit.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.viewpager2.widget.ViewPager2
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.loadAndShowInterstitial
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
import io.onboardkit.ui.pager.AdvanceFlingDetector
import io.onboardkit.ui.pager.StepPage
import io.onboardkit.ui.pager.StepPagerAdapter
import io.onboardkit.ui.pager.pageHasHorizontallyScrollableViewUnder
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
    override val excludeFromAppResume: Boolean = false

    internal override val resumeBlockedByScreen: Boolean
        get() {
            if (super.resumeBlockedByScreen || !::binding.isInitialized || exitResolved ||
                binding.obStepPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return true
            val id = enabledStepIds.getOrNull(binding.obStepPager.currentItem) ?: return true
            return sdk.configOrNull()?.stepById(id)?.type != StepType.CONTENT
        }

    private lateinit var binding: ObActivityOnboardingBinding
    private lateinit var pagerAdapter: StepPagerAdapter

    private val _currentIndex = MutableStateFlow(0)
    private val _totalSteps = MutableStateFlow(0)
    private var lastSelectedPosition = -1
    private var advanceFlingDetector: AdvanceFlingDetector? = null
    private var gestureBeganOnRestingLastStep = false
    private var exitResolved = false

    override val currentIndex: StateFlow<Int> get() = _currentIndex
    override val totalSteps: StateFlow<Int> get() = _totalSteps

    private var enabledStepIds: List<StepId> = emptyList()

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = sdk.requireConfig()
        enabledStepIds = FlowNavigator.enabledSteps(
            config,
            sdk.flags(),
            sdk.guard().isPremium(this),
            OnboardingSdk::canFillAdOnlyStep,
        )
        ObLog.d(ObLog.Section.SCREEN, "ob_onboarding steps=${enabledStepIds.map { it.value }}")
        if (enabledStepIds.isEmpty()) {
            finishFlow(FinishReason.EMPTY_FLOW)
            return
        }

        sdk.preload().onOnboardingShown(this)

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

        if (config.behavior.swipeCompletesLastStep) {
            advanceFlingDetector = AdvanceFlingDetector(
                this,
                rtl = { binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL },
                onAdvanceFling = ::completeLastStepBySwipe,
            )
        }

        // Hot-swap: a mid-flow remote change rebuilds only the pages not currently visible
        lifecycleScope.launch {
            sdk.remoteOrNull()?.flags?.drop(1)?.collect { rebuildPendingPages() }
        }
    }

    private fun buildPages(): List<StepPage> {
        val config = sdk.requireConfig()
        val contentVariant = NativeTemplates.templateForPlacement(
            AdPlacement.StepNative(enabledStepIds.first()),
        ).name
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::pagerAdapter.isInitialized) {
            pagerAdapter.fragmentAt(binding.obStepPager.currentItem)?.onWindowTouched()
        }
        val detector = advanceFlingDetector
        if (detector != null) {
            // Decided at DOWN, not at fling: a gesture that started while the pager was still
            // animating into the last step would otherwise complete a page the user never saw.
            // A horizontally scrollable child under the finger keeps its own gesture.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureBeganOnRestingLastStep =
                    binding.obStepPager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                    binding.obStepPager.currentItem == enabledStepIds.size - 1 &&
                    !binding.obStepPager.pageHasHorizontallyScrollableViewUnder(ev.x, ev.y)
            }
            detector.observe(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The swipe counterpart of the last step's CTA. A fling whose own drag left the pager
     * mid-transition waits for it to settle, because [next] deliberately drops non-idle calls;
     * [exitResolved] is checked here only to skip dead work — [next] owns setting it.
     */
    private fun completeLastStepBySwipe() {
        if (exitResolved) return
        if (!gestureBeganOnRestingLastStep) return
        val last = enabledStepIds.size - 1
        if (binding.obStepPager.currentItem != last) return
        if (binding.obStepPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
            next(StepExit.SWIPE)
            return
        }
        binding.obStepPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state != ViewPager2.SCROLL_STATE_IDLE) return
                    binding.obStepPager.unregisterOnPageChangeCallback(this)
                    if (exitResolved) return
                    if (binding.obStepPager.currentItem != last) return
                    next(StepExit.SWIPE)
                }
            },
        )
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
        // variantKey is the native template the page was built with — without it a funnel
        // difference between two template buckets is unattributable
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
        // The scroll-state guard cannot catch a duplicate on the last step — the exit handoff
        // never scrolls the pager — so the last completion latches here, whichever of the CTA,
        // the advance fling, or an ad step's auto-next got in first.
        if (position >= enabledStepIds.size - 1) {
            if (exitResolved) return
            exitResolved = true
        }
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
            binding.obStepPager.setCurrentItem(
                position + 1,
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
        } else {
            resolveExit()
        }
    }

    /**
     * Completes an ad page on failure, Skip, or timeout. A callback may arrive while the
     * pager is settling onto that page, when [next] would drop it. Wait for idle and check
     * the source page again so a late answer cannot complete the following page instead.
     */
    override fun completeAdStep(stepId: StepId, exitReason: String) {
        val position = enabledStepIds.indexOf(stepId)
        if (position < 0) return
        if (binding.obStepPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
            if (binding.obStepPager.currentItem == position) next(exitReason)
            return
        }
        binding.obStepPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state != ViewPager2.SCROLL_STATE_IDLE) return
                    binding.obStepPager.unregisterOnPageChangeCallback(this)
                    // The user may have swiped on in the meantime; only complete the source page.
                    if (binding.obStepPager.currentItem == position) next(exitReason)
                }
            },
        )
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
        loadAndShowInterstitial(
            AdPlacement.AfterOnboardingInterstitial,
            timeoutMs = 8_000L,
            onFinished = {
                // The page can complete in the background. Android only permits the next
                // Activity/paywall presentation once this task is back in front.
                lifecycleScope.launch { lifecycle.withResumed { continueAfterOnboardingAd() } }
            },
        )
    }

    private fun continueAfterOnboardingAd() {
        if (isFinishing || isDestroyed) return
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val decision = FlowNavigator.decideExit(
            flags = sdk.flags(),
            config = config,
            hasReusableSplashInterstitial = false,
            isOb5NativeReady = provider?.isNativeReady(AdPlacement.Ob5) == true,
        )
        ObLog.d(ObLog.Section.NAV, "ob_onboarding exit_decision=$decision")
        when (decision) {
            ExitDecision.ShowReusedInterstitialThenComplete -> finishFlow(FinishReason.COMPLETED)

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
