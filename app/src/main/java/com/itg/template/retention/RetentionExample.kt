package com.itg.template.retention

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.itg.template.R
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.setting.SettingActivity
import com.itg.template.ui.component.splash.SplashActivity
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.ui.splash.SplashEntry
import io.retentionkit.*
import io.retentionkit.core.*
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.FeedbackShowResult
import io.retentionkit.feedback.RetentionFeedbackActivity
import io.retentionkit.integration.BillingRetentionBridge
import io.retentionkit.integration.OnboardRetentionBridge
import io.retentionkit.integration.TrackkitRetentionEventSink
import io.retentionkit.review.ReviewActionResult
import io.suite.firebase.FirebaseRetentionConfigSource
import org.json.JSONArray
import timber.log.Timber

/** The partner seam: catalogue, chosen locale, one host bridge, shared configuration and tracking. */
object RetentionExample {
    @Volatile private var acknowledgementRuntime: RetentionRuntime? = null
    private var acknowledgement: RetentionSubscription? = null
    var bridge: OnboardRetentionBridge? = null
        private set
    fun install(application: Application): RetentionKit? {
        RetentionKit.get()?.let { return it }
        val onboard = OnboardRetentionBridge(SplashActivity::class.java, hostCanPresent = {
            when (it) {
                is MainActivity -> it.isRetentionEntryReady
                is RetentionPlaygroundActivity, is SettingActivity, is RetentionFeedbackActivity -> true
                else -> false
            }
        }, mainActivity = MainActivity::class.java)
        bridge = onboard
        val result = RetentionKit.install(application, ExampleQa.options(RetentionKitOptions(
            featureProvider = RetentionFeatureProvider(RetentionExampleContent::features),
            localeProvider = RetentionLocaleProvider(RetentionExampleContent::localizedContext),
            router = onboard.router,
            uiHost = onboard,
            adapters = listOf(onboard, BillingRetentionBridge()),
            feedback = FeedbackOptions(featureIds = RetentionExampleContent.featureIds.toList(), appIconRes = R.mipmap.ic_launcher,
                nativeContent = io.retentionkit.feedback.FeedbackNativeContent { activity, owner, container ->
                    val slot = android.widget.FrameLayout(activity)
                    container.addView(slot)
                    ExampleEntryNative.attach(activity, owner, slot, "native_uninstall")
                }),
            initialUserState = RetentionUserState(entitlement = RetentionEntitlement.UNKNOWN),
            eventSink = TrackkitRetentionEventSink(),
            configSource = FirebaseRetentionConfigSource(
                legacyKeys = io.retentionkit.integration.RetentionLegacyConfig.keys,
                legacyMapper = io.retentionkit.integration.RetentionLegacyConfig::overrides),
            clock = ExampleQa.clock(application),
            store = ExampleQa.store(application),
        )))
        if (result is RetentionKitInstallResult.Installed) attachSuccessAcknowledgement(application, result.kit.runtime)
        if (result is RetentionKitInstallResult.Failed) Timber.e("Retention install failed: %s", result.reasons)
        com.itg.template.ui.component.uninstall.ShortcutManager.initShortCut(application)
        return (result as? RetentionKitInstallResult.Installed)?.kit
    }

    /** Capture once and retain the rewritten envelope. Pending entries survive setup/process death. */
    fun capture(context: Context, intent: Intent?) {
        val kit = RetentionKit.get() ?: return
        if (intent != null && RetentionEntryCodec.read(intent) is RetentionEntryDecodeResult.Absent &&
            SplashEntry.from(intent.extras) == SplashEntry.UNINSTALL) {
            RetentionEntryCodec.write(intent, RetentionEntry(RetentionEntrySource.SHORTCUT,
                "retention.feedback", "open_feedback", createdAtMillis = kit.runtime.clock.wallTimeMillis()))
        }
        val accepted = kit.capture(intent)
        if (accepted is RetentionEntryAcceptance.Accepted) {
            // Normalize legacy/raw typed launches through the same standard Splash host contract.
            kit.runtime.createEntryIntent(accepted.entry)?.extras?.let { intent?.putExtras(it) }
        }
        if (accepted is RetentionEntryAcceptance.Accepted && !kit.runtime.userState.setupCompleted) {
            val prefs = context.getSharedPreferences("retention_example_flow_v1", Context.MODE_PRIVATE)
            val tokens = runCatching { JSONArray(prefs.getString("tokens", "[]")) }.getOrDefault(JSONArray())
            val existing = (0 until tokens.length()).map { tokens.getString(it) }.toSet()
            if (accepted.entry.token !in existing) { tokens.put(accepted.entry.token); prefs.edit().putString("tokens", tokens.toString()).commit() }
        }
    }

