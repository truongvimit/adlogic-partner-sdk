package io.paykit

import androidx.annotation.RawRes
import java.net.URI

enum class PayKitLogLevel { NONE, ERROR, WARN, INFO, DEBUG }

class PayKitConfigException(val errors: List<String>) :
    IllegalArgumentException("Invalid PayKit config:\n" + errors.joinToString("\n"))

/**
 * Validated compile-time configuration. Build it with [payKitConfig]; validation returns a
 * [Result] instead of throwing from a constructor.
 */
class PayKitConfig private constructor(
    val termsUrl: String,
    val privacyUrl: String,
    val defaultPlacements: Set<PaywallPlacement>,
    val exitButtonDelayMs: Long,
    val singleClickWindowMs: Long,
    val logLevel: PayKitLogLevel,
    @RawRes val fallbackConfigRes: Int,
) {

    internal companion object {
        fun from(builder: PayKitConfigBuilder): PayKitConfig = PayKitConfig(
            termsUrl = builder.termsUrl.trim(),
            privacyUrl = builder.privacyUrl.trim(),
            defaultPlacements = builder.defaultPlacements.toSet(),
            exitButtonDelayMs = builder.exitButtonDelayMs,
            singleClickWindowMs = builder.singleClickWindowMs,
            logLevel = builder.logLevel,
            fallbackConfigRes = builder.fallbackConfigRes,
        )
    }
}

class PayKitConfigBuilder internal constructor() {

    var termsUrl: String = ""
    var privacyUrl: String = ""

    /** Empty is fail-closed: nothing shows until remote config or the host names a placement. */
    var defaultPlacements: Set<PaywallPlacement> = emptySet()

    var exitButtonDelayMs: Long = 0
    var singleClickWindowMs: Long = 700
    var logLevel: PayKitLogLevel = PayKitLogLevel.WARN

    @RawRes
    var fallbackConfigRes: Int = 0

    internal fun build(): Result<PayKitConfig> {
        val errors = mutableListOf<String>()
        validateUrl("termsUrl", termsUrl, errors)
        validateUrl("privacyUrl", privacyUrl, errors)
        if (exitButtonDelayMs < 0) {
            errors += "[exitButtonDelayMs] must be >= 0, was $exitButtonDelayMs"
        }
        if (singleClickWindowMs <= 0) {
            errors += "[singleClickWindowMs] must be > 0, was $singleClickWindowMs"
        }
        return if (errors.isEmpty()) {
            Result.success(PayKitConfig.from(this))
        } else {
            Result.failure(PayKitConfigException(errors))
        }
    }

    // Both links are a store-policy requirement, not decoration: a blank or non-http value ships
    // a paywall whose legal buttons open an empty URI.
    private fun validateUrl(name: String, value: String, errors: MutableList<String>) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            errors += "[$name] must not be blank — the paywall needs a reachable legal link"
            return
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            errors += "[$name] must be an http(s) URL with a host, was \"$trimmed\""
        }
    }
}

/** Every problem is collected into one failure, so a partner fixes the config in one pass. */
fun payKitConfig(block: PayKitConfigBuilder.() -> Unit): Result<PayKitConfig> =
    PayKitConfigBuilder().apply(block).build()
