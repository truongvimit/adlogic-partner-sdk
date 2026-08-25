package io.onboardkit.ui.splash

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.ads.module.config.AdConfig
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.tracked
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.onboardkit.core.SkipReason
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Splash template. The app's launcher activity extends this and overrides the hooks it needs.
 *
 * The screen runs one linear sequence — consent, billing, remote, ad requests, minimum display —
 * and hands off exactly once at the end. Every step is bounded by its own timeout, so no stalled
 * hook can hold the app here, and nothing runs in parallel that could move the flow on while the
 * ad is still arriving.
 */
open class ObSplashActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_splash"

    private val startedAtMs = System.currentTimeMillis()
    private var progressAnimator: ObjectAnimator? = null

    /** Completed when the slot has an answer of any kind: filled, failed, or never requested. */
    private val bannerSettled = CompletableDeferred<Unit>()
    private val interstitialSettled = CompletableDeferred<Unit>()
    private var adsRequested = false

    /**
     * Resolved before the ads are awaited so the handoff under the ad needs no suspension point.
     * Null until the remote fetch lands — under [AdLoadStrategy.SAME_TIME] the ads start loading
     * before that, and treating "not yet known" as a segment would pick the wrong ad unit.
     */
    private var startDecision: StartDecision? = null

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val cfg = sdk.requireConfig()
        val layout = if (cfg.splash.layoutRes != 0) cfg.splash.layoutRes else R.layout.ob_activity_splash
        setContentView(layout)
        bindDefaultViews()

        ObLog.startFlow()
        ObLog.d(
            ObLog.Section.SPLASH,
            "config strategy=${cfg.splash.adLoadStrategy} minDisplayMs=${cfg.splash.minDisplayTimeMs} " +
                "consentMs=${cfg.splash.consentTimeoutMs} remoteMs=${cfg.splash.remoteFetchTimeoutMs} " +
                "billingMs=${cfg.splash.billingTimeoutMs}",
        )
        OnboardingSdk.emitEvent(OnboardingEvent.SplashViewed(0))
        OnboardingSdk.track(AnalyticsEvent.SplashViewed())

        lifecycleScope.launch { runSplash() }
    }

    private suspend fun runSplash() {
        val cfg = sdk.requireConfig()

        // The remote fetch requests no ads, so it may overlap consent; ad requests may not. A
        // request that goes out before the user has answered is a policy violation, not a race.
        coroutineScope {
            val consent = async { step("consent", cfg.splash.consentTimeoutMs) { onConsentRequired() } }
            val remote = async {
                step("remote_fetch", cfg.splash.remoteFetchTimeoutMs) {
                    sdk.remoteOrNull()?.sync(cfg.splash.remoteFetchTimeoutMs)
                    // Refresh the ad units in the same step. No-op unless the host installed an
                    // AdConfigSource, so an app that ships only assets/ad_config.json pays nothing.
                    AdConfig.refresh(cfg.splash.remoteFetchTimeoutMs)
                }
            }
            // A timeout leaves the form on screen unanswered — the one case where no ad may go out.
            // A refusal is not that case: it finishes the step and the ads run non-personalized.
            val mayRequestAds = consent.await() ?: false
            OnboardingSdk.setCanRequestAds(mayRequestAds)
            if (!mayRequestAds) {
                ObLog.w(ObLog.Section.SPLASH, "consent unanswered — running the flow without ads")
            }
            // Before any request: entitlement decides whether one is legitimate at all, and a
            // request made while it is still unknown reaches a paying user. Billing was started in
            // Application.onCreate, so this usually returns having waited on nothing.
            step("billing", cfg.splash.billingTimeoutMs) { onInitBilling() }
            // SAME_TIME spends what is left of the fetch window loading with the compiled ad ids;
            // ALTERNATE waits so that a remote id override can still apply.
            if (cfg.splash.adLoadStrategy == AdLoadStrategy.SAME_TIME) requestSplashAds()
            remote.await()
        }

        ObLog.d(ObLog.Section.REMOTE, sdk.flags().adSummary())
        onRemoteFetched()

        val decision = OnboardingSdk.shouldStart()
        startDecision = decision
        ObLog.d(ObLog.Section.SPLASH, "start_decision=${describe(decision)} returning=${isReturningUser()}")
        (decision as? StartDecision.Start)?.let {
            sdk.preload().onSplashRemoteReady(this, it.destination, it.resumeStepIndex)
        }

        requestSplashAds()

        awaitBanner()
        awaitInterstitial()

        val remaining = remainingMinDisplayMs(cfg.splash.minDisplayTimeMs)
        if (remaining > 0) {
            ObLog.d(ObLog.Section.SPLASH, "min_display waiting ${remaining}ms")
            delay(remaining.milliseconds)
        }

        proceed()
    }

    /** Runs [block] under [timeoutMs], logging both ends so a stalled hook is visible in logcat. */
    private suspend fun <T> step(name: String, timeoutMs: Long, block: suspend () -> T): T? {
        val startedAt = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs.milliseconds) { block() }
        val elapsed = System.currentTimeMillis() - startedAt
        if (result == null) {
            ObLog.w(ObLog.Section.SPLASH, "$name TIMEOUT after ${elapsed}ms (limit ${timeoutMs}ms)")
        } else {
            ObLog.d(ObLog.Section.SPLASH, "$name done ms=$elapsed")
        }
        return result
    }

    /** Fires both splash ad requests. Safe to call twice — only the first one does anything. */
    private fun requestSplashAds() {
        if (adsRequested) return
        adsRequested = true
        requestSplashBanner()
        requestSplashInterstitial()
    }

    private fun requestSplashBanner() {
        val placement = AdPlacement.SplashBanner
        val skip = sdk.guard().skipReason(this, placement)
        val unit = sdk.requireConfig().ads.splashBanner
        val provider = sdk.provider()
        if (skip != null || unit == null || provider == null) {
            skip?.let { placement.trackSkipped(it) }
            bannerSettled.complete(Unit)
            return
        }

        val container = findViewById<FrameLayout?>(R.id.ob_splash_ad_container)
        container?.visibility = View.VISIBLE
        if (container == null) {
            ObLog.w(
                ObLog.Section.LOAD,
                "${placement.key} container missing (ob_splash_ad_container) — " +
                    "the ads module needs banner_container in the splash layout",
            )
        }
        ObLog.d(ObLog.Section.LOAD, "${placement.key} request")
        placement.trackRequest()
        provider.loadBanner(
            this,
            unit,
            placement.tracked(
                object : AdEventListener {
                    override fun onLoaded() {
                        ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                        bannerSettled.complete(Unit)
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed")
                        bannerSettled.complete(Unit)
                    }
                },
            ),
        )
    }

    private fun requestSplashInterstitial() {
        val placement = AdPlacement.SplashInterstitial
        // Resolved before the guard on purpose: a remote id override is invisible in the compiled
        // config, so judging that alone would report no_ad_unit for a live placement.
        val unit = resolveSplashInterUnit()
        val skip = sdk.guard().skipReason(this, placement, unit)
        val provider = sdk.provider()
        if (skip != null || unit == null || provider == null) {
            placement.trackSkipped(skip ?: AdSkipReason.NO_AD_UNIT)
            interstitialSettled.complete(Unit)
            return
        }

        ObLog.d(
            ObLog.Section.LOAD,
            "${placement.key} request tiers=${unit.tierCount} budgetMs=${sdk.flags().splashAdBudgetMs}",
        )
        placement.trackRequest()
        provider.loadInterstitial(
            this,
            placement,
            unit,
            placement.tracked(
                object : AdEventListener {
                    override fun onLoaded() {
                        ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                        interstitialSettled.complete(Unit)
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed — all tiers")
                        interstitialSettled.complete(Unit)
                    }
                },
            ),
        )
    }

    /**
     * `ob_splash_banner_wait_ms` defaults to `0`: request the banner and move on. Raise it when
     * the banner impression is worth delaying the interstitial that will cover it.
     */
    private suspend fun awaitBanner() {
        val waitMs = sdk.flags().splashBannerWaitMs
        if (waitMs <= 0) return
        withTimeoutOrNull(waitMs.milliseconds) { bannerSettled.await() }
    }

    /**
     * The budget is the only thing that can move the splash on while a load is still running, and
     * it is remote-tunable (`ob_splash_ad_budget_ms`) rather than a constant nobody can reach.
     */
    private suspend fun awaitInterstitial() {
        val budgetMs = sdk.flags().splashAdBudgetMs
        val settled = withTimeoutOrNull(budgetMs.milliseconds) { interstitialSettled.await() } != null
        val ready = sdk.provider()?.isInterstitialReady(AdPlacement.SplashInterstitial) == true
        if (!settled) {
            ObLog.w(ObLog.Section.LOAD, "splash_inter BUDGET_EXPIRED budgetMs=$budgetMs ready=$ready")
        }
        if (!ready) AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.NO_FILL)
    }

    /**
     * A remote id override beats the compiled id, split by user segment.
     *
     * A returning user is worth a different floor than a first-open one. When the segment is not
     * resolved yet — `SAME_TIME` loads before the fetch lands — the shared override applies rather
     * than the old-user one, because guessing the segment would spend the wrong floor.
     */
    private fun resolveSplashInterUnit(): InterstitialAdUnit? {
        val ads = sdk.requireConfig().ads
        return if (isReturningUser()) {
            ads.splashInterstitialOldUser ?: ads.splashInterstitial
        } else {
            ads.splashInterstitial
        }
    }

    private fun isReturningUser(): Boolean = when (val decision = startDecision) {
        null -> false
        is StartDecision.Skip -> true
        is StartDecision.Start -> decision.destination == FlowDestination.QUESTION_OLD_USER
    }

    private suspend fun proceed() {
        OnboardingSdk.track(AnalyticsEvent.SplashCompleted(System.currentTimeMillis() - startedAtMs))

        // A purchase here removes the reason to show the interstitial at all
        if (sdk.presentPaywall(this, PaywallPlacement.SPLASH_INTER) == PaywallOutcome.Purchased) {
            ObLog.d(ObLog.Section.SPLASH, "paywall purchased — skipping splash interstitial")
            AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PURCHASED_AT_PAYWALL)
            startFlow()
            finish()
            return
        }
        // The destination starts while the ad is on screen; this activity is finished only once
        // the ad is gone, which is what keeps the ad alive long enough to be seen.
        showInterstitial(
            AdPlacement.SplashInterstitial,
            onNext = { startFlow() },
            onFinished = { finish() },
        )
    }

    private fun startFlow() {
        val decision = startDecision ?: StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)
        ObLog.d(ObLog.Section.NAV, "ob_splash -> ${describe(decision)}")
        OnboardingSdk.startResolved(this, decision, StartOptions(passthrough = intent.extras))
    }

    private fun describe(decision: StartDecision): String = when (decision) {
        is StartDecision.Start -> "START dest=${decision.destination} resumeIndex=${decision.resumeStepIndex}"
        is StartDecision.Skip -> "SKIP reason=${decision.reason}"
    }

    private fun remainingMinDisplayMs(configured: Long): Long {
        val target = sdk.flags().splashMinDisplayMs.takeIf { it > 0 } ?: configured
        val elapsed = System.currentTimeMillis() - startedAtMs
        return (target - elapsed).coerceIn(0, target)
    }

    private fun bindDefaultViews() {
        val cfg = sdk.requireConfig()
        findViewById<ImageView?>(R.id.ob_splash_logo)?.let { logo ->
            if (cfg.splash.logoRes != 0) {
                logo.setImageResource(cfg.splash.logoRes)
            } else {
                logo.setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
            }
        }
        findViewById<TextView?>(R.id.ob_splash_app_name)?.let { name ->
            if (cfg.splash.appNameRes != 0) {
                name.setText(cfg.splash.appNameRes)
            } else {
                name.text = applicationInfo.loadLabel(packageManager)
            }
        }
        findViewById<ProgressBar?>(R.id.ob_splash_progress)?.let { bar ->
            progressAnimator = ObjectAnimator.ofInt(bar, "progress", 0, 100).apply {
                duration = cfg.splash.minDisplayTimeMs.coerceAtLeast(1_000)
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    override fun onDestroy() {
        progressAnimator?.cancel()
        progressAnimator = null
        // The consent timeout holds this screen's completion callback for its whole window; the
        // flow it guards died with the screen.
        ConsentCenter.detach(this)
        super.onDestroy()
    }

    /**
     * Whether ads may now be requested. Runs the SDK's own UMP flow by default, so an app that
     * wants standard GDPR behaviour writes nothing.
     *
     * This is "has the consent step finished", not "did the user agree". A refusal returns `true`:
     * the user still gets ads, non-personalized. `false` means the form is still unanswered on
     * screen, so the flow runs without ads rather than placing one underneath it. Override and
     * return `true` for an app that has no consent step at all.
     */
    protected open suspend fun onConsentRequired(): Boolean =
        suspendCancellableCoroutine { continuation ->
            ConsentCenter.request(this, screen = "splash") { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }

    /**
     * Resolve the purchase entitlement; return as soon as it is known. 5s hard timeout by default.
     *
     * Runs before the first ad request, because [io.onboardkit.ads.AdsGuard] reads the entitlement
     * to decide whether a request may go out at all. Do only that here — anything slower belongs in
     * [onRemoteFetched] or a background coroutine, or it delays every ad on the splash.
     */
    protected open suspend fun onInitBilling() {}

    /** Remote config has been fetched and synced — sync the app's own keys here. */
    protected open fun onRemoteFetched() {}
}
