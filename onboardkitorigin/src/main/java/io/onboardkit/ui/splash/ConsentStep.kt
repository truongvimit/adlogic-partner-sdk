package io.onboardkit.ui.splash

import io.onboardkit.core.ObLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * [roundTripMs] bounds a request with no SDK-owned consent flow. Once [isResolving] reports a
 * live flow, wait for its result without limiting the user's reading time. The owning lifecycle
 * can still cancel the wait. ConsentCenter owns network timeouts and vendor error callbacks.
 *
 * On the default path [roundTripMs] rarely decides anything, because the consent flow arms a round
 * trip deadline of its own. A timeout finishes the step without inventing authorization. A host
 * using its own consent provider must publish that provider's authorization before completing
 * [request]; its wait is still bounded by [roundTripMs] when no SDK-owned flow is resolving.
 *
 * [canRequestAds] is the final authority, including after a successful callback. Permission can
 * be revoked between callback delivery and this coroutine resuming. Finishing the step and being
 * allowed to request ads are separate outcomes.
 */
internal suspend fun awaitConsentAnswer(
    roundTripMs: Long,
    isResolving: () -> Boolean,
    canRequestAds: () -> Boolean,
    request: suspend () -> Boolean,
): Boolean = coroutineScope {
    val startedAt = System.currentTimeMillis()
    val answer = async { request() }
    // withTimeoutOrNull cancels its own child — the await — never the async, which belongs to this
    // scope. The answer survives the deadline and can still be waited on.
    var granted = withTimeoutOrNull(roundTripMs.milliseconds) { answer.await() }
    if (granted == null && isResolving()) {
        ObLog.d(ObLog.Section.SPLASH, "consent still open after ${roundTripMs}ms — waiting for the answer")
        granted = answer.await()
    }
    val elapsed = System.currentTimeMillis() - startedAt
    if (granted == null) {
        answer.cancel()
        ObLog.w(ObLog.Section.SPLASH, "consent UNANSWERED after ${elapsed}ms — running the flow without ads")
    } else {
        ObLog.d(ObLog.Section.SPLASH, "consent done ms=$elapsed")
    }
    // Re-read authority even after a true callback: that earlier answer may already be stale.
    canRequestAds()
}
