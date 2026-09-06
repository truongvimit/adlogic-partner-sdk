package io.retentionkit.widgets

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.retentionkit.core.*
import java.lang.ref.WeakReference
import java.util.UUID

internal class WidgetPinCoordinator(private val module: RetentionWidgets) {
    private val runtime get() = module.runtime
    private var activeToken: String? = null
    private var wentBackground = false
    private val timeouts = mutableMapOf<String, Runnable>()
    private var invitation: WeakReference<AlertDialog>? = null
    private var invitationLease: RetentionUiLease? = null
    private var invitationCheck: Runnable? = null

    fun restore() {
        expire()
        module.state.pins().filter { it.status == "pending" }.forEach(::scheduleTimeout)
    }

    fun request(): WidgetPinResult {
        capabilityFailure()?.let { return it }
        expire()
        module.state.pins().firstOrNull { it.status == "pending" }?.let { return WidgetPinResult.Duplicate(it.token) }
        return when (val result = runtime.ui.acquire("widgets.pin")) {
            is RetentionUiLeaseResult.Blocked -> WidgetPinResult.Blocked(result.reason)
            is RetentionUiLeaseResult.Acquired -> handoff(result.lease, runtime.config.revision)
        }
    }

    private fun capabilityFailure(): WidgetPinResult? = when (val capability = module.pinCapability()) {
        RetentionCapability.Available -> null
        is RetentionCapability.Unavailable -> WidgetPinResult.Unavailable(capability.reason)
        is RetentionCapability.Unknown -> WidgetPinResult.Failed(capability.reason)
    }

    private fun handoff(lease: RetentionUiLease, revision: Long): WidgetPinResult {
        var record: PinRecord? = null
        try {
            capabilityFailure()?.let { return it }
            expire()
            module.state.pins().firstOrNull { it.status == "pending" }?.let { return WidgetPinResult.Duplicate(it.token) }
            val now = runtime.clock.wallTimeMillis()
            val timeout = runtime.config.long("widgets.pin.timeout_ms", 120_000)
            val token = UUID.randomUUID().toString()
            val candidate = PinRecord(token, module.provider.flattenToString(), now, now + timeout, module.platform.ids(module.provider).toList())
            val callback = callbackIntent(token)
            // Persist the random callback capability before system code can deliver it, including
            // cold process delivery. No side effects occur inside this store transaction.
            module.state.savePin(candidate)
            record = candidate
            val activity = lease.activity()
            if (!module.current(revision) || activity == null) {
                fail(candidate, "ui_or_config_changed")
                callback.cancel()
                return WidgetPinResult.Failed("ui_or_config_changed")
            }
            activeToken = token
            wentBackground = false
            // This intentionally revokes the lease. The last lease/Activity check MUST precede it.
            runtime.signal(RetentionSignal.ExternalTransitionStarted(transitionToken(token), "widget_pin", timeout))
            // Guard synchronous host observers that changed config or navigated during the signal.
            if (!module.current(revision) || !runtime.isForeground || runtime.activities.current() !== activity || runtime.userState.onboardingActive) {
                fail(candidate, "handoff_changed")
                callback.cancel()
                return WidgetPinResult.Failed("handoff_changed")
            }
            val accepted = module.platform.requestPin(module.provider, callback)
            // The local Activity variable is never captured by any callback/timer.
            if (!accepted) {
                module.state.savePin(candidate.copy(status = "unsupported", reason = "launcher_rejected_request"))
                finish(token)
                callback.cancel()
                return WidgetPinResult.Unavailable("launcher_rejected_request")
            }
            module.event("pin_requested", mapOf("token" to token))
            val latest = module.state.pin(token) ?: candidate
            if (latest.status == "pending") scheduleTimeout(latest)
            return latest.result()
        } catch (error: Exception) {
            module.diagnose("pin_request", error)
            record?.let { candidate -> module.guard("pin_failure") { fail(candidate, "platform_error") } }
            // Always release this owner's transition even if persistence failed.
            record?.let { finish(it.token) }
            return WidgetPinResult.Failed("platform_error")
        } finally { lease.close() }
    }

    internal fun callbackIntent(token: String): PendingIntent {
        val intent = Intent(runtime.application, WidgetPinReceiver::class.java)
            .setAction(CALLBACK_ACTION)
            .setData(Uri.Builder().scheme("retentionkit").authority("widget-pin").appendPath(token).build())
        // The launcher must fill EXTRA_APPWIDGET_ID. Immutable would discard that evidence.
        // Mutation is restricted to this explicit, non-exported, one-shot receiver capability.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(runtime.application, 0, intent, PendingIntent.FLAG_ONE_SHOT or mutable)
    }

    fun confirm(token: String, widgetId: Int) {
        val record = module.state.pin(token) ?: return
        if (record.status !in setOf("pending", "unknown")) return
        val now = runtime.clock.wallTimeMillis()
        if (now < record.created || now - record.created > CALLBACK_RETENTION_MS || record.provider != module.provider.flattenToString() ||
            widgetId in record.baseline || !module.owns(widgetId)) {
            module.event("pin_callback_rejected", mapOf("token" to token))
            return
        }
        // A callback identifies an actual new instance of the requested provider. The request's
        // positive API return, widget update broadcast, or mere new instance never proves a pin.
        module.state.savePin(record.copy(status = "confirmed", widgetId = widgetId))
        finish(token)
        module.event("pin_confirmed", mapOf("token" to token, "widget_id" to widgetId.toString()))
        module.update(intArrayOf(widgetId))
    }