    fun onOutcome(context: Context, outcome: OnboardingOutcome) {
        val main = bridge?.mainIntent(context, MainActivity::class.java, outcome)
        val prefs = context.getSharedPreferences("retention_example_flow_v1", Context.MODE_PRIVATE)
        if (outcome is OnboardingOutcome.Aborted) {
            // Only cancel entries explicitly attached to this unfinished setup, never the backlog.
            val tokens = runCatching { JSONArray(prefs.getString("tokens", "[]")) }.getOrDefault(JSONArray())
            repeat(tokens.length()) { RetentionKit.get()?.runtime?.entries?.consume(tokens.getString(it)) }
            prefs.edit().remove("tokens").commit()
            return
        }
        prefs.edit().remove("tokens").commit()
        main?.let(context::startActivity)
    }

    fun continueSetup(activity: Activity, token: String) {
        val runtime = RetentionKit.get()?.runtime ?: return
        val entry = runtime.entries.pending(token) ?: return
        runtime.createEntryIntent(entry)?.let(activity::startActivity)
    }

    fun beginExternal(kind: String): AutoCloseable? {
        val runtime = RetentionKit.get()?.runtime ?: return null
        val token = "example:" + java.util.UUID.randomUUID()
        if (!runtime.signal(RetentionSignal.ExternalTransitionStarted(token, kind))) return null
        // The active suite bridge reacts to these signals; QA may have replaced that bridge.
        return AutoCloseable { runtime.signal(RetentionSignal.ExternalTransitionFinished(token)) }
    }

    fun showFeedback(activity: Activity) {
        val result = RetentionKit.get()?.feedback?.openViaEntry()
        Toast.makeText(activity, if (result is FeedbackShowResult.Scheduled) R.string.rk_example_request_sent else R.string.rk_example_unavailable, Toast.LENGTH_SHORT).show()
    }
    fun manualRate(activity: Activity) {
        val result = RetentionKit.get()?.review?.openStore()
        Toast.makeText(activity, if (result is ReviewActionResult.Scheduled) R.string.rk_example_request_sent else R.string.rk_example_unavailable, Toast.LENGTH_SHORT).show()
    }
    /** Core subscribers run only after the corresponding durable user-state mutation succeeds.
     * This acknowledges dispatch; it does not promise each asynchronous module effect succeeded. */
    @Synchronized fun attachSuccessAcknowledgement(context: Context, runtime: RetentionRuntime) {
        if (acknowledgementRuntime === runtime) return
        acknowledgement?.close()
        acknowledgementRuntime = runtime
        val data = ExampleDataStore(context.applicationContext)
        acknowledgement = runtime.subscribe("example.business.outbox") { signal ->
            if (acknowledgementRuntime === runtime && signal is RetentionSignal.BusinessSuccess) {
                val pending = data.pendingSuccesses().firstOrNull { it.id == signal.eventId && it.featureId == signal.featureId }
                // A thrown/failed local acknowledgement leaves the durable operation pending.
                // Core isolates subscriber exceptions, and a later flush replays the same ID.
                if (pending != null) data.markReported(pending.id)
            }
        }
    }

    @Synchronized fun flushSuccesses(context: Context) {
        val kit = RetentionKit.get() ?: return
        attachSuccessAcknowledgement(context, kit.runtime)
        for (operation in ExampleDataStore(context).pendingSuccesses()) {
            // The Boolean means queued, so only the owned subscriber above acknowledges delivery.
            if (!kit.runtime.signal(RetentionSignal.BusinessSuccess(operation.featureId, operation.id))) break
        }
    }
}
