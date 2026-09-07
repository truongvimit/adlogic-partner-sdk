package io.retentionkit

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.retentionkit.core.*
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.FeedbackShowResult
import io.retentionkit.feedback.RetentionFeedbackModule
import io.retentionkit.notifications.RetentionNotificationOptions
import io.retentionkit.notifications.RetentionNotifications
import io.retentionkit.review.RetentionReviewModule
import io.retentionkit.review.ReviewOptions
import io.retentionkit.widgets.RetentionWidgets
import io.retentionkit.widgets.WidgetOptions
import java.util.UUID

/** Vendor-free umbrella options. Null disables a module; use selective artifacts for smaller graphs. */
data class RetentionKitOptions @JvmOverloads constructor(
    val featureProvider: RetentionFeatureProvider = RetentionFeatureProvider.EMPTY,
    val router: RetentionRouter = RetentionRouter.NONE,
    val localeProvider: RetentionLocaleProvider = RetentionLocaleProvider.SYSTEM,
    val notifications: RetentionNotificationOptions? = RetentionNotificationOptions(),
    val widgets: WidgetOptions? = WidgetOptions(),
    val feedback: FeedbackOptions? = FeedbackOptions(),
    val review: ReviewOptions? = ReviewOptions(),
    val initialUserState: RetentionUserState = RetentionUserState(),
    val initialOverrides: Map<String, String> = emptyMap(),
    val eventSink: RetentionEventSink = RetentionEventSink.NONE,
    val configSource: RetentionConfigSource? = null,
    val configTimeoutMillis: Long = 10_000,
    val uiHost: RetentionUiHost = RetentionUiHost.NONE,
    val adapters: List<RetentionModule> = emptyList(),
    val clock: RetentionClock = RetentionClock.System,
    val store: RetentionStore? = null,
)

sealed class RetentionKitInstallResult {
    data class Installed(val kit: RetentionKit, val reused: Boolean = false) : RetentionKitInstallResult()
    data class Failed(val reasons: List<String>) : RetentionKitInstallResult()
}
sealed class RetentionDispatchResult {
    /** Entry is now consumed; the host opens this feature once. */
    data class Navigate(val entry: RetentionEntry) : RetentionDispatchResult()
    /** SDK accepted its internal route; this is not evidence that a screen was visible. */
    data object SdkHandled : RetentionDispatchResult()
    data class Unavailable(val reason: String) : RetentionDispatchResult()
}

