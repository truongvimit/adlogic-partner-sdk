package io.retentionkit.sample

import android.app.Activity
import android.app.Application
import io.retentionkit.core.RetentionEntry
import io.retentionkit.core.RetentionInstallResult
import io.retentionkit.core.RetentionModule
import io.retentionkit.core.RetentionOptions
import io.retentionkit.core.RetentionRuntime

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
}
internal data class ProofAction(val label: String, val run: () -> String)