    fun expire() {
        val now = runtime.clock.wallTimeMillis()
        module.state.pins().filter { it.status == "pending" && (now >= it.deadline || now < it.created) }
            .forEach { unknown(it, "timeout") }
    }
    fun onBackground() { if (activeToken != null) wentBackground = true }
    fun onForeground() {
        if (wentBackground) {
            activeToken?.let { token -> module.state.pin(token)?.takeIf { it.status == "pending" }?.let { unknown(it, "returned_without_callback") } }
        }
    }
    fun disable() {
        closeInvitation()
        module.state.pins().filter { it.status == "pending" }.forEach { unknown(it, "disabled") }
    }
    fun shutdown() {
        closeInvitation()
        timeouts.values.forEach { module.main.removeCallbacks(it) }
        timeouts.clear()
        activeToken?.let(::finish)
    }

    private fun scheduleTimeout(record: PinRecord) {
        timeouts.remove(record.token)?.let { module.main.removeCallbacks(it) }
        val timeout = Runnable {
            timeouts.remove(record.token)
            if (!module.closed) module.guard("pin_timeout") {
                module.state.pin(record.token)?.takeIf { it.status == "pending" }?.let { unknown(it, "timeout") }
            }
        }
        timeouts[record.token] = timeout
        module.main.postDelayed(timeout, (record.deadline - runtime.clock.wallTimeMillis()).coerceIn(1, 300_000))
    }
    private fun fail(record: PinRecord, reason: String) {
        module.state.savePin(record.copy(status = "failed", reason = reason))
        finish(record.token)
        module.event("pin_failed", mapOf("token" to record.token, "reason" to reason))
    }
    private fun unknown(record: PinRecord, reason: String) {
        module.state.savePin(record.copy(status = "unknown", reason = reason))
        finish(record.token)
        module.event("pin_unknown", mapOf("token" to record.token, "reason" to reason))
    }
    private fun finish(token: String) {
        timeouts.remove(token)?.let { module.main.removeCallbacks(it) }
        if (activeToken == token) { activeToken = null; wentBackground = false }
        runtime.signal(RetentionSignal.ExternalTransitionFinished(transitionToken(token)))
    }

    fun showInvitation(): WidgetInvitationResult {
        when (val capability = module.pinCapability()) {
            is RetentionCapability.Unavailable -> return WidgetInvitationResult.Unavailable(capability.reason)
            is RetentionCapability.Unknown -> return WidgetInvitationResult.Failed(capability.reason)
            RetentionCapability.Available -> Unit
        }
        val lease = when (val result = runtime.ui.acquire("widgets.invitation", 60_000)) {
            is RetentionUiLeaseResult.Blocked -> return WidgetInvitationResult.Blocked(result.reason)
            is RetentionUiLeaseResult.Acquired -> result.lease
        }
        val revision = runtime.config.revision
        try {
            val localized = runtime.localizedContext()
            val activity = lease.activity() ?: return WidgetInvitationResult.Failed("activity_unavailable").also { lease.close() }
            var accepted = false
            val dialog = AlertDialog.Builder(activity)
                .setTitle(localized.getString(R.string.rk_widget_invitation_title))
                .setMessage(localized.getString(R.string.rk_widget_invitation_message))
                .setPositiveButton(localized.getString(R.string.rk_widget_add)) { _, _ ->
                    accepted = true
                    module.guard("invitation_accept") { handoff(lease, revision) }
                }
                .setNegativeButton(localized.getString(R.string.rk_widget_not_now), null)
                .create()
            dialog.setOnDismissListener {
                if (!accepted) module.event("invitation_dismissed")
                lease.close()
                if (invitationLease === lease) clearInvitationReferences()
            }
            if (!module.current(revision) || lease.activity() == null) {
                lease.close()
                return WidgetInvitationResult.Failed("ui_or_config_changed")
            }
            invitation = WeakReference(dialog)
            invitationLease = lease
            dialog.show()
            val check = object : Runnable {
                override fun run() {
                    if (module.closed || !module.current(revision) || !lease.isValid() || invitation?.get()?.isShowing != true) closeInvitation()
                    else module.main.postDelayed(this, 250)
                }
            }
            invitationCheck = check
            module.main.postDelayed(check, 250)
            module.event("invitation_shown")
            return WidgetInvitationResult.Shown
        } catch (error: Exception) {
            lease.close()
            closeInvitation()
            module.diagnose("invitation", error)
            return WidgetInvitationResult.Failed("dialog_error")
        }
    }
    private fun closeInvitation() {
        invitation?.get()?.let { dialog -> module.guard("dialog_dismiss") { dialog.dismiss() } }
        invitationLease?.close()
        clearInvitationReferences()
    }
    private fun clearInvitationReferences() {
        invitationCheck?.let { module.main.removeCallbacks(it) }
        invitationCheck = null
        invitation = null
        invitationLease = null
    }
    private fun transitionToken(token: String) = "widgets.pin:$token"

    companion object {
        const val CALLBACK_ACTION = "io.retentionkit.widgets.PIN_CONFIRMED"
        private const val CALLBACK_RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
