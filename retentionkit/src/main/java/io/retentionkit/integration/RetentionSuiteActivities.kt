package io.retentionkit.integration

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import io.retentionkit.*
import io.retentionkit.core.*
import java.lang.ref.WeakReference
import java.util.UUID

sealed class RetentionPermissionResult {
    /** Accepted for dispatch after synchronous SDK observers; OS may still reject/cancel. */
    data object Scheduled : RetentionPermissionResult()
    data object AlreadyGranted : RetentionPermissionResult()
    data class Unavailable(val reason: String) : RetentionPermissionResult()
}

/** One owner for Android lifecycle hooks. Does not replace app callbacks or start prompts itself. */
internal class RetentionSuiteActivities(private val options: RetentionSuiteOptions) : RetentionModule, Application.ActivityLifecycleCallbacks {
    override val id = "suite.activities"
    private lateinit var runtime: RetentionRuntime
    private val sessions = mutableListOf<Session>()
    private var closed = false
    private class Session(activity: ComponentActivity) {
        val activity = WeakReference(activity)
        var main: RetentionMainHandoff? = null
        var feature: RetentionFeatureHandoff? = null
        var listener: Consumer<Intent>? = null
        var permission: ActivityResultLauncher<String>? = null
        var external: RetentionHandoffScope? = null
        var permissionScope: RetentionHandoffScope? = null
        var binding = true
        var initialDelivery: Intent? = null
    }
    override fun attach(runtime: RetentionRuntime) {
        this.runtime = runtime
        runtime.application.registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (closed || activity !is ComponentActivity) return
        val kit = RetentionKit.get()?.takeIf { it.runtime === runtime } ?: return
        val session = Session(activity)
        sessions.add(session)
        try {
            session.listener = Consumer<Intent> { intent ->
                if (session.binding) session.initialDelivery = intent
                else {
                    session.main?.onNewIntent(intent)
                    session.feature?.onNewIntent(intent)
                }
            }.also(activity::addOnNewIntentListener)
            // Application callback happens during super.onCreate: bind before onStart; presentation
            // is posted only after onResume, when the app has finished constructing its content.
            if (options.mainActivity.isInstance(activity)) session.main = kit.mainHandoff(activity, savedInstanceState,
                RetentionRouter { context, entry ->
                    val canonical = options.resolveDestination(entry.destination)
                    if (runtime.features().any { it.id == canonical }) options.featureRouter.createIntent(context, entry) else null
                })
            else if (activity is RetentionFeatureHost) session.feature = RetentionFeatureHandoff(kit, activity, savedInstanceState, options.resolveDestination)
            // Capture/host acquisition can synchronously close the runtime or deliver a newer
            // Intent. Close a just-constructed handle too, and replay only that newest selection.
            if (closed || RetentionKit.get() !== kit) { remove(session); return }
            session.binding = false
            session.initialDelivery?.let { session.listener?.accept(it) }
            session.initialDelivery = null
            if (closed || RetentionKit.get() !== kit) { remove(session); return }
            session.permission = activity.activityResultRegistry.register("io.retentionkit.notifications.permission", activity,
                ActivityResultContracts.RequestPermission()) {
                val permission = session.permissionScope
                session.permissionScope = null
                permission?.close() // A late permission result never closes a later settings scope.
                kit.permissionChanged()
            }
        } catch (error: Exception) {
            remove(session)
            runtime.diagnostics.record(id, "Activity integration failed", RetentionDiagnosticLevel.ERROR, error)
        }
    }
    fun requestNotifications(activity: ComponentActivity): RetentionPermissionResult {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (sessions.any { it.activity.get() === activity && it.permissionScope != null }) {
                return RetentionPermissionResult.Unavailable("permission_result_pending")
            }
            return launch(activity, "notification_permission") { session, _ ->
                val owned = session.external
                session.permissionScope = owned
                try { checkNotNull(session.permission).launch(Manifest.permission.POST_NOTIFICATIONS) }
                catch (error: Exception) {
                    if (session.permissionScope === owned) session.permissionScope = null
                    throw error
                }
            }
        }
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) return openSettings(activity)
        RetentionKit.get()?.permissionChanged()
        return RetentionPermissionResult.AlreadyGranted
    }
    fun openSettings(activity: ComponentActivity): RetentionPermissionResult = launch(activity, "notification_settings") { _, current ->
        current.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, current.packageName))
    }
    private fun launch(activity: ComponentActivity, kind: String, action: (Session, Activity) -> Unit): RetentionPermissionResult {
        val session = sessions.firstOrNull { it.activity.get() === activity } ?: return RetentionPermissionResult.Unavailable("activity_not_bound")
        if (closed || session.external != null) return RetentionPermissionResult.Unavailable("request_in_progress")
        val lease = (runtime.ui.acquire("suite.permission", 10_000, RetentionUiPurpose.ENTRY) as? RetentionUiLeaseResult.Acquired)?.lease
            ?: return RetentionPermissionResult.Unavailable("ui_blocked")
        try {
            if (lease.activity() !== activity) return RetentionPermissionResult.Unavailable("ui_changed")
            lateinit var scope: RetentionHandoffScope
            scope = RetentionHandoffScope.forEntry(runtime, activity, "suite:${UUID.randomUUID()}", kind) {
                if (session.external === scope) session.external = null
                if (!closed) runtime.reconcile("permission_return")
            }
            session.external = scope // Registered before Started can reenter/cancel this owner.
            scope.start()
            scope.dispatch { ready ->
                if (ready == null || closed || session.external !== scope) scope.close()
                else try { action(session, ready) } catch (error: Exception) {
                    scope.close()
                    runtime.diagnostics.record(id, "Notification permission/settings launch failed", RetentionDiagnosticLevel.ERROR, error)
                }
            }
            return RetentionPermissionResult.Scheduled
        } finally { lease.close() }
    }
    private fun remove(session: Session) {
        sessions.remove(session)
        session.main?.close(); session.feature?.close()
        session.main = null; session.feature = null
        session.activity.get()?.let { current -> session.listener?.let(current::removeOnNewIntentListener) }
        session.listener = null
        session.permission?.unregister()
        session.permission = null
        session.external?.close()
        session.permissionScope = null
        session.initialDelivery = null
        session.activity.clear()
    }
    override fun shutdown() {
        closed = true
        runtime.application.unregisterActivityLifecycleCallbacks(this)
        sessions.toList().forEach(::remove)
    }
    override fun onActivityDestroyed(activity: Activity) { sessions.firstOrNull { it.activity.get() === activity }?.let(::remove) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
