package io.retentionkit.review

import io.retentionkit.core.RetentionConfigSnapshot

data class ReviewOptions @JvmOverloads constructor(
    val enabled: Boolean = true,
    val successThreshold: Int = 5,
    val cooldownDays: Int = 10,
    val maxAttempts: Int = 3,
    val retryBackoffMillis: Long = 300_000,
    val requestTimeoutMillis: Long = 15_000,
    val flowTimeoutMillis: Long = 120_000,
) {
    internal fun validation(): List<String> = buildList {
        if (successThreshold !in 1..1000) add("success_threshold must be 1..1000")
        if (cooldownDays !in 1..365) add("cooldown_days must be 1..365")
        if (maxAttempts !in 1..10) add("max_attempts must be 1..10")
        if (retryBackoffMillis !in 1000..86_400_000) add("retry_backoff_ms must be 1000..86400000")
        if (requestTimeoutMillis !in 1000..60_000) add("request_timeout_ms must be 1000..60000")
        if (flowTimeoutMillis !in 1000..180_000) add("flow_timeout_ms must be 1000..180000")
    }
    internal fun resolved(config: RetentionConfigSnapshot) = copy(
        enabled = config.boolean("review.enabled", enabled),
        successThreshold = config.long("review.success_threshold", successThreshold.toLong()).toInt(),
        cooldownDays = config.long("review.cooldown_days", cooldownDays.toLong()).toInt(),
        maxAttempts = config.long("review.max_attempts", maxAttempts.toLong()).toInt(),
        retryBackoffMillis = config.long("review.retry_backoff_ms", retryBackoffMillis),
        requestTimeoutMillis = config.long("review.request_timeout_ms", requestTimeoutMillis),
        flowTimeoutMillis = config.long("review.flow_timeout_ms", flowTimeoutMillis),
    )
}

sealed class ReviewActionResult {
    data object Scheduled : ReviewActionResult()
    data class Unavailable(val reason: String) : ReviewActionResult()
}

data class ReviewSnapshot(
    val successesSinceAttempt: Long,
    val attempts: Long,
    val lastAttemptAtMillis: Long,
    val retryAfterMillis: Long,
    val inFlightPhase: String?,
)
