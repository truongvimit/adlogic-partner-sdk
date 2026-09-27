package io.onboardkit.ui.splash

import io.onboardkit.remote.OnboardingSettings
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ads.module.update.ForceUpdateConfig
import com.ads.module.update.ForceUpdateGate
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NativeStatus
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.showNativeAd
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.ads.tracked
import com.ads.module.config.AdRemoteConfig
import io.onboardkit.ads.ObInterstitial
import io.onboardkit.ads.SplashInterCase
import io.onboardkit.config.InterstitialAdUnit
import io.onboardkit.config.OnboardKitConfig
import io.onboardkit.config.SplashAdSlotFormat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** Splash template: the app's launcher Activity extends it and overrides the hooks it needs. */
open class ObSplashActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_splash"

    private val attempt by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[SplashAttempt::class.java]
    }
    private val windowFocused = MutableStateFlow(false)
    private var progressBar: ProgressBar? = null
    private var progressPercent: TextView? = null
    private var renderedPercent = -1
    private var progressTimeoutMs = 60_000L
    private var progressAnimator: ValueAnimator? = null
    private var reducedMotionJob: Job? = null
    private var budgetJob: Job? = null

    private val nativeScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { attempt.nativeScreenFinished.complete(Unit) }

    // Unconditional registration lets AndroidX deliver a pending result to a recreated owner.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        getSharedPreferences("ob_splash_permissions", MODE_PRIVATE).edit()
            .putBoolean("notification_handled", true).apply()
        ObLog.d(ObLog.Section.SPLASH, "notification permission result granted=$granted")
        attempt.promptAnswered()
        refreshProgress()
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

        attempt.attach(this)
        if (!attempt.showRequested) forgetIdlePreloads()
        observeProgress()
        lifecycleScope.launch { runSplash() }
    }

    private fun forgetIdlePreloads() {
        attempt.preloadsRequested.retainAll { sdk.provider()?.nativeStatus(it) != NativeStatus.IDLE }
    }

    override fun onResume() {
        super.onResume()
        // A recreated instance may keep the window focus without onWindowFocusChanged(true).
        windowFocused.value = hasWindowFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused.value = hasFocus
        if (!restartedByGuard) refreshProgress()
    }

    override fun onPause() {
        if (!restartedByGuard) pauseProgress()
        super.onPause()
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    // A form can cover a RESUMED activity. React to visibility instead of polling UMP.
                    ConsentCenter.formShowing.collect { refreshProgress() }
                } finally {
                    pauseProgress()
                }
            }
        }
    }

    private fun pauseProgress() {
        progressAnimator?.cancel()
        reducedMotionJob?.cancel()
        budgetJob?.cancel()
        attempt.progress.setActive(false, SystemClock.elapsedRealtime())
    }

    /** Called only for lifecycle, focus, prompt, configuration, or presentation changes. */
    private fun refreshProgress() {
        progressAnimator?.cancel()
        reducedMotionJob?.cancel()
        budgetJob?.cancel()
        val now = SystemClock.elapsedRealtime()
        val visible = !isFinishing && !isDestroyed && !attempt.showRequested &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && windowFocused.value &&
            attempt.prompt.value != SplashPrompt.Open && !ConsentCenter.isFormShowing()
        attempt.progress.setActive(visible, now)
        val elapsed = attempt.progress.elapsedMs(now)
        renderProgress(elapsed)
        if (!visible) return

        // Deadline scheduling is independent of rendering, including custom layouts without a bar.
        attempt.budgetTimeoutMs?.let { timeout ->
            if (!attempt.interstitialSettled.isCompleted) {
                budgetJob = lifecycleScope.launch {
                    delay((timeout - attempt.progress.elapsedMs(SystemClock.elapsedRealtime())).coerceAtLeast(0))
                    attempt.onInterResult(InterResult.TIMED_OUT)
                }
            }
        }
        if ((progressBar == null && progressPercent == null) || elapsed >= progressTimeoutMs) return
        val scale = if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
        else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale <= 0f || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            // Reduced motion still reports progress, without animation frames or percentage scheduling.
            reducedMotionJob = lifecycleScope.launch {
                while (attempt.progress.elapsedMs(SystemClock.elapsedRealtime()) < progressTimeoutMs) {
                    delay(1_000.milliseconds)
                    renderProgress(attempt.progress.elapsedMs(SystemClock.elapsedRealtime()))
                }
            }
            return
        }
        progressAnimator = ValueAnimator.ofFloat(elapsed.toFloat(), progressTimeoutMs.toFloat()).apply {
            // This represents elapsed time, so keep the 10s/60s curve at non-default system scales.
            duration = ((progressTimeoutMs - elapsed) / scale).toLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener { renderProgress((it.animatedValue as Float).toLong()) }
            start()
        }
    }

    private fun renderProgress(elapsedMs: Long) {
        val fraction = if (attempt.showRequested) 1f else attempt.progress.fractionAt(elapsedMs, progressTimeoutMs)
        val level = (fraction * 10_000).toInt()
        progressBar?.let { if (it.progress != level) it.progress = level }
        val percent = level / 100
        if (percent == renderedPercent) return
        renderedPercent = percent
        progressPercent?.text = getString(R.string.ob_splash_percent, percent)
    }

    private suspend fun awaitNetworkGate(cfg: OnboardKitConfig) {
        if (!cfg.splash.noInternetPromptEnabled) return
        if (ObNetwork.isValidated(this)) return

        ObLog.w(ObLog.Section.SPLASH, "no validated internet — holding the splash")
        val offlineDialog = ObNoInternetDialog(this, onRetry = ::openConnectivitySettings)
        offlineDialog.show()
        try {
            ObNetwork.awaitValidated(this)
        } finally {
            offlineDialog.dismiss()
        }
        // Focus, not resume: the connectivity panel floats over a splash that stays RESUMED.
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

    private suspend fun runSplash() {
        if (attempt.showRequested) return proceed()
        val configBeforeRemote = sdk.requireConfig()
        awaitNetworkGate(configBeforeRemote)
        startBackgroundWork(configBeforeRemote)
        if (attempt.consentAnswered == null) {
            attempt.consentAnswered = awaitConsentAnswer(
                roundTripMs = configBeforeRemote.splash.consentTimeoutMs,
                isResolving = ConsentCenter::isResolving,
                canRequestAds = ConsentCenter::canRequestAds,
                request = ::onConsentRequired,
            )
        }
        if (attempt.updateConfig == null) attempt.updateConfig = readForceUpdateConfig()
        if (attempt.consentAnswered != true) {
            ObLog.w(ObLog.Section.SPLASH, "consent has not authorized requests — running the flow without ads")
        }
        val updateConfig = checkNotNull(attempt.updateConfig)
        if (updateConfig.enabled && updateConfig.force && updateConfig.isRequired(installedVersionCode())) {
            awaitNotificationPermission()
            awaitSplashFocus()
            ForceUpdateGate.await(this, updateConfig)
        }
        attempt.allowAdRequests()
        if (attempt.startDecision == null) attempt.startDecision = OnboardingSdk.shouldStart()
        ObLog.d(ObLog.Section.SPLASH, "start_decision=${describe(checkNotNull(attempt.startDecision))}")
        requestSplashAds()
        requestSplashPreloads(attempt.settledInterOrNull())
        awaitNotificationPermission()
        awaitSplashFocus()
        beginAdWait()
        awaitInterstitial()
        proceed()
    }

    private fun startBackgroundWork(cfg: OnboardKitConfig) {
        val refresh = attempt.remoteRefresh ?: sdk.refreshRemote(cfg.splash.remoteFetchTimeoutMs)
            .also { attempt.remoteRefresh = it }
        if (!attempt.remoteHookResolved) lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            refresh.await()
            if (isFinishing || isDestroyed || attempt.remoteHookResolved) return@launch
            attempt.remoteHookResolved = true
            onRemoteFetched()
            if (!attempt.showRequested) {
                if (attempt.budgetTimeoutMs == null) {
                    progressTimeoutMs = sdk.flags().splashAdBudgetMs.coerceAtLeast(0)
                    refreshProgress()
                }
                val policy = readForceUpdateConfig()
                if (policy != attempt.updateConfig) {
                    attempt.updateConfig = policy
                    attempt.updateGatePassed = false
                }
            }
            ObLog.d(ObLog.Section.REMOTE, "attempt=${attempt.id} ${sdk.flags().adSummary()}")
        }
        if (!attempt.billingResolved) lifecycleScope.launch {
            step("billing", cfg.splash.billingTimeoutMs) { onInitBilling() }
            attempt.billingResolved = true
        }
    }

    private fun installedVersionCode(): Long =
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

    // Not windowed here: the provider holds each request until this splash's window opens.
    internal fun requestSplashPreloads(interResult: InterResult?) {
        if ((attempt.startDecision as? StartDecision.Start)?.destination != FlowDestination.LANGUAGE) return
        val flags = sdk.flags()
        val parallel = flags.splashLfoParallelPreloadEnabled
        val allowWhileVisible = attempt.prompt.value == SplashPrompt.Open
        if ((parallel || interResult != null) && attempt.preloadsRequested.add(AdPlacement.Language1)) {
            val reason = if (parallel) "parallel_start" else interResult?.lfoReason
            ObLog.d(
                ObLog.Section.PRELOAD,
                "splash_lfo attempt=${attempt.id} mode=${if (parallel) "parallel" else "sequential"} reason=$reason",
            )
            sdk.preload().preloadLanguage1(this, allowWhileVisible)
        }
        if (interResult == InterResult.LOADED && attempt.preloadsRequested.add(AdPlacement.SplashNative)) {
            sdk.preload().preloadSplashNative(this, allowWhileVisible)
        }
    }

    private fun splashNativeEligible(): Boolean =
        AdPlacement.SplashNative in attempt.preloadsRequested &&
            sdk.guard().skipReason(this, AdPlacement.SplashNative) == null

    private suspend fun awaitSplashNativeScreen() {
        if (attempt.nativeScreenResolved) return
        if (!attempt.nativeScreenRequested) {
            val status = sdk.provider()?.nativeStatus(AdPlacement.SplashNative)
            if (!splashNativeEligible() ||
                (status != NativeStatus.READY && status != NativeStatus.LOADING)
            ) {
                sdk.provider()?.releaseNative(AdPlacement.SplashNative)
                attempt.nativeScreenResolved = true
                return
            }
            attempt.nativeScreenRequested = true
            nativeScreenLauncher.launch(Intent(this, ObSplashNativeActivity::class.java))
        }
        attempt.nativeScreenFinished.await()
        awaitSplashFocus()
        attempt.nativeScreenResolved = true
    }

    private suspend fun awaitNotificationPermission() {
        if (attempt.prompt.value == SplashPrompt.NotAsked) {
            if (!shouldAskNotificationPermission()) return
            awaitSplashFocus()
            attempt.promptOpened()
            refreshProgress()
            try {
                ObLog.d(ObLog.Section.SPLASH, "requesting notification permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (error: RuntimeException) {
                ObLog.w(ObLog.Section.SPLASH, "notification permission unavailable: ${error.message}")
                attempt.promptAnswered()
            }
        }
        attempt.prompt.first { it is SplashPrompt.Answered }
    }

    private fun shouldAskNotificationPermission(): Boolean {
        if (!sdk.requireConfig().splash.notificationPermissionEnabled || Build.VERSION.SDK_INT < 33 ||
            applicationInfo.targetSdkVersion < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED ||
            getSharedPreferences("ob_splash_permissions", MODE_PRIVATE)
                .getBoolean("notification_handled", false)
        ) return false
        return packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
    }

    private suspend fun awaitSplashFocus() {
        combine(lifecycle.currentStateFlow, windowFocused) { state, focused ->
            !isFinishing && !isDestroyed && state.isAtLeast(Lifecycle.State.RESUMED) && focused
        }.first { it }
    }

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

    private suspend fun requestSplashAds() {
        if (attempt.adPhaseStartedAtMs != null) return
        // Read before the latch: a recreation during this suspension must not strand the request.
        if (attempt.returningUser == null) attempt.returningUser = OnboardingSdk.isCompleted()
        awaitSplashFocus()
        attempt.adPhaseStartedAtMs = SystemClock.elapsedRealtime()
        if (splashSlotFormat() == SplashAdSlotFormat.NATIVE) requestSplashSlotNative()
        else requestSplashBanner()
        requestSplashInterstitial()
    }

    // Not valueOf: a console typo must cost the slot its native, not the app its start.
    private fun splashSlotFormat(): SplashAdSlotFormat {
        val name = OnboardingSettings.text("splash.ads.slot_format")
        return SplashAdSlotFormat.entries.firstOrNull { it.name == name } ?: SplashAdSlotFormat.BANNER
    }

    private fun requestSplashSlotNative() {
        val placement = AdPlacement.SplashInlineNative
        val container = findViewById<FrameLayout?>(R.id.ob_splash_ad_container)
        if (container == null) {
            ObLog.w(
                ObLog.Section.LOAD,
                "${placement.key} container missing — a custom splash layout that replaces " +
                    "ob_splash_ad_container has nowhere to put the bottom slot",
            )
            return
        }
        // Not cleared here: showNativeAd empties it only once the guard allows, sparing host content.
        container.visibility = View.VISIBLE
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.splashInlineNative,
            container = container,
            onBound = {
                ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
            },
            onUnavailable = { reason ->
                ObLog.w(ObLog.Section.LOAD, "${placement.key} unavailable — $reason")
                container.visibility = View.GONE
            },
        )
    }

    private fun requestSplashBanner() {
        val placement = AdPlacement.SplashBanner
        val skip = sdk.guard().skipReason(this, placement)
        val unit = sdk.requireConfig().ads.splashBanner
        val provider = sdk.provider()
        if (skip != null || unit == null || provider == null) {
            skip?.let { placement.trackSkipped(it) }
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
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed")
                    }
                },
            ),
        )
    }

    private fun requestSplashInterstitial() {
        val placement = AdPlacement.SplashInterstitial
        val case = SplashInterCase(
            SplashEntry.from(intent), attempt.returningUser == true, splashInterstitialOverride(),
        )
        // Recorded before any gate, so the show judges exactly the position this load judged.
        val (adConfigKey, unit) = ObInterstitial.spend(placement, case)
        val skip = sdk.guard().skipReason(this, placement, unit, adConfigKey)
        val provider = sdk.provider()
        if (skip != null || unit == null || provider == null) {
            placement.trackSkipped(skip ?: AdSkipReason.NO_AD_UNIT)
            attempt.onInterResult(InterResult.SKIPPED)
            return
        }

        ObLog.d(
            ObLog.Section.LOAD,
            "${placement.key} request key=$adConfigKey tiers=${unit.tierCount} " +
                "budgetMs=${sdk.flags().splashAdBudgetMs}",
        )
        placement.trackRequest()
        val state = attempt
        provider.loadInterstitial(
            this,
            placement,
            unit,
            adConfigKey,
            placement.tracked(
                object : AdEventListener {
                    override fun onLoaded() {
                        ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                        state.onInterResult(InterResult.LOADED)
                    }

                    override fun onFailedToLoad() {
                        ObLog.w(ObLog.Section.LOAD, "${placement.key} failed — all tiers")
                        state.onInterResult(InterResult.FAILED)
                    }
                },
            ),
        )
    }

    private fun beginAdWait() {
        if (attempt.budgetTimeoutMs != null) return
        val flags = sdk.flags()
        attempt.budgetTimeoutMs = flags.splashAdBudgetMs.coerceAtLeast(0)
        progressTimeoutMs = checkNotNull(attempt.budgetTimeoutMs)
        refreshProgress()
        ObLog.d(ObLog.Section.SPLASH, "attempt=${attempt.id} visible_budget ms=${flags.splashAdBudgetMs}")
    }

    private suspend fun awaitInterstitial() {
        // The visible-time clock resolves the budget; prompts/background must not spend it.
        if (attempt.interstitialSettled.await() == InterResult.TIMED_OUT) {
            ObLog.w(ObLog.Section.LOAD, "attempt=${attempt.id} splash_inter BUDGET_EXPIRED")
        }
    }

    /**
     * Units to spend instead of the SDK's choice for this launch, or `null`; asked once as the request
     * goes out. Default: the [SplashEntry] key's units while declared and on. A key the backend's
     * ad_config declares for this launch (entry, else `inter_splash` / `inter_splash_o`) still wins.
     */
    protected open fun splashInterstitialOverride(): InterstitialAdUnit? =
        SplashEntry.from(intent)?.let { entry ->
            AdRemoteConfig.getInstance().tiersFor(entry.interKey)
                .takeIf { it.isNotEmpty() }
                ?.let { InterstitialAdUnit(tiers = it) }
        }

    private suspend fun proceed() {
        if (!attempt.showRequested) {
            if (attempt.nextScreenTiming == null) {
                awaitSplashFocus()
                attempt.nextScreenTiming = resolveNextScreenTiming(
                    nativeFsEligible = splashNativeEligible(),
                    isEntry = SplashEntry.from(intent) != null,
                    remote = ::remoteNextScreenTiming,
                    hook = ::nextScreenTiming,
                )
            }
            // Before the paywall and the show for every timing: a dismissed ad navigates at once.
            awaitMinimumDisplay()
            awaitPresentationWindow()
            awaitUpdateGate()
            val purchased = sdk.presentPaywall(this, PaywallPlacement.SPLASH_INTER) == PaywallOutcome.Purchased
            awaitSplashFocus()
            // Remote can publish a required update while the paywall owns the screen.
            awaitUpdateGate()
            attempt.showRequested = true
            refreshProgress()
            val state = attempt
            if (purchased || state.interstitialSettled.await() != InterResult.LOADED) {
                if (purchased) AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PURCHASED_AT_PAYWALL)
                state.nativeScreenResolved = true
                sdk.provider()?.releaseNative(AdPlacement.SplashNative)
                state.showNext.complete(Unit)
                state.showFinished.complete(Unit)
            } else {
                // Weak: the show outlives recreation; only a live owner takes the UNDER_AD handoff.
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
                    onFinished = { reason ->
                        if (reason != null) {
                            state.nativeScreenResolved = true
                            OnboardingSdk.provider()?.releaseNative(AdPlacement.SplashNative)
                        }
                        state.showFinished.complete(Unit)
                    },
                )
            }
        }
        if (attempt.nextScreenTiming == NextScreenTiming.UNDER_AD) attempt.showNext.await()
        if (!attempt.flowStarted) {
            attempt.showFinished.await()
            awaitSplashFocus()
            awaitSplashNativeScreen()
            startFlow()
        }
        attempt.showFinished.await()
        finish()
    }

    private suspend fun awaitUpdateGate() {
        while (!attempt.updateGatePassed) {
            val policy = checkNotNull(attempt.updateConfig)
            ForceUpdateGate.await(this, policy)
            attempt.updateGatePassed = attempt.updateConfig == policy
            awaitSplashFocus()
        }
    }

    private suspend fun awaitMinimumDisplay() {
        // An answered notification has its own settle interval; a quick answer must not add
        // the splash minimum on top of the one second requested before presenting a ready ad.
        if (attempt.promptAnsweredAtMs != null) return
        val remaining = minDisplayLeftMs(
            sdk.requireConfig().splash.minDisplayTimeMs,
            checkNotNull(attempt.adPhaseStartedAtMs),
            SystemClock.elapsedRealtime(),
        )
        if (remaining > 0) delay(remaining.milliseconds)
    }

    private suspend fun awaitPresentationWindow() {
        attempt.promptAnsweredAtMs?.let { answeredAt ->
            val settleMs = sdk.flags().splashNotificationSettleMs
            delay(remainingMs(answeredAt + settleMs, SystemClock.elapsedRealtime()).milliseconds)
        }
        awaitSplashFocus()
    }

    private fun remoteNextScreenTiming(): NextScreenTiming? =
        (OnboardingSettings.values.remoteValue("splash.navigation.next_screen_timing") as? String)
            ?.takeUnless { it == "AUTO" }?.let(NextScreenTiming::valueOf)

    /**
     * Asked once, with the splash in front, before its ad would show, unless native_fs forces
     * AFTER_AD: for every [SplashEntry] launch and for a launcher start without an explicit remote
     * `splash.navigation.next_screen_timing`. Default: AFTER_AD for an entry, else the explicit
     * setting, else AFTER_AD for the first-open flow and UNDER_AD otherwise.
     */
    protected open fun nextScreenTiming(): NextScreenTiming = defaultNextScreenTiming(
        entry = SplashEntry.from(intent),
        configured = {
            OnboardingSettings.text("splash.navigation.next_screen_timing")
                .takeUnless { it == "AUTO" }?.let(NextScreenTiming::valueOf)
        },
        decision = attempt.startDecision,
    )

    private fun startFlow() {
        if (attempt.flowStarted) return
        attempt.flowStarted = true
        OnboardingSdk.track(AnalyticsEvent.SplashCompleted(System.currentTimeMillis() - attempt.startedAtMs))
        val decision = attempt.startDecision ?: StartDecision.Skip(SkipReason.DISABLED_BY_CONFIG)
        (decision as? StartDecision.Start)?.let {
            sdk.preload().onSplashRemoteReady(this, it.destination, it.resumeStepIndex,
                language1AlreadyScheduled = AdPlacement.Language1 in attempt.preloadsRequested)
        }
        ObLog.d(ObLog.Section.NAV, "ob_splash -> ${describe(decision)}")
        OnboardingSdk.startResolved(this, decision, StartOptions(passthrough = intent.extras))
    }

    private fun describe(decision: StartDecision): String = when (decision) {
        is StartDecision.Start -> "START dest=${decision.destination} resumeIndex=${decision.resumeStepIndex}"
        is StartDecision.Skip -> "SKIP reason=${decision.reason}"
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
        progressTimeoutMs = attempt.budgetTimeoutMs ?: sdk.flags().splashAdBudgetMs.coerceAtLeast(0)
        progressPercent = findViewById(R.id.ob_splash_progress_percent)
        progressBar = findViewById<ProgressBar?>(R.id.ob_splash_progress)?.apply {
            isIndeterminate = false
            max = 10_000
        }
    }

    override fun onDestroy() {
        if (!restartedByGuard) pauseProgress()
        ConsentCenter.detach(this)
        // Also on recreation, unlike other screens: the slot is requested once per attempt.
        sdk.provider()?.releaseNative(AdPlacement.SplashInlineNative)
        super.onDestroy()
    }

    /**
     * Whether ads may now be requested; runs the SDK's UMP flow by default and ends with this Activity.
     * A custom CMP must publish both decisions through [ConsentCenter.setHostConsent] before returning:
     * `true` alone grants nothing. Bounded by `consentTimeoutMs` unless the SDK's own consent flow is live.
     */
    protected open suspend fun onConsentRequired(): Boolean =
        suspendCancellableCoroutine { continuation ->
            ConsentCenter.request(this, screen = "splash") { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }

    /**
     * Refreshes purchase entitlement alongside consent, bounded by `billingTimeoutMs`.
     * Ad requests use the currently published entitlement without waiting for this hook.
     */
    protected open suspend fun onInitBilling() {}

    /**
     * Reads activated update policy without fetching or waiting. Read before requests and again
     * if remote finishes before presentation. A known required update blocks ads; an optional one
     * is offered before the splash ad.
     * Default off; `FirebaseUpdateConfig.activated()` fits here.
     */
    protected open fun readForceUpdateConfig(): ForceUpdateConfig = ForceUpdateConfig()

    /** Called once when background refresh settles, while this splash is alive. SDK settings apply
     * independently of this hook, including after splash closes; use process-owned fetch listeners
     * for app settings that must also update after this Activity is gone. */
    protected open fun onRemoteFetched() {}
}