/** Standard modules share one runtime, catalogue, route ledger, UI owner and cached config. */
class RetentionKit private constructor(
    val runtime: RetentionRuntime,
    val notifications: RetentionNotifications?,
    val widgets: RetentionWidgets?,
    val feedback: RetentionFeedbackModule?,
    val review: RetentionReviewModule?,
    private val remoteConfig: RetentionRemoteConfig?,
    private val uiHost: RetentionUiHost,
) {
    /** Bind in Main.onCreate; forward the returned handle's new-Intent/save callbacks. */
    fun mainHandoff(activity: Activity, savedInstanceState: Bundle?, featureRouter: RetentionRouter): RetentionMainHandoff =
        RetentionMainHandoff(this, activity, savedInstanceState, featureRouter, uiHost)
    /** Call for onCreate AND onNewIntent. Forward the rewritten Intent extras through setup. */
    fun capture(intent: Intent?): RetentionEntryAcceptance = runtime.entries.capture(intent).also { accepted ->
        if (accepted is RetentionEntryAcceptance.Accepted) notifications?.recordOpened(accepted.entry)
    }
    /** Advanced host feature route claim. Prefer dispatchPending to handle standard SDK routes too. */
    fun consume(token: String): RetentionEntry? {
        val entry = runtime.entries.pending(token) ?: return null
        if (feedback?.handles(entry) == true || entry.destination == RetentionFeedbackModule.DESTINATION) return null
        return if (runtime.entries.consume(token)) entry else null
    }
    /** Invoke after setup and while the final host Activity is resumed; blocked SDK entries remain pending. */
    fun dispatchPending(token: String): RetentionDispatchResult {
        val entry = runtime.entries.pending(token) ?: return RetentionDispatchResult.Unavailable("missing_or_consumed")
        if (!runtime.userState.setupCompleted) return RetentionDispatchResult.Unavailable("setup_incomplete")
        val eligibility = runtime.ui.eligibility(RetentionUiPurpose.ENTRY)
        if (eligibility is RetentionEligibility.Blocked) return RetentionDispatchResult.Unavailable(eligibility.reason.name.lowercase())
        if (entry.destination == RetentionFeedbackModule.DESTINATION) {
            val module = feedback ?: return RetentionDispatchResult.Unavailable("feedback_disabled")
            return when (val result = module.handleEntry(entry)) {
                FeedbackShowResult.Scheduled -> RetentionDispatchResult.SdkHandled
                is FeedbackShowResult.Unavailable -> RetentionDispatchResult.Unavailable(result.reason)
            }
        }
        if (runtime.features().none { it.id == entry.destination }) return RetentionDispatchResult.Unavailable("unknown_destination")
        if (!runtime.userState.setupCompleted || runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Blocked) {
            return RetentionDispatchResult.Unavailable("ui_changed")
        }
        return consume(token)?.let(RetentionDispatchResult::Navigate) ?: RetentionDispatchResult.Unavailable("already_consumed")
    }
    fun setupCompleted() { runtime.signal(RetentionSignal.SetupCompleted) }
    fun onboardingChanged(active: Boolean) { runtime.signal(RetentionSignal.OnboardingChanged(active)) }
    fun entitlementChanged(entitlement: RetentionEntitlement) { runtime.signal(RetentionSignal.EntitlementChanged(entitlement)) }
    @JvmOverloads fun businessSuccess(featureId: String, eventId: String = UUID.randomUUID().toString()) {
        runtime.signal(RetentionSignal.BusinessSuccess(featureId, eventId))
    }
    @JvmOverloads fun adClicked(clickId: String = UUID.randomUUID().toString()) { runtime.signal(RetentionSignal.AdClicked(clickId)) }
    /** Notification permission retains one owner in the host/OnboardKit; RetentionKit never requests it. */
    fun permissionChanged() { runtime.reconcile("permission_changed") }
    fun refreshConfig() { remoteConfig?.refresh() }

    companion object {
        private val lock = Any()
        @Volatile private var installed: RetentionKit? = null
        @JvmStatic fun get(): RetentionKit? = installed?.takeIf { it.runtime === RetentionRuntime.get() }
        @JvmStatic fun install(application: Application, options: RetentionKitOptions): RetentionKitInstallResult = synchronized(lock) {
            get()?.let { return RetentionKitInstallResult.Installed(it, reused = true) }
            if (RetentionRuntime.get() != null) return RetentionKitInstallResult.Failed(listOf("Runtime was installed outside RetentionKit"))
            try {
                val notifications = options.notifications?.let(::RetentionNotifications)
                val widgets = options.widgets?.let(::RetentionWidgets)
                val feedback = options.feedback?.let(::RetentionFeedbackModule)
                val review = options.review?.let(::RetentionReviewModule)
                val remote = options.configSource?.let { RetentionRemoteConfig(it, options.configTimeoutMillis) }
                val modules = listOfNotNull(notifications, widgets, feedback, review, remote) + options.adapters.toList()
                when (val result = RetentionRuntime.install(application, RetentionOptions(
                    modules = modules, featureProvider = options.featureProvider, router = options.router,
                    localeProvider = options.localeProvider, initialUserState = options.initialUserState,
                    initialOverrides = options.initialOverrides, eventSink = options.eventSink,
                    clock = options.clock, store = options.store, uiHost = options.uiHost,
                ))) {
                    is RetentionInstallResult.Failed -> RetentionKitInstallResult.Failed(result.reasons)
                    is RetentionInstallResult.Installed -> RetentionKit(result.runtime, notifications, widgets, feedback, review, remote, options.uiHost).let {
                        installed = it
                        RetentionKitInstallResult.Installed(it)
                    }
                }
            } catch (error: Exception) { RetentionKitInstallResult.Failed(listOf(error.message ?: "Invalid retention options")) }
        }
    }
}
