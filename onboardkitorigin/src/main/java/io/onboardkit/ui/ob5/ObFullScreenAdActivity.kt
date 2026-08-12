package io.onboardkit.ui.ob5

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.tracked
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.config.NativeTemplate
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AdSkipReason
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.analytics.StepExit
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityFullscreenAdBinding
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import io.trackkit.AdFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone full-screen native (OB5). Uses its own OB5 ad pool, honors premium like every
 * other screen, and always auto-dismisses after a remote-configured timeout so nobody can be
 * trapped — three separate bugs of the original, fixed here.
 */
class ObFullScreenAdActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_fullscreen_ad"

    private lateinit var binding: ObActivityFullscreenAdBinding
    private var skipJob: Job? = null
    private var autoDismissJob: Job? = null
    private var adBound = false

    /** -1 until the step is counted; stays -1 on the policy-declined path, which shows nothing. */
    private var stepIndex = -1
    private var shownAtMs = 0L

    /** A skip tap racing the auto-dismiss timer used to launch the next screen twice. */
    private val navigated = AtomicBoolean(false)

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityFullscreenAdBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!sdk.policy().canShowNative(this, AdPlacement.Ob5)) {
            AdPlacement.Ob5.trackSkipped(AdFormat.NATIVE_FULL_SCREEN, AdSkipReason.POLICY)
            navigateNext(StepExit.SKIP)
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

        sdk.provider()?.suppressAppResume(javaClass)
        binding.obSkipButton.setOnClickListener { navigateNext(StepExit.SKIP) }

        requestAd()
        scheduleSkip()
        scheduleAutoDismiss()
    }

    private fun requestAd() {
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val unit = config.ads.ob5Native ?: config.ads.fullScreenStepNative
        if (provider == null || unit == null) {
            AdPlacement.Ob5.trackSkipped(AdFormat.NATIVE_FULL_SCREEN, AdSkipReason.NO_UNIT)
            navigateNext(StepExit.AD_FAILED)
            return
        }
        val listener = AdPlacement.Ob5.tracked(
            AdFormat.NATIVE_FULL_SCREEN,
            object : AdEventListener {
                override fun onLoaded() {
                    runOnUiThread { bindAd() }
                }

                override fun onFailedToLoad() {
                    runOnUiThread { showSkipNow() }
                }
            },
        )
        AdPlacement.Ob5.trackRequest(AdFormat.NATIVE_FULL_SCREEN)
        if (!bindAd(listener)) {
            provider.preloadNative(
                this,
                NativeAdRequest(
                    AdPlacement.Ob5,
                    unit,
                    NativeTemplates.layoutFor(NativeTemplate.FULL_SCREEN),
                ),
            )
        }
    }

    private fun bindAd(listener: AdEventListener? = null): Boolean {
        if (adBound) return true
        val bound = sdk.provider()?.bindNative(
            this,
            AdPlacement.Ob5,
            binding.obNativeContainer,
            binding.obNativeShimmer.root,
            listener,
        ) == true
        if (bound) {
            adBound = true
            // The ad_show event itself is latched inside the tracked listener, which bindNative
            // notifies; only the SDK-facing event bus is fed here.
            OnboardingSdk.emitEvent(OnboardingEvent.AdShown(AdPlacement.Ob5.key))
        }
        return bound
    }

    private fun scheduleSkip() {
        val flags = sdk.flags()
        if (!flags.showSkipOb5) {
            // Auto-dismiss still guarantees an exit; keep Skip hidden as remote asked
            return
        }
        skipJob = lifecycleScope.launch {
            delay(flags.skipButtonDelaySec.coerceAtLeast(0) * 1_000)
            binding.obSkipButton.visibility = View.VISIBLE
        }
    }

    /** Hard exit: even with Skip hidden and no interaction, the screen closes itself. */
    private fun scheduleAutoDismiss() {
        val seconds = sdk.flags().fullScreenAutoDismissSec.coerceAtLeast(5)
        autoDismissJob = lifecycleScope.launch {
            delay(seconds * 1_000)
            navigateNext(StepExit.AUTO_DISMISS)
        }
    }

    private fun showSkipNow() {
        skipJob?.cancel()
        binding.obSkipButton.visibility = View.VISIBLE
    }

    private fun navigateNext(exitReason: String) {
        if (!navigated.compareAndSet(false, true)) return
        skipJob?.cancel()
        autoDismissJob?.cancel()
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
            sdk.presentPaywall(this@ObFullScreenAdActivity, PaywallPlacement.AFTER_ONBOARDING)
            OnboardingSdk.completeFlow(this@ObFullScreenAdActivity)
            finish()
        }
    }

    override fun onDestroy() {
        skipJob?.cancel()
        autoDismissJob?.cancel()
        sdk.provider()?.allowAppResume(javaClass)
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
