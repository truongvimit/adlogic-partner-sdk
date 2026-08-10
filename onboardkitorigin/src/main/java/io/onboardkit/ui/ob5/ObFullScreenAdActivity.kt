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
import io.onboardkit.config.NativeTemplate
import io.onboardkit.core.StepId
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityFullscreenAdBinding
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Standalone full-screen native (OB5). Uses its own OB5 ad pool, honors premium like every
 * other screen, and always auto-dismisses after a remote-configured timeout so nobody can be
 * trapped — three separate bugs of the original, fixed here.
 */
class ObFullScreenAdActivity : BaseOnboardActivity() {

    private lateinit var binding: ObActivityFullscreenAdBinding
    private var skipJob: Job? = null
    private var autoDismissJob: Job? = null
    private var adBound = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityFullscreenAdBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!sdk.policy().canShowNative(this, AdPlacement.Ob5)) {
            navigateNext()
            return
        }

        OnboardingSdk.session.recordStepShown(StepId.OB5)
        sdk.provider()?.suppressAppResume(javaClass)
        binding.obSkipButton.setOnClickListener { navigateNext() }

        requestAd()
        scheduleSkip()
        scheduleAutoDismiss()
    }

    private fun requestAd() {
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val unit = config.ads.ob5Native ?: config.ads.fullScreenStepNative
        if (provider == null || unit == null) {
            navigateNext()
            return
        }
        val listener = object : AdEventListener {
            override fun onLoaded() {
                runOnUiThread { bindAd() }
            }

            override fun onFailedToLoad() {
                runOnUiThread { showSkipNow() }
            }
        }
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
            navigateNext()
        }
    }

    private fun showSkipNow() {
        skipJob?.cancel()
        binding.obSkipButton.visibility = View.VISIBLE
    }

    private fun navigateNext() {
        skipJob?.cancel()
        autoDismissJob?.cancel()
        val config = sdk.requireConfig()
        if (sdk.flags().enableQuestion && config.question != null) {
            ObQuestionActivity.start(this, QuestionSource.NEW_USER)
            finish()
            return
        }
        lifecycleScope.launch {
            val gate = sdk.paywall()
            if (gate != null && gate.shouldShow(PaywallPlacement.AFTER_ONBOARDING)) {
                when (gate.present(this@ObFullScreenAdActivity, PaywallPlacement.AFTER_ONBOARDING)) {
                    PaywallOutcome.Purchased,
                    PaywallOutcome.Dismissed,
                    PaywallOutcome.ContinueWithAds,
                    -> Unit
                }
            }
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
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ObFullScreenAdActivity::class.java))
        }
    }
}
