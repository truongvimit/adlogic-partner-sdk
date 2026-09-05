package io.onboardkit.ui.splash

import io.onboardkit.core.ObLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long a form already on screen may go unanswered before the splash stops waiting on it.
 *
 * Not a reading budget — nobody spends three minutes looking at a consent form — but the escape
 * hatch the old flat timeout used to provide by accident. UMP handles a dead WebView renderer by
 * destroying the view and returning, without calling the dismissal listener and without dismissing
 * its own non-cancelable full-screen dialog, so a flow can stay unresolved with nothing left that
 * could ever resolve it. Reaching this while the screen is in front of the user means no answer is
 * coming; moving on finishes the splash, and destroying its window is what clears the dead dialog.
 */
private const val FORM_ANSWER_CEILING_MS = 180_000L

/**
 * The splash's consent step, bounded the way the consent flow bounds itself: [roundTripMs] covers
 * the network round trip, and the user's own reading time is covered only by [formWaitMs].
 *
 * Running one deadline over the whole step ran its clock while the user was reading the UMP form,
 * and expiring answered `false` — which shut the ad gate for the rest of the session over an Accept
 * that was one tap away. So the deadline asks [isResolving] before it gives up: `true` means a live
 * screen still owes an answer, and the wait continues on the far more generous [formWaitMs].
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
    isVisible: () -> Boolean = { true },
    formWaitMs: Long = FORM_ANSWER_CEILING_MS,
    request: suspend () -> Boolean,
): Boolean = coroutineScope {
    val startedAt = System.currentTimeMillis()
    val answer = async { request() }
    // withTimeoutOrNull cancels its own child — the await — never the async, which belongs to this
    // scope. The answer survives the deadline and can still be waited on.
    var granted = withTimeoutOrNull(roundTripMs.milliseconds) { answer.await() }
    if (granted == null && isResolving()) {
        ObLog.d(ObLog.Section.SPLASH, "consent still open after ${roundTripMs}ms — waiting for the answer")
        // Only a form the user is actually looking at needs escaping, and giving up out of sight
        // would hand off from the background — which Android blocks, leaving a finished splash and
        // no next screen. A splash sent to the back waits again instead: UMP's privacy policy link
        // opens a browser in its own task, so this is an ordinary way to read the form, not an edge.
        do {
            granted = withTimeoutOrNull(formWaitMs.milliseconds) { answer.await() }
        } while (granted == null && !isVisible())
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
