package com.itg.template.retention

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.Toast
import com.itg.template.R
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.setting.SettingActivity
import com.itg.template.ui.component.splash.SplashActivity
import io.retentionkit.*
import io.retentionkit.core.*
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.FeedbackShowResult
import io.retentionkit.feedback.RetentionFeedbackActivity
import io.retentionkit.review.ReviewActionResult
import timber.log.Timber

/** The partner seam: catalogue, chosen locale, one host bridge, shared configuration and tracking. */
object RetentionExample {
    @Volatile private var acknowledgementRuntime: RetentionRuntime? = null
    private var acknowledgement: RetentionSubscription? = null
    fun install(application: Application): RetentionKit? {
        val result = io.retentionkit.integration.RetentionSuite.install(application,
            io.retentionkit.integration.RetentionSuiteOptions(
                splashActivity = SplashActivity::class.java,
                mainActivity = MainActivity::class.java,
                featureProvider = RetentionFeatureProvider(RetentionExampleContent::features),
                featureRouter = RetentionRouter { context, _ -> android.content.Intent(context, RetentionPlaygroundActivity::class.java) },
                localeProvider = RetentionLocaleProvider(RetentionExampleContent::localizedContext),
                resolveDestination = ExampleDataStore::canonicalFeature,
                hostCanPresent = {
                    when (it) {
                        is MainActivity -> it.isRetentionEntryReady
                        is RetentionPlaygroundActivity, is SettingActivity, is RetentionFeedbackActivity -> it.window.decorView.hasWindowFocus()
                        else -> false
                    }
                },
                feedback = FeedbackOptions(featureIds = RetentionExampleContent.featureIds.toList(), appIconRes = R.mipmap.ic_launcher,
                    nativeContent = io.retentionkit.feedback.FeedbackNativeContent { activity, owner, container ->
                        val slot = android.widget.FrameLayout(activity)
                        container.addView(slot)
                        ExampleEntryNative.attach(activity, owner, slot, "native_uninstall")
                    }),
                customize = ExampleQa::options,
            ))
        if (result is RetentionKitInstallResult.Installed) attachSuccessAcknowledgement(application, result.kit.runtime)
        if (result is RetentionKitInstallResult.Failed) Timber.e("Retention install failed: %s", result.reasons)
        com.itg.template.ui.component.uninstall.ShortcutManager.initShortCut(application)
        return (result as? RetentionKitInstallResult.Installed)?.kit
    }

    /** Inspection only: ordinary defaults schedule/post from SDK readiness, not this button. */
    fun showNotificationStatus(activity: Activity) {
        val snapshot = io.retentionkit.integration.RetentionSuite.get()?.notificationStatus()
        val text = snapshot?.campaigns?.joinToString("\n\n") { item ->
            val reason = when (val outcome = item.lastOutcome) {
                is io.retentionkit.notifications.NotificationOutcome.Skipped -> outcome.reason
                is io.retentionkit.notifications.NotificationOutcome.Failed -> outcome.stage
                is io.retentionkit.notifications.NotificationOutcome.PostSubmitted -> "submitted_to_android"
                else -> if (item.pendingForeground) "waiting_for_ready_foreground" else if (item.nextAlarmAtMillis != null) "scheduled" else "waiting_for_trigger"
            }
            "${item.campaign}: $reason"
        } ?: activity.getString(R.string.rk_example_unavailable)
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.rk_example_notification_status).setMessage(text)
            .setPositiveButton(android.R.string.ok, null).show()
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
