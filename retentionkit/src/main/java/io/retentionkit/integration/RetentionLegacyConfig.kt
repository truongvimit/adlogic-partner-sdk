package io.retentionkit.integration

import io.retentionkit.core.RetentionDiagnosticLevel
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.notifications.NotificationLegacyConfig

data class RetentionLegacyMapping(val overrides: Map<String, String>, val unsupportedKeys: Set<String>)

/** Common MO keys plus verified historical suite keys. No Firebase dependency or feature names.
 * Values remain strings so the installed modules validate one atomic document and keep last-good
 * state on invalid input. In particular, cooldown days stay days, never unchecked milliseconds.
 */
object RetentionLegacyConfig {
    private val suiteKeys = mapOf(
        "notiUninstallShortcutEnabled" to "feedback.shortcut_enabled",
        "notiWidgetPromptEnabled" to "widgets.invitation.enabled",
        "rateEnabled" to "review.enabled",
        "rateMinSuccesses" to "review.success_threshold",
        "rateCooldownDays" to "review.cooldown_days",
        "rateMaxPrompts" to "review.max_attempts",
    )
    private val capabilityKeys = setOf("noti_lockscreen_wake_seconds", "noti_lockscreen_content", "notiForPremiumUsers")
    @JvmField val keys: Set<String> = (NotificationLegacyConfig.keys + suiteKeys.keys + capabilityKeys).toSet()

    @JvmStatic fun map(values: Map<String, String>): RetentionLegacyMapping {
        val notifications = NotificationLegacyConfig.map(values.filterKeys { it in NotificationLegacyConfig.keys })
        val suite = values.filterKeys { it in suiteKeys }.mapKeys { suiteKeys.getValue(it.key) }
        val unsupported = values.keys - NotificationLegacyConfig.keys - suiteKeys.keys -
            if (values["notiForPremiumUsers"] == "false") setOf("notiForPremiumUsers") else emptySet()
        return RetentionLegacyMapping(notifications.overrides + suite, unsupported + notifications.unsupportedKeys)
    }

    /** Convenient mapper for an external config source; unsupported keys produce diagnostics,
     * never bypass entitlement or pretend to support forced wake/network content.
     */
    @JvmStatic fun overrides(values: Map<String, String>): Map<String, String> = map(values).also { mapped ->
        if (mapped.unsupportedKeys.isNotEmpty()) RetentionRuntime.get()?.diagnostics?.record(
            "legacy-config", "Unsupported remote keys: ${mapped.unsupportedKeys.sorted().joinToString()}", RetentionDiagnosticLevel.WARNING)
    }.overrides
}
