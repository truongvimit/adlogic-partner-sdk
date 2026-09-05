package io.onboardkit.ui.ob5

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.flowAdListener
import io.onboardkit.ads.trackSkipped
import io.onboardkit.core.ObLog
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityFullscreenAdBinding
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone full-screen native (OB5), disabled by default and using its existing ready pool.
 * An empty pool exits when navigation is safe without requesting a replacement ad.
 * Skip defaults to 3 seconds; auto-dismiss defaults to 15 seconds and clamps to at least 5.
 * Both countdowns run only while RESUMED and restart in full after pause. Once unlocked, Skip
 * stays available across resume, while the current remote flag may hide it without resetting
 * that latch. Successful binding starts the hard-exit countdown, but only a real vendor impression
 * reports [OnboardingEvent.AdShown]. A paywall runs once independently of those countdowns.
 * If its eligibility decision or result arrives while paused, presentation telemetry and flow
 * completion wait for safe RESUMED navigation.
 */
class ObFullScreenAdActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_fullscreen_ad"

    private lateinit var binding: ObActivityFullscreenAdBinding
    private var skipJob: Job? = null
    private var autoDismissJob: Job? = null
    private var adBound = false
    private var skipUnlocked = false
    private var pendingExit: String? = null
    private var completionPending = false
    private var navigationWaiter: CompletableDeferred<Unit>? = null

    /** -1 until the step is counted; stays -1 on the policy-declined path, which shows nothing. */
    private var stepIndex = -1
    private var shownAtMs = 0L

    /** A skip tap racing the auto-dismiss timer used to launch the next screen twice. */
    private val navigated = AtomicBoolean(false)

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityFullscreenAdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.obSkipButton.setOnClickListener { requestExit(StepExit.SKIP) }

        sdk.guard().skipReason(this, AdPlacement.Ob5)?.let { reason ->
            AdPlacement.Ob5.trackSkipped(reason)
            requestExit(StepExit.SKIP)
            return
        }

        OnboardingSdk.session.recordStepShown(StepId.OB5)
        // OB5 lives outside the pager, so it never passes through the host's page-change and
        // next() hooks — the two places every other step is counted. Reported here instead, or it
        // would be the one step in the flow with a view but no completion, which is exactly the
        // hole the audited SDK had (`complete_ob5` never existed).
        stepIndex = OnboardingSdk.session.stepsShown.indexOf(StepId.OB5)
        shownAtMs = System.currentTimeMillis()
        OnboardingSdk.track(AnalyticsEvent.StepViewed(StepId.OB5, stepIndex, VARIANT))
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!::binding.isInitialized || !canNavigate()) return
        navigationWaiter?.let { waiter ->
            navigationWaiter = null
            waiter.complete(Unit)
        }
        if (pendingExit != null) {
            drainPendingExit()
            return
        }
        sdk.guard().skipReason(this, AdPlacement.Ob5)?.let { reason ->
            AdPlacement.Ob5.trackSkipped(reason)
            requestExit(StepExit.SKIP)
            return
        }
        // A successful bind consumes its pool entry. Readiness is relevant only before binding.
        if (!adBound) bindReadyAd()
        if (adBound && pendingExit == null && !navigated.get()) {
            scheduleSkip()
            scheduleAutoDismiss()
        }
    }

    private fun bindReadyAd() {
        val provider = sdk.provider()
        if (provider == null || !provider.isNativeReady(AdPlacement.Ob5)) {
            AdPlacement.Ob5.trackSkipped(if (provider == null) AdSkipReason.NO_PROVIDER else AdSkipReason.NOT_READY)
            requestExit(StepExit.AD_FAILED)
            return
        }
        val flowListener = flowAdListener(object : AdEventListener {
            override fun onImpression() {
                OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.Ob5.key))
            }
        })
        val listener = object : AdEventListener {
            // No load callback may bind again: this page only consumes the fill already ready.
            override fun onImpression() = whileActive { flowListener.onImpression() }
            override fun onClicked() = whileActive { flowListener.onClicked() }
            override fun onAdOpened() = whileActive { flowListener.onAdOpened() }
            override fun onFailedToLoad() = whileActive { requestExit(StepExit.AD_FAILED) }
        }
        val bound = provider.bindNative(
            this, AdPlacement.Ob5, binding.obNativeContainer, shimmer = null, listener = listener,
        )
        if (pendingExit != null || navigated.get() || isFinishing || isDestroyed) return
        if (bound) adBound = true else {
            AdPlacement.Ob5.trackSkipped(AdSkipReason.NOT_READY)
            requestExit(StepExit.AD_FAILED)
        }
    }

    private fun whileActive(action: () -> Unit) {
        runOnUiThread {
            if (pendingExit == null && !navigated.get() && !isFinishing && !isDestroyed) action()
        }
    }

    private fun scheduleSkip() {
        val flags = sdk.flags()
        if (!flags.showSkipOb5) {
            binding.obSkipButton.visibility = View.GONE
            return
        }
        if (skipUnlocked) {
            binding.obSkipButton.visibility = View.VISIBLE
            return
        }
        skipJob = lifecycleScope.launch {
            delay(flags.skipButtonDelaySec.coerceAtLeast(0) * 1_000)
            if (canNavigate() && pendingExit == null) {
                skipUnlocked = true
                binding.obSkipButton.visibility = View.VISIBLE
            }
        }
    }

    /** Hard exit: even with Skip hidden and no interaction, the screen closes itself. */
    private fun scheduleAutoDismiss() {
        val seconds = sdk.flags().fullScreenAutoDismissSec.coerceAtLeast(5)
        autoDismissJob = lifecycleScope.launch {
            delay(seconds * 1_000)
            requestExit(StepExit.AUTO_DISMISS)
        }
    }

    override fun onPause() {
        skipJob?.cancel()
        autoDismissJob?.cancel()
        super.onPause()
    }

    private fun canNavigate(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved

    private suspend fun awaitSafeNavigation() {
        while (!canNavigate()) {
            val waiter = CompletableDeferred<Unit>()
            navigationWaiter = waiter
            try {
                waiter.await()
            } finally {
                if (navigationWaiter === waiter) navigationWaiter = null
            }
        }
    }

    /** The first reason closes listener/bind admission immediately, even while navigation waits. */
    private fun requestExit(exitReason: String) {
        if (pendingExit != null || navigated.get()) return
        pendingExit = exitReason
        ObLog.d(ObLog.Section.NAV, "from=ob5 exit=$exitReason")
        skipJob?.cancel()
        autoDismissJob?.cancel()
        sdk.provider()?.releaseNative(AdPlacement.Ob5)
        drainPendingExit()
    }

    private fun drainPendingExit() {
        val exitReason = pendingExit ?: return
        if (!canNavigate()) return
        if (completionPending) {
            // Claim completion before notifying the host, which may synchronously navigate again.
            completionPending = false
            OnboardingSdk.completeFlow(this)
            finish()
            return
        }
        if (!navigated.compareAndSet(false, true)) return
        if (stepIndex >= 0) {
            OnboardingSdk.track(
                AnalyticsEvent.StepCompleted(
                    StepId.OB5,
                    stepIndex,
                    System.currentTimeMillis() - shownAtMs,
                    exitReason,
                ),
            )
        }
        val config = sdk.requireConfig()
        if (sdk.flags().enableQuestion && config.question != null) {
            ObQuestionActivity.start(this, QuestionSource.NEW_USER)
            finish()
            return
        }
        lifecycleScope.launch {
            // Outcome ignored: the flow completes either way
            sdk.presentPaywall(this@ObFullScreenAdActivity, PaywallPlacement.AFTER_ONBOARDING,
                beforePresent = { awaitSafeNavigation() })
            completionPending = true
            drainPendingExit()
        }
    }

    override fun onDestroy() {
        skipJob?.cancel()
        autoDismissJob?.cancel()
        sdk.provider()?.releaseNative(AdPlacement.Ob5)
        super.onDestroy()
    }

    companion object {
        /** Same variant key the pager stamps on its AD_FULL_SCREEN pages, so OB3 and OB5 compare. */
        private const val VARIANT = "fullscreen"

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ObFullScreenAdActivity::class.java))
        }
    }
}
