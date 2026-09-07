package io.retentionkit.sample

import android.app.Activity
import android.app.Application
import android.content.Intent
import io.retentionkit.core.*

/** Only this core contract is shared. Each selected source directory links its own module APIs. */
internal interface ProofProfile {
    fun modules(): List<RetentionModule> = emptyList()
    fun install(application: Application, options: RetentionOptions): String = when (
        val result = RetentionRuntime.install(application, options.copy(modules = modules()))
    ) {
        is RetentionInstallResult.Installed -> "Installed: ${modules().joinToString { it.id }.ifEmpty { "core" }}"
        is RetentionInstallResult.Failed -> "Install failed: ${result.reasons.joinToString()}"
    }
    fun actions(activity: Activity, runtime: RetentionRuntime): List<ProofAction> = emptyList()
    fun entryAccepted(entry: RetentionEntry) {}
    fun capture(runtime: RetentionRuntime, intent: Intent?): RetentionEntryAcceptance = runtime.entries.capture(intent).also {
        if (it is RetentionEntryAcceptance.Accepted) entryAccepted(it.entry)
    }
    fun dispatchPending(runtime: RetentionRuntime, token: String): ProofRoute {
        val entry = runtime.entries.pending(token) ?: return ProofRoute.Blocked("missing_or_consumed")
        if (!runtime.userState.setupCompleted) return ProofRoute.Blocked("setup_incomplete")
        val gate = runtime.ui.eligibility()
        if (gate is RetentionEligibility.Blocked) return ProofRoute.Blocked(gate.reason.name.lowercase())
        if (runtime.features().none { it.id == entry.destination }) return ProofRoute.Blocked("unknown_destination")
        return if (runtime.entries.consume(token)) ProofRoute.Navigate(entry) else ProofRoute.Blocked("already_consumed")
    }
}
internal sealed class ProofRoute {
    data class Navigate(val entry: RetentionEntry) : ProofRoute()
    data object SdkHandled : ProofRoute()
    data class Blocked(val reason: String) : ProofRoute()
}
internal data class ProofAction(val label: String, val run: () -> String)
