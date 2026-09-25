package io.onboardkit.ui.splash

import io.onboardkit.remote.OnboardingSettings
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
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ads.module.update.ForceUpdateConfig
import com.ads.module.update.ForceUpdateGate
import com.ads.module.config.AdConfig
import com.ads.module.consent.ConsentCenter
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.StartOptions
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.NextScreenTiming
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.showNativeAd
import io.onboardkit.ads.trackRequest
import io.onboardkit.ads.trackSkipped
import io.onboardkit.ads.tracked
import io.onboardkit.config.AdLoadStrategy
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
import kotlinx.coroutines.CompletableDeferred
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
 * Consent and the remote fetch settle before the optional notification prompt, since remote
 * decides whether it shows. Authorized requests and the minimum display clock may run beneath
 * that prompt while splash is visible. The shared ad wait budget starts only after the
 * notification result and foreground focus; LFO1 follows the captured mode. System permission UI
 * waits for the user; network and host hooks keep their own timeouts.
 */
open class ObSplashActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_splash"

    private val attempt by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[SplashAttempt::class.java]
    }
    private var noInternetDialog: ObNoInternetDialog? = null
    private val windowFocused = MutableStateFlow(false)

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
        // Remote config may overlap UMP. Splash ad loading waits for the consent step to settle,
        // including ConsentCenter's fallback after a UMP failure or network timeout.
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
                }.also { settled ->
                    // A fetch that outlives this deadline still governs the rest of the session once it lands.
                    if (settled == null) OnboardingSdk.applyRemoteWhenLanded()
                    // One immutable policy per splash attempt, at the existing remote deadline.
                    // Neither subsequent fetches nor Activity recreation change this decision.
                    attempt.updateConfig = readForceUpdateConfig()
                    attempt.remoteResolved = true
                }
            }
            val billing = async {
                if (!attempt.billingResolved) step("billing", cfg.splash.billingTimeoutMs) { onInitBilling() }
                    .also { attempt.billingResolved = true }
            }
            // Completing the step and authorizing requests are separate. The SDK reads current
            // authority at each gate, so a later answer can recover without overwriting host-off.
            val mayRequestAds = consent.await()
            // The prompt waits for the splash's own requests, so the bottom slot loads under it
            // whichever format it is: a native cannot start its request once the prompt has focus.
            val adsRequested = CompletableDeferred<Unit>()
            val notification = async {
                remote.await()
                adsRequested.await()
                awaitNotificationPermission()
            }
            if (!mayRequestAds) {
                ObLog.w(ObLog.Section.SPLASH, "consent has not authorized requests — running the flow without ads")
            }
            // Before any request: entitlement decides whether one is legitimate at all, and a
            // request made while it is still unknown reaches a paying user. Billing was started in
            // Application.onCreate, so this usually returns having waited on nothing.
            billing.await()
            // Even SAME_TIME must wait for the update verdict: requests sent before remote
            // answers cannot be recovered if this launch turns out to require a mandatory update.
            // UMP, remote and billing above still overlap.
            remote.await()
            val installed = PackageInfoCompat.getLongVersionCode(
                packageManager.getPackageInfo(packageName, 0),
            )
            if (attempt.updateConfig.isRequired(installed)) {
                // No ad may go out before a mandatory update, so the prompt does not wait for one.
                adsRequested.complete(Unit)
                notification.await()
                awaitSplashFocus()
                ForceUpdateGate.await(this@ObSplashActivity, attempt.updateConfig)
                attempt.updateGatePassed = true
            }
            attempt.allowAdRequests()
            if (sdk.requireConfig().splash.adLoadStrategy == AdLoadStrategy.SAME_TIME) {
                requestSplashAds(deferIfUnauthorized = true)
            }
            if (!attempt.remoteHookResolved) {
                onRemoteFetched()
                attempt.remoteHookResolved = true
                attempt.flags = sdk.flags()
                ObLog.d(ObLog.Section.REMOTE, "attempt=${attempt.id} ${checkNotNull(attempt.flags).adSummary()}")
            }
            if (attempt.startDecision == null) attempt.startDecision = OnboardingSdk.shouldStart()
            ObLog.d(ObLog.Section.SPLASH, "start_decision=${describe(checkNotNull(attempt.startDecision))} returning=${isReturningUser()}")
            requestSplashAds()
            adsRequested.complete(Unit)
            lifecycleScope.launch {
                val reason = if (checkNotNull(attempt.flags).splashLfoParallelPreloadEnabled) "parallel_start"
                    else attempt.interstitialSettled.await().lfoReason
                ensureLfo1Preload(reason)
            }
            lifecycleScope.launch {
                if (attempt.interstitialSettled.await() == InterResult.LOADED) ensureSplashNativePreload()
            }
            notification.await()
        }

        awaitSplashFocus()
        beginAdWait()
        awaitInterstitial()
        awaitSlotVisible()
        ensureLfo1Preload(attempt.interstitialSettled.await().lfoReason)
        if (attempt.interstitialSettled.await() == InterResult.LOADED) ensureSplashNativePreload()
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

    private suspend fun ensureSplashNativePreload() {
        if (attempt.nativeScheduled || (attempt.startDecision as? StartDecision.Start)?.destination != FlowDestination.LANGUAGE) return
        awaitRequestWindow()
        if (attempt.nativeScheduled) return
        attempt.nativeScheduled = true
        sdk.preload().preloadSplashNative(this, allowWhileVisible = attempt.notificationOpen.value)
    }

    private fun splashNativeEligible(): Boolean =
        attempt.nativeScheduled &&
            (attempt.startDecision as? StartDecision.Start)?.destination == FlowDestination.LANGUAGE &&
            sdk.guard().skipReason(this, AdPlacement.SplashNative) == null

    /** A cold/failed optional native never adds a blank screen or delays the destination. */
    private suspend fun awaitSplashNativeScreen() {
        if (attempt.nativeScreenResolved) return
        if (!attempt.nativeScreenRequested) {
            if (!splashNativeEligible() || sdk.provider()?.isNativeReady(AdPlacement.SplashNative) != true) {
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
        if (attempt.notificationPermissionRequested) {
            attempt.notificationPermissionResult.await()
            awaitSplashFocus()
            return
        }
        if (!sdk.requireConfig().splash.notificationPermissionEnabled || Build.VERSION.SDK_INT < 33 ||
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
        attempt.focusedAtMs = SystemClock.elapsedRealtime()
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
        // Known before the start decision, because SAME_TIME loads ahead of it, and before the
        // latch below, so a recreation during this read cannot strand the request.
        if (attempt.returningUser == null) attempt.returningUser = OnboardingSdk.isCompleted()
        // Billing or remote fetch can finish while the host is backgrounded. Check focus at the
        // actual request boundary, then re-read authorization after that suspension.
        awaitRequestWindow()
        if (deferIfUnauthorized && !OnboardingSdk.canRequestAds()) return
        attempt.adsRequested = true
        // The minimum begins once requests are allowed, overlapping our notification prompt.
        attempt.adPhaseStartedAtMs = SystemClock.elapsedRealtime()
        if (splashSlotFormat() == SplashAdSlotFormat.NATIVE) requestSplashSlotNative()
        else requestSplashBanner()
        requestSplashInterstitial()
    }

    /**
     * Which format the bottom slot is filled with. Read per attempt rather than cached at config
     * time: on ALTERNATE the remote fetch lands before the request, so a console change takes
     * effect on the very launch that fetched it.
     *
     * Matched by name rather than `valueOf`: the only thing standing between a console typo and
     * an exception is an allow-list in another module, and this runs on the path every launch
     * takes. An unreadable value costs the slot its native, not the app its start.
     */
    private fun splashSlotFormat(): SplashAdSlotFormat {
        val name = OnboardingSettings.text("splash.ads.slot_format")
        return SplashAdSlotFormat.entries.firstOrNull { it.name == name } ?: SplashAdSlotFormat.BANNER
    }

    /**
     * The native that takes the slot when the format says NATIVE.
     *
     * It settles the same latch the banner does and reports its impression the same way, so one
     * slot has one wait whichever format remote config named.
     */
    private fun requestSplashSlotNative() {
        val placement = AdPlacement.SplashInlineNative
        val container = findViewById<FrameLayout?>(R.id.ob_splash_ad_container)
        if (container == null) {
            ObLog.w(
                ObLog.Section.LOAD,
                "${placement.key} container missing — a custom splash layout that replaces " +
                    "ob_splash_ad_container has nowhere to put the bottom slot",
            )
            attempt.bannerSettled.complete(Unit)
            return
        }
        // Deliberately not cleared here: showNativeAd empties the container itself before it
        // mounts either the skeleton or the ad, and a host splash layout may put its own content
        // in this slot — emptying it before the guard has spoken would destroy that for a
        // placement we then decline to fill.
        container.visibility = View.VISIBLE
        val state = attempt
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.splashInlineNative,
            container = container,
            onBound = {
                ObLog.d(ObLog.Section.LOAD, "${placement.key} loaded")
                state.markSlotLoaded()
            },
            onUnavailable = { reason ->
                ObLog.w(ObLog.Section.LOAD, "${placement.key} unavailable — $reason")
                container.visibility = View.GONE
                state.bannerSettled.complete(Unit)
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
                        state.markSlotLoaded()
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
        val case = SplashInterCase(SplashEntry.from(intent), attempt.returningUser == true, splashInterstitialOverride())
        // Recorded before any gate, so the show judges exactly the position this load judged.
        val (adConfigKey, unit) = ObInterstitial.spend(placement, case)
        val skip = sdk.guard().skipReason(this, placement, unit, adConfigKey)
        val provider = sdk.provider()
        if (skip != null || unit == null || provider == null) {
            placement.trackSkipped(skip ?: AdSkipReason.NO_AD_UNIT)
            attempt.settleInterstitial(InterResult.SKIPPED)
            return
        }

        ObLog.d(
            ObLog.Section.LOAD,
            "${placement.key} request key=$adConfigKey tiers=${unit.tierCount} budgetMs=${sdk.flags().splashAdBudgetMs}",
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

    /**
     * One monotonic deadline, first armed after notification and foreground focus. Armed even when
     * both ads settled under the prompt: the slot's minimum-visible hold is clamped to it.
     */
    private fun beginAdWait() {
        if (attempt.budgetDeadlineMs != null) return
        val flags = checkNotNull(attempt.flags)
        attempt.budgetDeadlineMs = SystemClock.elapsedRealtime() + flags.splashAdBudgetMs.coerceAtLeast(0)
        ObLog.d(ObLog.Section.SPLASH, "attempt=${attempt.id} budget_start ms=${flags.splashAdBudgetMs}")
    }

    private fun remainingBudgetMs(): Long = remainingMs(attempt.budgetDeadlineMs)

    private fun remainingMs(deadlineMs: Long?): Long =
        ((deadlineMs ?: SystemClock.elapsedRealtime()) - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    /**
     * Holds the interstitial until the bottom slot has had its minimum time on screen.
     *
     * The window runs from the later of the slot loading and the splash getting the screen back,
     * because both have to be true before anyone can look at it: a slot that filled behind the
     * notification dialog was not in front of the user yet.
     *
     * Nothing here can strand the flow. A slot that fails, is skipped or has no ad unit settles
     * without ever being marked filled and returns at once. One that has answered nothing is
     * waited for within what is left of the shared ad budget — except once the interstitial has
     * loaded and the notification prompt is gone, when it gets only
     * `splash.timing.slot_wait_after_inter_ms` from the later of the two before it is given up and
     * the interstitial shows.
     */
    private suspend fun awaitSlotVisible() {
        val minVisibleMs = checkNotNull(attempt.flags).splashSlotMinVisibleMs
        if (minVisibleMs <= 0) return
        if (!attempt.bannerSettled.isCompleted) {
            val slotDeadline = attempt.interLoadedAtMs?.let { loadedAt ->
                maxOf(loadedAt, attempt.notificationAnsweredAtMs ?: loadedAt) +
                    OnboardingSettings.number("splash.timing.slot_wait_after_inter_ms")
            }
            val waitMs = if (slotDeadline == null) remainingBudgetMs()
                else minOf(remainingBudgetMs(), remainingMs(slotDeadline))
            withTimeoutOrNull(waitMs.milliseconds) { attempt.bannerSettled.await() }
        }
        val loadedAt = attempt.slotLoadedAtMs
        if (!attempt.slotFilled || loadedAt == null) {
            ObLog.d(
                ObLog.Section.SPLASH,
                if (attempt.bannerSettled.isCompleted) "slot has no ad — not holding the interstitial"
                else "slot silent past its wait — showing the interstitial without it",
            )
            return
        }
        val onScreenSince = maxOf(loadedAt, attempt.focusedAtMs ?: loadedAt)
        val holdMs = minOf(
            minVisibleMs - (SystemClock.elapsedRealtime() - onScreenSince),
            remainingBudgetMs(),
        )
        if (holdMs > 0) {
            ObLog.d(ObLog.Section.SPLASH, "holding the interstitial ${holdMs}ms so the slot is seen")
            delay(holdMs.milliseconds)
        }
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
     * Ad units to spend in place of the SDK's own choice for this launch, or `null` to let the SDK
     * choose.
     *
     * The SDK spends one position per launch: a [SplashEntry] launch its entry's key —
     * `inter_noti`, `inter_widget`, `inter_uninstall` — while that key is declared and on, anyone
     * else their segment's key, `inter_splash_o` for a returning user (`inter_splash` without
     * `_o`) and `inter_splash` for a new one, each with its full `_high…` waterfall. An entry key
     * that is missing or switched off falls back to the segment's key, and a segment key switched
     * off silences every splash interstitial for that segment, entries included. The default
     * returns the entry key's units while they are declared and on, else `null`. The standard
     * entries therefore need no code in the app; override only for an app that diverges.
     *
     * Asked once per launch, when the request is about to go out, so it can read `intent` — the
     * same contract as [nextScreenTiming]. Only the ids change: the budget, the paywall checkpoint
     * and the telemetry keep judging the same `splash_inter` placement.
     *
     * What an override returns is a fallback. When the backend's ad_config declares the key this
     * launch would spend — the entry's key, else the splash key or, for a returning user, its `_o`
     * — the SDK resolves that key instead, switched off included.
     */
    protected open fun splashInterstitialOverride(): InterstitialAdUnit? =
        SplashEntry.from(intent)?.let { entry ->
            AdRemoteConfig.getInstance().tiersFor(entry.interKey)
                .takeIf { it.isNotEmpty() }
                ?.let { InterstitialAdUnit(tiers = it) }
        }

    private fun isReturningUser(): Boolean = attempt.returningUser ?: when (val decision = attempt.startDecision) {
        null -> false
        is StartDecision.Skip -> true
        is StartDecision.Start -> decision.destination == FlowDestination.QUESTION_OLD_USER
    }

    private suspend fun proceed() {
        if (!attempt.showRequested) {
            if (attempt.nextScreenTiming == null) {
                attempt.nextScreenTiming = when {
                    splashNativeEligible() -> NextScreenTiming.AFTER_AD
                    SplashEntry.from(intent) != null -> nextScreenTiming()
                    else -> remoteNextScreenTiming() ?: nextScreenTiming()
                }
            }
            // Must precede the paywall and show for every timing: a dismissed ad navigates at once.
            awaitMinimumDisplay()
            awaitPresentationWindow()
            if (!attempt.updateGatePassed) {
                // Only the optional update prompt reaches this point; mandatory policy was
                // handled before permitting any ad load or preload.
                ForceUpdateGate.await(this, attempt.updateConfig)
                attempt.updateGatePassed = true
                awaitPresentationWindow()
            }
            val purchased = sdk.presentPaywall(this, PaywallPlacement.SPLASH_INTER) == PaywallOutcome.Purchased
            awaitPresentationWindow()
            attempt.showRequested = true
            val state = attempt
            if (purchased || state.interstitialSettled.await() != InterResult.LOADED) {
                if (purchased) AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PURCHASED_AT_PAYWALL)
                state.nativeScreenResolved = true
                sdk.provider()?.releaseNative(AdPlacement.SplashNative)
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
        if (attempt.nextScreenTiming == NextScreenTiming.AFTER_AD) attempt.showFinished.await()
        else attempt.showNext.await()

        if (!attempt.flowStarted) {
            // AFTER_AD, no-ad, or an owner lost before onNext: never replay navigation on top
            // of an existing ad. A recreated owner continues once the presentation has ended.
            attempt.showFinished.await()
            awaitSplashFocus()
            awaitSplashNativeScreen()
            startFlow()
        }
        attempt.showFinished.await()
        attempt.completed = true
        finish()
    }

    private suspend fun awaitMinimumDisplay() {
        val remaining = remainingMinDisplayMs()
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

    /** An explicit `splash.navigation.next_screen_timing` the backend delivered; AUTO means none. */
    private fun remoteNextScreenTiming(): NextScreenTiming? =
        (OnboardingSettings.values.remoteValue("splash.navigation.next_screen_timing") as? String)
            ?.takeUnless { it == "AUTO" }?.let(NextScreenTiming::valueOf)

    /**
     * Asked once, right before the splash ad shows: for every [SplashEntry] launch, and for a
     * launcher start unless the backend delivered an explicit `splash.navigation.next_screen_timing`,
     * which outranks this hook. Default: that setting from the app asset when explicit, else
     * AFTER_AD for the first-open flow and every [SplashEntry] launch, UNDER_AD when a launcher
     * start goes past completed onboarding.
     */
    protected open fun nextScreenTiming(): NextScreenTiming =
        if (SplashEntry.from(intent) != null) NextScreenTiming.AFTER_AD
        else OnboardingSettings.text("splash.navigation.next_screen_timing").takeUnless { it == "AUTO" }?.let(NextScreenTiming::valueOf)
            ?: defaultNextScreenTiming(null, attempt.startDecision)

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

    private fun remainingMinDisplayMs(): Long {
        val target = sdk.requireConfig().splash.minDisplayTimeMs.coerceAtLeast(0)
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
        // Released unconditionally, unlike the screens that guard this on isChangingConfigurations:
        // they rebind from onCreate, whereas the slot is requested once per attempt and `attempt`
        // outlives the Activity, so a recreated splash never asks for this native again. Holding it
        // for a rebind that cannot come would strand the ad and its view for the rest of the process.
        sdk.provider()?.releaseNative(AdPlacement.SplashInlineNative)
        super.onDestroy()
    }

    /**
     * Whether ads may now be requested. Runs the SDK's UMP flow and failure fallback by default.
     *
     * Returns SDK request eligibility independently of personalization. A UMP failure or network
     * timeout permits ad requests through ConsentCenter without changing the stored consent.
     *
     * For a custom CMP, publish both decisions through [ConsentCenter.setHostConsent] before
     * returning. Returning `true` alone does not grant permission. The default UMP flow needs no
     * host wiring, and a host's explicit `OnboardingSdk.setCanRequestAds(false)` remains in force.
     *
     * The default flow's UMP round trip is bounded by ConsentOptions.timeoutMs and a visible form
     * is never timed out. An override that resolves consent without ConsentCenter is bounded by
     * `consentTimeoutMs` instead. Destroying this Activity cancels the wait.
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

    /**
     * Read activated update policy once after the existing remote step settles (including timeout).
     * Must not fetch or wait. The snapshot survives Activity recreation and is enforced
     * before any ad request for mandatory updates, or at presentation for optional prompts.
     * Consent/billing/notification still overlap; ad requests wait for this policy in all modes.
     * Default off; hosts may read FirebaseUpdateConfig.activated() here.
     */
    protected open fun readForceUpdateConfig(): ForceUpdateConfig = ForceUpdateConfig()

    /** Remote config has been fetched and synced — sync the app's own keys here. */
    protected open fun onRemoteFetched() {}
}
