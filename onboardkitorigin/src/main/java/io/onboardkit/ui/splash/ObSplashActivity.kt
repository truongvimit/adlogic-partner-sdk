package io.onboardkit.ui.splash

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.flow.StartDecision
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Splash template. The app's launcher activity extends this and overrides the hooks it needs.
 *
 * Four tasks run in parallel — consent, remote fetch, billing, ad load — every one behind a
 * timeout (the original hung forever on slow fetch/consent), then a barrier + a minimum
 * display time gate navigation. Navigation itself is CAS-guarded.
 */
open class ObSplashActivity : BaseOnboardActivity() {

    private val startedAtMs = System.currentTimeMillis()
    private val navigated = AtomicBoolean(false)
    private val interstitialGate = CompletableDeferred<Unit>()
    private var progressAnimator: ObjectAnimator? = null

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val cfg = sdk.requireConfig()
        val layout = if (cfg.splash.layoutRes != 0) cfg.splash.layoutRes else R.layout.ob_activity_splash
        setContentView(layout)
        bindDefaultViews()
        OnboardingSdk.emitEvent(OnboardingEvent.SplashViewed(0))

        lifecycleScope.launch { runSplashPipeline() }
    }

    private fun bindDefaultViews() {
        val cfg = sdk.requireConfig()
        findViewById<ImageView?>(R.id.ob_splash_logo)?.let { logo ->
            if (cfg.splash.logoRes != 0) logo.setImageResource(cfg.splash.logoRes)
            else logo.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
        }
        findViewById<TextView?>(R.id.ob_splash_app_name)?.let { name ->
            if (cfg.splash.appNameRes != 0) name.setText(cfg.splash.appNameRes)
            else name.text = applicationInfo.loadLabel(packageManager)
        }
        findViewById<ProgressBar?>(R.id.ob_splash_progress)?.let { bar ->
            progressAnimator = ObjectAnimator.ofInt(bar, "progress", 0, 100).apply {
                duration = cfg.splash.minDisplayTimeMs.coerceAtLeast(1_000)
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private suspend fun runSplashPipeline() {
        val cfg = sdk.requireConfig()

        withTimeoutOrNull(cfg.splash.consentTimeoutMs.milliseconds) { onConsentRequired() }

        if (cfg.splash.adLoadStrategy == AdLoadStrategy.SAME_TIME) startAdLoads()

        val remote = sdk.remoteOrNull()
        remote?.sync(cfg.splash.remoteFetchTimeoutMs)
        onRemoteFetched()
        // Preload only what the upcoming destination needs; a completed flow preloads nothing.
        val start = OnboardingSdk.shouldStart() as? StartDecision.Start
        sdk.preload().onSplashRemoteReady(this, start?.destination, start?.resumeStepIndex ?: 0)

        if (cfg.splash.adLoadStrategy == AdLoadStrategy.ALTERNATE) startAdLoads()

        withTimeoutOrNull(cfg.splash.billingTimeoutMs.milliseconds) { onInitBilling() }

        // Barrier: interstitial resolved (loaded/failed/absent) + minimum display time
        withTimeoutOrNull(INTERSTITIAL_WAIT_MS.milliseconds) { interstitialGate.await() }
        val minDisplay = sdk.flags().splashMinDisplayMs.takeIf { it > 0 }
            ?: cfg.splash.minDisplayTimeMs
        val elapsed = System.currentTimeMillis() - startedAtMs
        delay((minDisplay - elapsed).coerceIn(0, minDisplay).milliseconds)

        navigateOnce()
    }

    private fun startAdLoads() {
        val cfg = sdk.requireConfig()
        val provider = sdk.provider()
        if (provider == null) {
            interstitialGate.complete(Unit)
            return
        }

        if (cfg.ads.splashBanner != null && sdk.policy().canShowBanner(this)) {
            findViewById<FrameLayout?>(R.id.ob_splash_ad_container)?.visibility =
                android.view.View.VISIBLE
            provider.loadBanner(this, cfg.ads.splashBanner)
        }

        val interUnit = resolveSplashInterUnit(cfg.ads.splashInterstitial)
        if (interUnit == null ||
            !sdk.policy().canShowInterstitial(this, AdPlacement.SplashInterstitial)
        ) {
            interstitialGate.complete(Unit)
            return
        }
        provider.loadInterstitial(
            this,
            AdPlacement.SplashInterstitial,
            interUnit,
            object : AdEventListener {
                override fun onLoaded() {
                    interstitialGate.complete(Unit)
                }

                override fun onFailedToLoad() {
                    interstitialGate.complete(Unit)
                }
            },
        )
    }

    /** Remote id override beats the compile-time id, matching the original's splash-inter rule. */
    private fun resolveSplashInterUnit(compiled: InterstitialAdUnit?): InterstitialAdUnit? {
        val remoteId = sdk.flags().adsSplashInterId
        return when {
            remoteId.isNotBlank() -> InterstitialAdUnit(remoteId)
            else -> compiled
        }
    }

    private fun navigateOnce() {
        if (!navigated.compareAndSet(false, true)) return
        OnboardingSdk.track(
            AnalyticsEvent.SplashViewed(System.currentTimeMillis() - startedAtMs),
        )
        lifecycleScope.launch {
            val gate = sdk.paywall()
            if (gate != null && gate.shouldShow(PaywallPlacement.SPLASH_INTER)) {
                when (gate.present(this@ObSplashActivity, PaywallPlacement.SPLASH_INTER)) {
                    PaywallOutcome.Purchased -> {
                        proceedToFlow()
                        return@launch
                    }

                    PaywallOutcome.Dismissed, PaywallOutcome.ContinueWithAds -> Unit
                }
            }
            showSplashInterThenProceed()
        }
    }

    private fun showSplashInterThenProceed() {
        val provider = sdk.provider()
        if (provider == null || !provider.isInterstitialReady(AdPlacement.SplashInterstitial)) {
            proceedToFlow()
            return
        }
        provider.showInterstitial(this, AdPlacement.SplashInterstitial) { proceedToFlow() }
    }

    private fun proceedToFlow() {
        lifecycleScope.launch {
            OnboardingSdk.start(this@ObSplashActivity, StartOptions(passthrough = intent.extras))
            finish()
        }
    }

    override fun onDestroy() {
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDestroy()
    }

    // ── Hooks for the host app ──

    /** Run UMP/GDPR consent here; return when resolved. Hard timeout applies regardless. */
    protected open suspend fun onConsentRequired() {}

    /** Init billing/purchases; return when done. 5s hard timeout by default. */
    protected open suspend fun onInitBilling() {}

    /** Remote config has been fetched and synced — sync the app's own keys here. */
    protected open fun onRemoteFetched() {}

    private companion object {
        const val INTERSTITIAL_WAIT_MS = 30_000L
    }
}
