package io.retentionkit.integration

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import io.onboardkit.core.OnboardingOutcome
import io.onboardkit.ui.splash.ObSplashActivity
import io.onboardkit.ui.splash.SplashEntry
import io.retentionkit.*
import io.retentionkit.core.*
import io.retentionkit.feedback.FeedbackOptions
import io.retentionkit.feedback.RetentionFeedbackModule
import io.suite.firebase.FirebaseRetentionConfigSource

/** Optional full-suite preset. Declare OnboardKit, Ads, Billing, Trackkit and suite-firebase. */
data class RetentionSuiteOptions(
    val splashActivity: Class<out ObSplashActivity>,
    val mainActivity: Class<out ComponentActivity>,
    val featureProvider: RetentionFeatureProvider,
    val featureRouter: RetentionRouter,
    val localeProvider: RetentionLocaleProvider = RetentionLocaleProvider.SYSTEM,
    val hostCanPresent: (Activity) -> Boolean = { it.window.decorView.hasWindowFocus() },
    val resolveDestination: (String) -> String = { it },
    val feedback: FeedbackOptions = FeedbackOptions(),
    /** For products with no IAP only. UNKNOWN from a configured Billing client is never treated free. */
    val usesBilling: Boolean = true,
    /** Advanced/module overrides and explicit test fixtures. Ordinary integration needs no override. */
    val customize: (RetentionKitOptions) -> RetentionKitOptions = { it },
)

/** Owns suite composition and entry lifecycle; business storage and screen layout stay in the app. */
class RetentionSuite private constructor(
    val kit: RetentionKit,
    private val options: RetentionSuiteOptions,
    private val bridge: OnboardRetentionBridge,
    private val activities: RetentionSuiteActivities,
) {
    /** Delegate once from the existing OnboardingListener; never replaces that listener. */
    fun onOutcome(context: Context, outcome: OnboardingOutcome) {
        val intent = bridge.mainIntent(context, options.mainActivity, outcome)
        val tokens = kit.runtime.store.snapshot(FLOW).entries().keys
        if (outcome is OnboardingOutcome.Aborted) tokens.forEach { kit.runtime.entries.consume(it) }
        kit.runtime.store.transaction(FLOW) { it.clear() }
        intent?.let(context::startActivity)
    }
    /** Explicit user action. SDK registers the launcher and owns result/return/timeout cleanup. */
    fun requestNotifications(activity: ComponentActivity): RetentionPermissionResult = activities.requestNotifications(activity)
    fun openNotificationSettings(activity: ComponentActivity): RetentionPermissionResult = activities.openSettings(activity)
    /** Forward an existing Onboard/host permission callback if it owns the Android prompt. */
    fun permissionChanged() = kit.permissionChanged()
    /** Read-only campaign outcomes/reasons from the notification engine, with no manual refresh. */
    fun notificationStatus() = kit.notifications?.status()

    internal fun captureSplash(intent: Intent) {
        if (RetentionEntryCodec.read(intent) is RetentionEntryDecodeResult.Absent && SplashEntry.from(intent.extras) == SplashEntry.UNINSTALL) {
            RetentionEntryCodec.write(intent, RetentionEntry(RetentionEntrySource.SHORTCUT,
                RetentionFeedbackModule.DESTINATION, "open_feedback", createdAtMillis = kit.runtime.clock.wallTimeMillis()))
        }
        val accepted = kit.capture(intent)
        if (accepted is RetentionEntryAcceptance.Accepted) {
            kit.runtime.createEntryIntent(accepted.entry)?.extras?.let(intent::putExtras)
            val pending = kit.runtime.entries.pending().map { it.token }.toSet()
            if (!kit.runtime.userState.setupCompleted) kit.runtime.store.transaction(FLOW) {
                // Bound retained setup selections and prune records already absent from the ledger.
                it.entries().keys.filter { old -> old !in pending }.forEach(it::remove)
                if (it.entries().size < 128) it.put(accepted.entry.token, true)
            }
        }
    }
    companion object {
        private const val FLOW = "suite.onboarding.entries"
        @Volatile private var installed: RetentionSuite? = null
        @JvmStatic fun get(): RetentionSuite? = installed?.takeIf { RetentionKit.get() === it.kit }
        @JvmStatic @Synchronized fun install(application: Application, options: RetentionSuiteOptions): RetentionKitInstallResult {
            get()?.let { return RetentionKitInstallResult.Installed(it.kit, reused = true) }
            if (RetentionKit.get() != null) return RetentionKitInstallResult.Failed(listOf("RetentionKit already installed without the suite"))
            val bridge = OnboardRetentionBridge(options.splashActivity, options.hostCanPresent, mainActivity = options.mainActivity)
            val activities = RetentionSuiteActivities(options)
            val standard = RetentionKitOptions(
                featureProvider = options.featureProvider, localeProvider = options.localeProvider,
                router = bridge.router, uiHost = bridge, feedback = options.feedback,
                adapters = listOfNotNull(bridge, if (options.usesBilling) BillingRetentionBridge() else null, activities),
                initialUserState = RetentionUserState(entitlement = if (options.usesBilling) RetentionEntitlement.UNKNOWN else RetentionEntitlement.NON_SUBSCRIBER),
                eventSink = TrackkitRetentionEventSink(),
                configSource = FirebaseRetentionConfigSource(legacyKeys = RetentionLegacyConfig.keys, legacyMapper = RetentionLegacyConfig::overrides),
            )
            val result = RetentionKit.install(application, options.customize(standard))
            if (result is RetentionKitInstallResult.Installed) installed = RetentionSuite(result.kit, options, bridge, activities)
            return result
        }
    }
}
