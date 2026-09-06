package io.onboardkit.ui.splash

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ads.module.config.AdConfig
import com.ads.module.config.AdRemoteConfig
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.tracked
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.core.ObLog
import io.onboardkit.core.SkipReason
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.net.ObNetwork
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Splash template. The app's launcher activity extends this and overrides the hooks it needs.
 *
 * Consent resolves before the optional notification prompt. Billing, remote config and ad loading
 * keep their existing ordering; ad loading may overlap that prompt. Handoff waits for both the ad
 * barriers and the permission result, so show/navigation do not run under system permission UI.
 * System permission UI waits for the user; network and host hooks keep their own timeouts.
 */
open class ObSplashActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_splash"

    private var attemptStartedAtMs = System.currentTimeMillis()
    private var progressAnimator: ObjectAnimator? = null
    private var noInternetDialog: ObNoInternetDialog? = null
    private val windowFocused = MutableStateFlow(false)

    private var notificationPermissionRequested = false
    private val notificationPermissionResult = CompletableDeferred<Unit>()
    // Unconditional registration lets AndroidX deliver a pending result to a recreated owner.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        getSharedPreferences("ob_splash_permissions", MODE_PRIVATE).edit()
            .putBoolean("notification_handled", true).apply()
        ObLog.d(ObLog.Section.SPLASH, "notification permission result granted=$granted")
        notificationPermissionResult.complete(Unit)
    }

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
        notificationPermissionRequested = savedInstanceState?.getBoolean("ob_notification_requested") == true
        if (savedInstanceState?.getBoolean("ob_notification_finished") == true) {
            notificationPermissionResult.complete(Unit)
        }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused.value = hasFocus
    }

    /**
     * Holds the splash until the device can actually reach the internet, so the flow's first ad
     * request is not spent on a dead connection.
     *
     * Nothing has latched at this point — no ad requested, no consent asked, no decision taken —
     * so this is a late start rather than a re-run, and everything downstream is untouched.
     *
     * The wait ends on window focus, never on resume alone. On Android 13+ the connectivity
     * panel is a system dialog floating over a splash that stays RESUMED underneath it, so a
     * lifecycle-only gate would let the interstitial play under that dialog.
     */
    private suspend fun awaitNetworkGate(cfg: OnboardKitConfig) {
        if (!cfg.splash.noInternetPromptEnabled) return
        if (ObNetwork.isValidated(this)) return

        ObLog.w(ObLog.Section.SPLASH, "no validated internet — holding the splash")
        val prompt = ObNoInternetDialog(this, onRetry = ::openConnectivitySettings)
        prompt.show()
        // The flow cannot run offline, so this waits as long as it takes: consent, the remote
        // fetch and every ad request are all still ahead of it.
        ObNetwork.awaitValidated(this)
        prompt.dismiss()
        // Not onResume: on Android 13+ the connectivity dialog floats over a splash that stays
        // RESUMED underneath it, and the interstitial would play under that dialog.
        windowFocused.first { it }
        attemptStartedAtMs = System.currentTimeMillis()
        ObLog.d(ObLog.Section.SPLASH, "network gate released")
    }

    private fun openConnectivitySettings() {
        val destinations = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in destinations) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        ObLog.w(ObLog.Section.SPLASH, "no connectivity settings destination resolved")
    }

    private suspend fun runSplash() = coroutineScope {
        val cfg = sdk.requireConfig()
        awaitNetworkGate(cfg)
        // A recreated owner must receive the outstanding OS result before opening UMP again.
        if (notificationPermissionRequested) {
            notificationPermissionResult.await()
            awaitSplashFocus()
        }
        val notification = async(start = CoroutineStart.LAZY) { awaitNotificationPermission(cfg) }

        // The remote fetch requests no ads, so it may overlap consent; ad requests may not. A
        // request that goes out before the user has answered is a policy violation, not a race.
        coroutineScope {
            val consent = async {
                awaitConsentAnswer(
                    roundTripMs = cfg.splash.consentTimeoutMs,
                    isResolving = ConsentCenter::isResolving,
                    canRequestAds = ConsentCenter::canRequestAds,
                    isVisible = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
                    request = ::onConsentRequired,
                )
            }
            val remote = async {
                step("remote_fetch", cfg.splash.remoteFetchTimeoutMs) {
                    sdk.remoteOrNull()?.sync(cfg.splash.remoteFetchTimeoutMs)
                    // Refresh the ad units in the same step. No-op unless the host installed an
                    // AdConfigSource, so an app that ships only assets/ad_config.json pays nothing.
                    AdConfig.refresh(cfg.splash.remoteFetchTimeoutMs)
                }
            }
            // Completing the step and authorizing requests are separate. The SDK reads current
            // authority at each gate, so a later answer can recover without overwriting host-off.
            val mayRequestAds = consent.await()
            notification.start()
            if (!mayRequestAds) {
                ObLog.w(ObLog.Section.SPLASH, "consent has not authorized requests — running the flow without ads")
            }
            // Before any request: entitlement decides whether one is legitimate at all, and a
            // request made while it is still unknown reaches a paying user. Billing was started in
            // Application.onCreate, so this usually returns having waited on nothing.
            step("billing", cfg.splash.billingTimeoutMs) { onInitBilling() }
            // SAME_TIME spends what is left of the fetch window loading with the compiled ad ids;
            // ALTERNATE waits so that a remote id override can still apply.
            if (cfg.splash.adLoadStrategy == AdLoadStrategy.SAME_TIME) {
                requestSplashAds(deferIfUnauthorized = true)
            }
            remote.await()
        }

        ObLog.d(ObLog.Section.REMOTE, sdk.flags().adSummary())
        onRemoteFetched()

        val decision = OnboardingSdk.shouldStart()
        startDecision = decision
        ObLog.d(ObLog.Section.SPLASH, "start_decision=${describe(decision)} returning=${isReturningUser()}")
        (decision as? StartDecision.Start)?.let {
            sdk.preload().onSplashRemoteReady(this@ObSplashActivity, it.destination, it.resumeStepIndex)
        }

        requestSplashAds()

        awaitBanner()
        awaitInterstitial()

        val remaining = remainingMinDisplayMs(cfg.splash.minDisplayTimeMs)
        if (remaining > 0) {
            ObLog.d(ObLog.Section.SPLASH, "min_display waiting ${remaining}ms")
            delay(remaining.milliseconds)
        }

        notification.await()
        if (notificationPermissionRequested) awaitSplashFocus()
        proceed()
    }

    private suspend fun awaitNotificationPermission(cfg: OnboardKitConfig) {
        if (notificationPermissionRequested) {
            notificationPermissionResult.await()
            awaitSplashFocus()
            return
        }
        if (!cfg.splash.notificationPermissionEnabled || Build.VERSION.SDK_INT < 33 ||
            applicationInfo.targetSdkVersion < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED ||
            getSharedPreferences("ob_splash_permissions", MODE_PRIVATE)
                .getBoolean("notification_handled", false)
        ) return
        // Hosts that do not send notifications may remove the merged declaration entirely.
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        if (!declared) return

        // Consent's callback can arrive before its window disappears. Home also loses focus.
        awaitSplashFocus()
        notificationPermissionRequested = true
        try {
            ObLog.d(ObLog.Section.SPLASH, "requesting notification permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (error: RuntimeException) {
            ObLog.w(ObLog.Section.SPLASH, "notification permission unavailable: ${error.message}")
            notificationPermissionResult.complete(Unit)
        }
        // A refusal or swipe-away settles this step just like Allow; it never gates ads consent.
        notificationPermissionResult.await()
        awaitSplashFocus()
    }

    private suspend fun awaitSplashFocus() {
        combine(lifecycle.currentStateFlow, windowFocused) { state, focused ->
            state.isAtLeast(Lifecycle.State.RESUMED) && focused
        }.first { it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ob_notification_requested", notificationPermissionRequested)
        outState.putBoolean("ob_notification_finished", notificationPermissionResult.isCompleted)
        super.onSaveInstanceState(outState)
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

    /**
     * Fires both slots at most once. SAME_TIME may defer a closed consent/host gate without
     * consuming the latch; the final call after onRemoteFetched still settles every declined slot.
     */
    private fun requestSplashAds(deferIfUnauthorized: Boolean = false) {
        if (adsRequested) return
        if (deferIfUnauthorized && !OnboardingSdk.canRequestAds()) return
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
     * The entry's own unit beats everything; below that, a remote id override beats the compiled
     * id, split by user segment.
     *
     * A returning user is worth a different floor than a first-open one. When the segment is not
     * resolved yet — `SAME_TIME` loads before the fetch lands — the shared override applies rather
     * than the old-user one, because guessing the segment would spend the wrong floor.
     */
    private fun resolveSplashInterUnit(): InterstitialAdUnit? {
        splashInterstitialOverride()?.let {
            ObLog.d(ObLog.Section.LOAD, "splash_inter unit chosen by the entry tiers=${it.tierCount}")
            return it
        }
        val ads = sdk.requireConfig().ads
        return if (isReturningUser()) {
            ads.splashInterstitialOldUser ?: ads.splashInterstitial
        } else {
            ads.splashInterstitial
        }
    }

    /**
     * The interstitial ad unit this launch spends, or `null` to keep the configured splash units.
     *
     * The default resolves [SplashEntry]: a launch that came through one spends its entry's key —
     * `inter_noti`, `inter_widget`, `inter_uninstall` — full `_high…` waterfall included, and a
     * key that is missing or disabled falls back to the regular resolution rather than silencing
     * the ad. The standard entries therefore need no code in the app at all; override only for an
     * app that diverges — different keys, or its own per-entry segmentation.
     *
     * Asked once per launch, when the request is about to go out, so it can read `intent` — the
     * same contract as [nextScreenTiming]. Only the ids change: the guard, the budget, the paywall
     * checkpoint and the telemetry keep judging the same `splash_inter` placement. Returning
     * non-null replaces the whole resolution, including the returning-user split — an entry
     * specific enough to carry its own id owns its own segmentation.
     */
    protected open fun splashInterstitialOverride(): InterstitialAdUnit? =
        SplashEntry.from(intent)?.let { entry ->
            AdRemoteConfig.getInstance().tiersFor(entry.interKey)
                .takeIf { it.isNotEmpty() }
                ?.let { InterstitialAdUnit(tiers = it) }
        }

    private fun isReturningUser(): Boolean = when (val decision = startDecision) {
        null -> false
        is StartDecision.Skip -> true
        is StartDecision.Start -> decision.destination == FlowDestination.QUESTION_OLD_USER
    }

    private suspend fun proceed() {
        OnboardingSdk.track(AnalyticsEvent.SplashCompleted(System.currentTimeMillis() - attemptStartedAtMs))

        // A purchase here removes the reason to show the interstitial at all
        if (sdk.presentPaywall(this, PaywallPlacement.SPLASH_INTER) == PaywallOutcome.Purchased) {
            ObLog.d(ObLog.Section.SPLASH, "paywall purchased — skipping splash interstitial")
            AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PURCHASED_AT_PAYWALL)
            startFlow()
            finish()
            return
        }
        // This activity is finished only once the ad is gone, whichever timing started the flow —
        // that is what keeps the ad alive long enough to be seen.
        val timing = nextScreenTiming()
        ObLog.d(ObLog.Section.SPLASH, "next screen timing=$timing")
        showInterstitial(
            AdPlacement.SplashInterstitial,
            onNext = { if (timing == NextScreenTiming.UNDER_AD) startFlow() },
            onFinished = {
                if (timing == NextScreenTiming.AFTER_AD) startFlow()
                finish()
            },
        )
    }

    /**
     * Whether the destination this launch hands off to may exist behind the splash interstitial.
     *
     * Asked once per launch, immediately before the ad is shown, so it can read `intent` — which
     * is the point: the answer belongs to the entry that chose the destination, never to the app
     * as a whole, which is why it is a hook here rather than a field on `SplashConfig`.
     *
     * The default answers it from [SplashEntry]: a launch that came through one names a feature
     * or screen to open, and `AFTER_AD` is always safe there — it only gives up the head start.
     * A launcher tap keeps `UNDER_AD`. Override for a finer split, e.g. a notification action
     * whose destination is where the user stays.
     */
    protected open fun nextScreenTiming(): NextScreenTiming =
        if (SplashEntry.from(intent) != null) NextScreenTiming.AFTER_AD else NextScreenTiming.UNDER_AD

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
        val elapsed = System.currentTimeMillis() - attemptStartedAtMs
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
     * Returns current request authorization, independently of personalization. UMP can permit
     * non-personalized requests after a refusal; a timeout cannot grant permission by itself.
     *
     * For a custom CMP, publish both decisions through [ConsentCenter.setHostConsent] before
     * returning. Returning `true` alone does not grant permission. The default UMP flow needs no
     * host wiring, and a host's explicit `OnboardingSdk.setCanRequestAds(false)` remains in force.
     *
     * The splash gives the default all the time it needs: `consentTimeoutMs` bounds only the round
     * trip, and once the form is up the wait is the user's — up to a three-minute backstop for a
     * form UMP has stopped answering for, which only runs while the screen is in front of them.
     * An override that resolves consent
     * without going through `ConsentCenter` has no flow for the splash to see, so that one is still
     * bounded by `consentTimeoutMs` — resolve promptly, or run the slow part elsewhere.
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
