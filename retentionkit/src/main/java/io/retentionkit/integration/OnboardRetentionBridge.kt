package io.retentionkit.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ads.module.admob.AppOpenManager
import com.ads.module.event.ERainLogEventManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.ui.splash.ObSplashActivity
import io.onboardkit.ui.splash.SplashEntry
import io.retentionkit.RetentionKit
import io.retentionkit.core.*
import io.retentionkit.feedback.RetentionFeedbackActivity
import io.retentionkit.feedback.RetentionFeedbackModule
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class RetentionEntryAdPolicy { STANDARD, WITHOUT_SPLASH_ADS }

/**
 * Optional suite adapter. Declare OnboardKit/ads explicitly, install OnboardKit first, and place
 * this object in RetentionKitOptions.adapters + uiHost; use its router for the one host entry.
 */
class OnboardRetentionBridge constructor(
    private val splashActivity: Class<out ObSplashActivity>,
    private val hostCanPresent: (Activity) -> Boolean = { true },
    private val entryAdPolicy: RetentionEntryAdPolicy = RetentionEntryAdPolicy.STANDARD,
    private val mainActivity: Class<out Activity>? = null,
) : RetentionModule, RetentionUiHost {
    constructor(splashActivity: Class<out ObSplashActivity>) : this(splashActivity, { true }, RetentionEntryAdPolicy.STANDARD, null)
    constructor(splashActivity: Class<out ObSplashActivity>, hostCanPresent: (Activity) -> Boolean) :
        this(splashActivity, hostCanPresent, RetentionEntryAdPolicy.STANDARD, null)

    override val id = "onboard-bridge"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: RetentionRuntime
    @Volatile private var closed = true
    private data class Transition(val handle: AutoCloseable, val timeout: Runnable)
    private val transitions = mutableMapOf<String, Transition>()
    private var clickObservation: AutoCloseable? = null

    /** Every tap, including rescue/warm entry, executes Splash. No-ad routing is explicit opt-in. */
    val router = RetentionRouter { context, entry ->
        val source = when {
            entry.destination == RetentionFeedbackModule.DESTINATION || entry.source == RetentionEntrySource.FEEDBACK -> SplashEntry.UNINSTALL
            entry.source == RetentionEntrySource.WIDGET || entry.source == RetentionEntrySource.SHORTCUT -> SplashEntry.WIDGET
            else -> SplashEntry.NOTIFICATION
        }
        when (entryAdPolicy) {
            RetentionEntryAdPolicy.STANDARD -> source.intent(context, splashActivity)
            RetentionEntryAdPolicy.WITHOUT_SPLASH_ADS -> source.intentWithoutSplashAds(context, splashActivity)
        }
    }

    override fun attach(runtime: RetentionRuntime) {
        this.runtime = runtime
        closed = false
        // SDK-owned Activity exclusion must exist before onActivityStarted/process-onStart. Waiting
        // for its later onResume lease would be too late for OPEN/WELCOME's current-Activity gate.
        AppOpenManager.getInstance().disableAppResumeWithActivity(RetentionFeedbackActivity::class.java)
        clickObservation = ERainLogEventManager.observeAdClicks("retention.onboard") { clickId, _ ->
            if (!closed) runtime.signal(RetentionSignal.AdClicked(clickId))
        }
        scope.launch {
            OnboardingSdk.isFlowActive.collect { active -> if (!closed) runtime.signal(RetentionSignal.OnboardingChanged(active)) }
        }
        val stateFlow = try { OnboardingSdk.state } catch (error: Exception) { diagnose("onboarding_state", error); null }
        if (stateFlow != null) scope.launch {
            try {
                stateFlow.collect { state ->
                    if (!closed && state.isFlowCompleted && !runtime.userState.setupCompleted) {
                        runtime.signal(RetentionSignal.SetupCompleted)
                        // Restoring completed setup must not hide an actual returning onboarding UI.
                        runtime.signal(RetentionSignal.OnboardingChanged(OnboardingSdk.isFlowActive.value))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { diagnose("onboarding_state", error) }
        }
    }

    private fun hostReady(activity: Activity): Boolean =
        !closed && activity !is ObSplashActivity && activity.javaClass.name != "com.google.android.gms.ads.AdActivity" &&
            !OnboardingSdk.isFlowActive.value && !AppOpenManager.getInstance().isShowingAd &&
            !AppOpenManager.getInstance().isInterstitialShowing && !activity.isFinishing && !activity.isDestroyed

    override fun canPresent(activity: Activity): Boolean = hostReady(activity) &&
        mainActivity?.isInstance(activity) != true && hostCanPresent(activity)

    override fun canPresentEntry(activity: Activity): Boolean {
        if (!hostReady(activity)) return false
        if (mainActivity?.isInstance(activity) != true) return hostCanPresent(activity)
        val entry = (RetentionEntryCodec.read(activity.intent) as? RetentionEntryDecodeResult.Valid)?.entry ?: return false
        return entry.mode == RetentionEntryMode.ONCE && runtime.entries.pending(entry.token) == entry && hostCanPresent(activity)
    }

    override fun onLeaseAcquired(owner: String, token: String, durationMillis: Long): AutoCloseable {
        check(!closed) { "Onboard bridge is detached" }
        return AppOpenManager.getInstance().suppressResume("retention.ui:$owner:$token", "retention_sdk_ui", durationMillis)
    }

    override fun onSignal(signal: RetentionSignal) {
        onMain {
            when (signal) {
                is RetentionSignal.ExternalTransitionStarted -> startTransition(signal)
                is RetentionSignal.ExternalTransitionFinished -> finishTransition(signal.token)
                else -> Unit
            }
        }
    }
    private fun startTransition(signal: RetentionSignal.ExternalTransitionStarted) {
        val manager = AppOpenManager.getInstance()
        val handle = manager.skipNextResume("retention.external:${signal.token}", "retention_${signal.kind}", signal.durationMillis)
        val expiry = Runnable { finishTransition(signal.token) }
        val previous = transitions.put(signal.token, Transition(handle, expiry))
        previous?.let { handler.removeCallbacks(it.timeout); close(it.handle) }
        handler.postDelayed(expiry, signal.durationMillis)
    }
    private fun finishTransition(token: String) {
        val transition = transitions.remove(token) ?: return
        handler.removeCallbacks(transition.timeout)
        // A module can finish from core ProcessForeground before AppOpen captures the return, or
        // between OPEN and WELCOME observers. Preserve this exact handle through that dispatch.
        // A failed handoff with no departure clears next turn, so it cannot poison a later return.
        handler.post { close(transition.handle) }
    }

    /** Optional companion to kit.capture for hosts already keeping the bridge in their entry screen. */
    fun capture(intent: Intent?): RetentionEntryAcceptance =
        RetentionKit.get()?.takeIf { it.runtime === runtime }?.capture(intent) ?: runtime.entries.capture(intent)

    /** Invoke inside the existing OnboardingListener; never replaces the host listener or policy. */
    @JvmOverloads fun onOutcome(outcome: OnboardingOutcome, setupCompleted: Boolean = outcome !is OnboardingOutcome.Aborted): Bundle? {
        if (closed) return null
        runtime.signal(RetentionSignal.OnboardingChanged(false))
        if (setupCompleted) runtime.signal(RetentionSignal.SetupCompleted)
        val extras = when (outcome) {
            is OnboardingOutcome.Completed -> outcome.passthrough
            is OnboardingOutcome.Skipped -> outcome.passthrough
            is OnboardingOutcome.Aborted -> null
        }
        return extras?.let(::Bundle)
    }

    /** Existing listener retains its own business work; this helper never reads the pending backlog. */
    fun mainIntent(context: Context, mainActivity: Class<out Activity>, outcome: OnboardingOutcome): Intent? {
        val extras = onOutcome(outcome)
        if (closed || outcome is OnboardingOutcome.Aborted) return null
        return Intent(context, mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { extras?.let(::putExtras) }
    }
    /** The existing OnboardKit/host permission launcher remains the only owner. */
    fun permissionChanged() { if (!closed) runtime.reconcile("permission_changed") }

    /** For host-controlled Settings/permission handoffs; close on failure/result, bounded if forgotten. */
    @JvmOverloads fun beginExternal(kind: String, durationMillis: Long = 120_000): AutoCloseable {
        require(durationMillis in 1..300_000)
        check(!closed)
        val token = "onboard:${UUID.randomUUID()}"
        runtime.signal(RetentionSignal.ExternalTransitionStarted(token, kind, durationMillis))
        val once = AtomicBoolean()
        return AutoCloseable { if (once.compareAndSet(false, true)) runtime.signal(RetentionSignal.ExternalTransitionFinished(token)) }
    }
    override fun shutdown() {
        closed = true
        clickObservation?.let(::close)
        clickObservation = null
        scope.cancel()
        val cleanup = Runnable {
            transitions.values.forEach { handler.removeCallbacks(it.timeout); close(it.handle) }
            transitions.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
        // Do not un-exclude the SDK-owned Activity or clear another adapter/host's exclusions.
    }
    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { if (!closed) action() }
    }
    private fun close(handle: AutoCloseable) { try { handle.close() } catch (error: Exception) { diagnose("close", error) } }
    private fun diagnose(component: String, error: Exception) = runtime.diagnostics.record("onboard_bridge.$component", error.message ?: "Adapter failure", RetentionDiagnosticLevel.ERROR, error)
}
