package io.onboardkit.ui.splash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
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
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.ads.tracked
import io.onboardkit.config.AdLoadStrategy
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.core.ObLog
import io.onboardkit.core.SkipReason
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.core.net.ObNetwork
import io.onboardkit.flow.FlowDestination
import io.onboardkit.flow.StartDecision
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.splash.SplashAttempt.InterResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Splash template. The app's launcher activity extends this and overrides the hooks it needs.
 *
 * Consent resolves before the optional notification prompt. Authorized requests and the minimum
 * display clock may run beneath that prompt while splash is visible. The shared ad wait budget
 * starts only after the notification result and foreground focus; LFO1 follows the captured mode.
 * System permission UI waits for the user; network and host hooks keep their own timeouts.
 */
open class ObSplashActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_splash"

    private val attempt by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[SplashAttempt::class.java]
    }
    private var noInternetDialog: ObNoInternetDialog? = null
    private val windowFocused = MutableStateFlow(false)

    // Unconditional registration lets AndroidX deliver a pending result to a recreated owner.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        getSharedPreferences("ob_splash_permissions", MODE_PRIVATE).edit()
            .putBoolean("notification_handled", true).apply()
        ObLog.d(ObLog.Section.SPLASH, "notification permission result granted=$granted")
        attempt.notificationAnswered()
    }

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val cfg = sdk.requireConfig()
        val layout = if (cfg.splash.layoutRes != 0) cfg.splash.layoutRes else R.layout.ob_activity_splash
        setContentView(layout)
        bindDefaultViews()

        if (!attempt.announced) {
            attempt.announced = true
            sdk.preload().beginSplashAttempt(attempt.id)
            ObLog.startFlow()
            ObLog.d(
                ObLog.Section.SPLASH,
                "config strategy=${cfg.splash.adLoadStrategy} minDisplayMs=${cfg.splash.minDisplayTimeMs} " +
                    "consentMs=${cfg.splash.consentTimeoutMs} remoteMs=${cfg.splash.remoteFetchTimeoutMs} " +
                    "billingMs=${cfg.splash.billingTimeoutMs}",
            )
            OnboardingSdk.emitEvent(OnboardingEvent.SplashViewed(0))
            OnboardingSdk.track(AnalyticsEvent.SplashViewed())
        }

        lifecycleScope.launch { runSplash() }
    }

    override fun onResume() {
        super.onResume()
        // Android can preserve the focused window during recreation without replaying
        // onWindowFocusChanged(true) to the new Activity instance.
        windowFocused.value = hasWindowFocus()
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
        attempt.startedAtMs = System.currentTimeMillis()
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
        if (attempt.completed) {
            finish()
            return@coroutineScope
        }
        if (attempt.showRequested) {
            proceed()
            return@coroutineScope
        }
        val cfg = sdk.requireConfig()
        awaitNetworkGate(cfg)
        // The remote fetch requests no ads, so it may overlap consent; ad requests may not. A
        // request that goes out before the user has answered is a policy violation, not a race.
        coroutineScope {
            val consent = async {
                attempt.consentAnswered ?: awaitConsentAnswer(
                    roundTripMs = cfg.splash.consentTimeoutMs,
                    isResolving = ConsentCenter::isResolving,
                    canRequestAds = ConsentCenter::canRequestAds,
                    request = ::onConsentRequired,
                ).also { attempt.consentAnswered = it }
            }
            val remote = async {
                if (!attempt.remoteResolved) step("remote_fetch", cfg.splash.remoteFetchTimeoutMs) {
                    sdk.remoteOrNull()?.sync(cfg.splash.remoteFetchTimeoutMs)
                    // Refresh the ad units in the same step. No-op unless the host installed an
                    // AdConfigSource, so an app that ships only assets/ad_config.json pays nothing.
                    AdConfig.refresh(cfg.splash.remoteFetchTimeoutMs)
                }.also { attempt.remoteResolved = true }
            }
            val billing = async {
                if (!attempt.billingResolved) step("billing", cfg.splash.billingTimeoutMs) { onInitBilling() }
                    .also { attempt.billingResolved = true }
            }
            // Completing the step and authorizing requests are separate. The SDK reads current
            // authority at each gate, so a later answer can recover without overwriting host-off.
            val mayRequestAds = consent.await()
            val notification = async { awaitNotificationPermission(cfg) }
            if (!mayRequestAds) {
                ObLog.w(ObLog.Section.SPLASH, "consent has not authorized requests — running the flow without ads")
            }
            // Before any request: entitlement decides whether one is legitimate at all, and a
            // request made while it is still unknown reaches a paying user. Billing was started in
            // Application.onCreate, so this usually returns having waited on nothing.
            billing.await()
            // SAME_TIME spends what is left of the fetch window loading with the compiled ad ids;
            // ALTERNATE waits so that a remote id override can still apply.
            if (cfg.splash.adLoadStrategy == AdLoadStrategy.SAME_TIME) {
                requestSplashAds(deferIfUnauthorized = true)
            }
            remote.await()
            if (!attempt.remoteHookResolved) {
                onRemoteFetched()
                attempt.remoteHookResolved = true
                attempt.flags = sdk.flags()
                ObLog.d(ObLog.Section.REMOTE, "attempt=${attempt.id} ${checkNotNull(attempt.flags).adSummary()}")
            }
            if (attempt.startDecision == null) attempt.startDecision = OnboardingSdk.shouldStart()
            ObLog.d(ObLog.Section.SPLASH, "start_decision=${describe(checkNotNull(attempt.startDecision))} returning=${isReturningUser()}")
            requestSplashAds()
            lifecycleScope.launch {
                val reason = if (checkNotNull(attempt.flags).splashLfoParallelPreloadEnabled) "parallel_start"
                    else attempt.interstitialSettled.await().lfoReason
                ensureLfo1Preload(reason)
            }
            notification.await()
        }

        awaitSplashFocus()
        beginAdWait()
        awaitBanner()
        awaitInterstitial()
        ensureLfo1Preload(attempt.interstitialSettled.await().lfoReason)
        proceed()
    }

    private suspend fun ensureLfo1Preload(reason: String) {
        if (attempt.lfo1Scheduled || (attempt.startDecision as? StartDecision.Start)?.destination != FlowDestination.LANGUAGE) return
        awaitRequestWindow()
        if (attempt.lfo1Scheduled) return
        attempt.lfo1Scheduled = true
        ObLog.d(ObLog.Section.PRELOAD, "splash_lfo attempt=${attempt.id} mode=${if (checkNotNull(attempt.flags).splashLfoParallelPreloadEnabled) "parallel" else "sequential"} reason=$reason")
        sdk.preload().preloadLanguage1(this, allowWhileVisible = attempt.notificationOpen.value)
    }

    private suspend fun awaitNotificationPermission(cfg: OnboardKitConfig) {
        if (attempt.notificationPermissionRequested) {
            attempt.notificationPermissionResult.await()
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
        attempt.notificationPermissionRequested = true
        attempt.notificationOpen.value = true
        try {
            ObLog.d(ObLog.Section.SPLASH, "requesting notification permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (error: RuntimeException) {
            ObLog.w(ObLog.Section.SPLASH, "notification permission unavailable: ${error.message}")
            attempt.notificationAnswered()
        }
        // A refusal or swipe-away settles this step just like Allow; it never gates ads consent.
        attempt.notificationPermissionResult.await()
        awaitSplashFocus()
    }

    private suspend fun awaitSplashFocus() {
        combine(lifecycle.currentStateFlow, windowFocused) { state, focused ->
            !isFinishing && !isDestroyed && state.isAtLeast(Lifecycle.State.RESUMED) && focused
        }.first { it }
    }

    private suspend fun awaitRequestWindow() {
        combine(lifecycle.currentStateFlow, windowFocused, attempt.notificationOpen) { state, focus, prompt ->
            !isFinishing && !isDestroyed && (state.isAtLeast(Lifecycle.State.RESUMED) && focus ||
                state.isAtLeast(Lifecycle.State.STARTED) && prompt)
        }.first { it }
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
    private suspend fun requestSplashAds(deferIfUnauthorized: Boolean = false) {
        if (attempt.adsRequested) return
        // Billing or remote fetch can finish while the host is backgrounded. Check focus at the
        // actual request boundary, then re-read authorization after that suspension.
        awaitRequestWindow()
        if (deferIfUnauthorized && !OnboardingSdk.canRequestAds()) return
        attempt.adsRequested = true
        // The minimum begins once requests are allowed, overlapping our notification prompt.
        attempt.adPhaseStartedAtMs = SystemClock.elapsedRealtime()
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
            attempt.bannerSettled.complete(Unit)
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
        val state = attempt
        provider.loadBanner(
            this,
            unit,
            placement.tracked(
                object : AdEventListener {
                    override fun onLoaded() {
                        ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                        state.bannerSettled.complete(Unit)
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed")
                        state.bannerSettled.complete(Unit)
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
            attempt.settleInterstitial(InterResult.SKIPPED)
            return
        }

        ObLog.d(
            ObLog.Section.LOAD,
            "${placement.key} request tiers=${unit.tierCount} budgetMs=${sdk.flags().splashAdBudgetMs}",
        )
        placement.trackRequest()
        val state = attempt
        provider.loadInterstitial(
            this,
            placement,
            unit,
            placement.tracked(
                object : AdEventListener {
                    override fun onLoaded() {
                        ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                        state.settleInterstitial(InterResult.LOADED)
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed — all tiers")
                        state.settleInterstitial(InterResult.FAILED)
                    }
                },
            ),
        )
    }

    /** One monotonic deadline, first armed after notification and foreground focus. */
    private fun beginAdWait() {
        if (attempt.budgetDeadlineMs != null) return
        val flags = checkNotNull(attempt.flags)
        if (!attempt.interstitialSettled.isCompleted ||
            flags.splashBannerWaitMs > 0 && !attempt.bannerSettled.isCompleted) {
            attempt.budgetDeadlineMs = SystemClock.elapsedRealtime() + flags.splashAdBudgetMs.coerceAtLeast(0)
            ObLog.d(ObLog.Section.SPLASH, "attempt=${attempt.id} budget_start ms=${flags.splashAdBudgetMs}")
        }
    }

    private fun remainingBudgetMs(): Long =
        ((attempt.budgetDeadlineMs ?: SystemClock.elapsedRealtime()) - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private suspend fun awaitBanner() {
        if (attempt.bannerDeadlineMs == null) {
            attempt.bannerDeadlineMs = SystemClock.elapsedRealtime() + checkNotNull(attempt.flags).splashBannerWaitMs.coerceAtLeast(0)
        }
        val waitMs = minOf((checkNotNull(attempt.bannerDeadlineMs) - SystemClock.elapsedRealtime()).coerceAtLeast(0), remainingBudgetMs())
        if (waitMs > 0) withTimeoutOrNull(waitMs.milliseconds) { attempt.bannerSettled.await() }
    }

    private suspend fun awaitInterstitial() {
        if (!attempt.interstitialSettled.isCompleted) {
            val remaining = remainingBudgetMs()
            val result = withTimeoutOrNull(remaining.milliseconds) { attempt.interstitialSettled.await() }
            if (result == null) attempt.settleInterstitial(InterResult.TIMED_OUT)
        }
        if (attempt.interstitialSettled.await() == InterResult.TIMED_OUT) {
            ObLog.w(ObLog.Section.LOAD, "attempt=${attempt.id} splash_inter BUDGET_EXPIRED")
        }
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

    private fun isReturningUser(): Boolean = when (val decision = attempt.startDecision) {
        null -> false
        is StartDecision.Skip -> true
        is StartDecision.Start -> decision.destination == FlowDestination.QUESTION_OLD_USER
    }

    private suspend fun proceed() {
        if (!attempt.showRequested) {
            val timing = attempt.nextScreenTiming ?: nextScreenTiming().also { attempt.nextScreenTiming = it }
            // UnderAd queues the destination immediately before the vendor's show(). Any wait
            // belongs before that pair, otherwise the destination can cover an already visible ad.
            // Wait before the paywall too: recreation during the minimum must not replay it.
            if (timing == NextScreenTiming.UNDER_AD) awaitMinimumDisplay()
            awaitPresentationWindow()
            val purchased = sdk.presentPaywall(this, PaywallPlacement.SPLASH_INTER) == PaywallOutcome.Purchased
            awaitPresentationWindow()
            attempt.showRequested = true
            val state = attempt
            if (purchased || state.interstitialSettled.await() != InterResult.LOADED) {
                if (purchased) AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PURCHASED_AT_PAYWALL)
                state.showNext.complete(Unit)
                state.showFinished.complete(Unit)
            } else {
                // Keep terminal state across recreation without retaining the old Activity.
                // Only this live presentation owner can take the synchronous UnderAd handoff.
                val owner = WeakReference(this)
                showInterstitial(
                    AdPlacement.SplashInterstitial,
                    onNext = {
                        if (state.nextScreenTiming == NextScreenTiming.UNDER_AD) {
                            owner.get()?.takeIf {
                                !it.isFinishing && !it.isDestroyed &&
                                    it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            }?.startFlow()
                        }
                        state.showNext.complete(Unit)
                    },
                    onFinished = { state.showFinished.complete(Unit) },
                )
            }
        }
        if (attempt.nextScreenTiming == NextScreenTiming.AFTER_AD) attempt.showFinished.await()
        else attempt.showNext.await()

        if (!attempt.flowStarted) {
            // AFTER_AD, no-ad, or an owner lost before onNext: never replay navigation on top
            // of an existing ad. A recreated owner continues once the presentation has ended.
            attempt.showFinished.await()
            awaitMinimumDisplay()
            awaitSplashFocus()
            startFlow()
        }
        attempt.showFinished.await()
        attempt.completed = true
        finish()
    }

    private suspend fun awaitMinimumDisplay() {
        val remaining = remainingMinDisplayMs(sdk.requireConfig().splash.minDisplayTimeMs)
        if (remaining > 0) delay(remaining.milliseconds)
    }

    private suspend fun awaitPresentationWindow() {
        awaitSplashFocus()
        val settleMs = checkNotNull(attempt.flags).splashNotificationSettleMs
        attempt.notificationAnsweredAtMs?.let { answeredAt ->
            val remaining = answeredAt + settleMs - SystemClock.elapsedRealtime()
            if (remaining > 0) delay(remaining.milliseconds)
        }
        awaitSplashFocus()
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
        if (attempt.flowStarted) return
        attempt.flowStarted = true
        OnboardingSdk.track(AnalyticsEvent.SplashCompleted(System.currentTimeMillis() - attempt.startedAtMs))
        val decision = attempt.startDecision ?: StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)
        (decision as? StartDecision.Start)?.let {
            sdk.preload().onSplashRemoteReady(this, it.destination, it.resumeStepIndex,
                language1AlreadyScheduled = attempt.lfo1Scheduled)
        }
        ObLog.d(ObLog.Section.NAV, "ob_splash -> ${describe(decision)}")
        OnboardingSdk.startResolved(this, decision, StartOptions(passthrough = intent.extras))
    }

    private fun describe(decision: StartDecision): String = when (decision) {
        is StartDecision.Start -> "START dest=${decision.destination} resumeIndex=${decision.resumeStepIndex}"
        is StartDecision.Skip -> "SKIP reason=${decision.reason}"
    }

    private fun remainingMinDisplayMs(configured: Long): Long {
        val target = checkNotNull(attempt.flags).splashMinDisplayMs.takeIf { it > 0 } ?: configured
        val elapsed = SystemClock.elapsedRealtime() - attempt.adPhaseStartedAtMs
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
            // Loading is visible throughout network/consent/ad waits, whose duration is unknown.
            // The view owns its looping animation and starts/stops it with visibility, including
            // after recreation. Apply this to host layouts as well as the default splash.
            bar.isIndeterminate = true
        }
    }

    override fun onDestroy() {
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
     * trip. Once an SDK-owned consent flow is resolving, wait for its result without a deadline
     * on the user's reading time. Destroying this Activity cancels its wait.
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
