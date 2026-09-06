package io.retentionkit.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.TimeZone
import java.util.UUID

interface RetentionClock {
    fun wallTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun timeZone(): TimeZone = TimeZone.getDefault()

    object System : RetentionClock {
        override fun wallTimeMillis(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    }
}

enum class RetentionEntitlement { UNKNOWN, NON_SUBSCRIBER, SUBSCRIBER }
enum class RetentionMarketingPhase { AFTER_SETUP, ONBOARDING }

data class RetentionUserState @JvmOverloads constructor(
    val setupCompleted: Boolean = false,
    val onboardingActive: Boolean = false,
    val entitlement: RetentionEntitlement = RetentionEntitlement.UNKNOWN,
    val installedAtMillis: Long = 0,
    val lastActiveAtMillis: Long = 0,
    val setupCompletedAtMillis: Long = 0,
)

data class RetentionFeature @JvmOverloads constructor(
    val id: String,
    val label: String,
    val iconRes: Int,
    val description: String = "",
    val imageRes: Int? = null,
)

/** The supplied context already uses the selected app locale. Return bundled/cached content only. */
fun interface RetentionFeatureProvider {
    fun features(localizedContext: Context): List<RetentionFeature>
    companion object { @JvmField val EMPTY = RetentionFeatureProvider { emptyList() } }
}

fun interface RetentionLocaleProvider {
    fun localizedContext(applicationContext: Context): Context
    companion object { @JvmField val SYSTEM = RetentionLocaleProvider { it } }
}

/** A router constructs an explicit host Activity intent; core never calls startActivity for entries. */
fun interface RetentionRouter {
    fun createIntent(context: Context, entry: RetentionEntry): Intent?
    companion object { @JvmField val NONE = RetentionRouter { _, _ -> null } }
}

fun interface ForegroundActivityProvider { fun current(): Activity? }

enum class RetentionSuppressionReason {
    NOT_INSTALLED, INVALID_CONFIGURATION, DISABLED, SETUP_INCOMPLETE, ENTITLEMENT_UNKNOWN,
    SUBSCRIBER, FOREGROUND, BACKGROUND, NO_ACTIVITY, HOST_UI, EXTERNAL_TRANSITION,
    PROMPT_BUSY, EXPIRED, PERMISSION, CHANNEL, COOLDOWN, CAP, DUPLICATE, UNAVAILABLE, WRONG_PHASE,
}

sealed class RetentionEligibility {
    data object Allowed : RetentionEligibility()
    data class Blocked(val reason: RetentionSuppressionReason, val detail: String = "") : RetentionEligibility()
}

sealed class RetentionCapability {
    data object Available : RetentionCapability()
    data class Unavailable(val reason: String) : RetentionCapability()
    data class Unknown(val reason: String) : RetentionCapability()
}

/** Transient click/transition state is deliberately not restored after process death. */
sealed interface RetentionSignal {
    data object SetupCompleted : RetentionSignal
    data class OnboardingChanged(val active: Boolean) : RetentionSignal
    data class EntitlementChanged(val entitlement: RetentionEntitlement) : RetentionSignal
    data class BusinessSuccess @JvmOverloads constructor(val featureId: String, val eventId: String = UUID.randomUUID().toString()) : RetentionSignal
    data class AdClicked @JvmOverloads constructor(val clickId: String = UUID.randomUUID().toString()) : RetentionSignal
    data class ExternalTransitionStarted @JvmOverloads constructor(val token: String, val kind: String, val durationMillis: Long = 120_000) : RetentionSignal
    data class ExternalTransitionFinished(val token: String) : RetentionSignal
    data class HostUiChanged @JvmOverloads constructor(val owner: String, val visible: Boolean, val durationMillis: Long = 120_000) : RetentionSignal
    data object ProcessForeground : RetentionSignal
    data object ProcessBackground : RetentionSignal
    data class ConfigurationChanged(val revision: Long) : RetentionSignal
}

/** Immutable last-known-good overrides; absent keys leave each module's defaults intact. */
class RetentionConfigSnapshot internal constructor(val revision: Long, values: Map<String, String>) {
    val values: Map<String, String> = java.util.Collections.unmodifiableMap(values.toMap())
    fun string(key: String, default: String? = null): String? = values[key] ?: default
    fun long(key: String, default: Long): Long = values[key]?.toLongOrNull() ?: default
    fun boolean(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default
}

sealed class RetentionConfigResult {
    data class Applied(val revision: Long) : RetentionConfigResult()
    data class Rejected(val reasons: List<String>) : RetentionConfigResult()
}

/** Methods must be short and nonblocking. Own/cancel any asynchronous work in shutdown(). */
interface RetentionModule {
    val id: String
    fun validateConfig(config: RetentionConfigSnapshot): List<String> = emptyList()
    fun attach(runtime: RetentionRuntime)
    fun onSignal(signal: RetentionSignal) {}
    fun reconcile(reason: String) {}
    fun shutdown() {}
}

fun interface RetentionSubscription : AutoCloseable { override fun close() }

internal val stableIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
internal fun validId(value: String): Boolean = stableIdPattern.matches(value)
