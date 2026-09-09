package io.onboardkit.ui.splash

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent step's one rule: the deadline bounds the round trip, never the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitConsentAnswerTest {

    @Test
    fun `a visible form held over ten minutes cannot complete the splash step before its answer`() = runTest {
        var mayRequestAds = false
        var completedAt: Long? = null
        val answer = CompletableDeferred<Boolean>()
        val step = async {
            awaitConsentAnswer(
                roundTripMs = 20_000,
                isResolving = { true },
                canRequestAds = { mayRequestAds },
                request = { answer.await() },
            ).also { completedAt = testScheduler.currentTime }
        }

        advanceTimeBy(610_000)
        runCurrent()
        val completedBeforeAnswer = step.isCompleted
        assertFalse(mayRequestAds)

        mayRequestAds = true
        answer.complete(true)
        runCurrent()
        val granted = step.await()

        assertFalse("Splash step completed at ${completedAt}ms before the form answer at 610000ms", completedBeforeAnswer)
        assertTrue(granted)
        assertEquals(610_000L, completedAt)
    }

    @Test
    fun `a successful callback cannot overrule authorization revoked before resuming`() = runTest {
        var mayRequestAds = true

        val allowed = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { mayRequestAds },
            request = {
                mayRequestAds = false
                true
            },
        )

        assertFalse(allowed)
    }

    @Test
    fun `an answer inside the round trip is returned`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { true },
            request = { delay(1_000); true },
        )

        assertTrue(granted)
    }

    /**
     * Step completion cannot grant permission when the authoritative source does not allow it.
     * A personalization refusal is separate: UMP may still authorize a non-personalized request.
     */
    @Test
    fun `no answer yet inside the round trip holds the gate shut`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { false },
            request = { delay(1_000); false },
        )

        assertFalse(granted)
    }

    /** The whole point: a form on screen outlives the deadline rather than being cut off by it. */
    @Test
    fun `a form still open past the deadline keeps waiting for the user`() = runTest {
        val startedAt = testScheduler.currentTime
        val answered = CompletableDeferred<Boolean>()
        // The user reads the form for twice the round-trip bound, then accepts.
        launch { delay(40_000); answered.complete(true) }

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            canRequestAds = { true },
            request = { answered.await() },
        )

        assertTrue(granted)
        assertEquals(40_000, testScheduler.currentTime - startedAt)
    }

    /**
     * The other half of "still open": the round trip is in the air and no form has appeared yet.
     *
     * `ConsentCenter` arms its own deadline for this. When UMP still authorizes requests from
     * a previous session, a slow update does not revoke that permission. The splash must wait for
     * the terminal without cutting off a live flow just before its callback arrives.
     */
    @Test
    fun `a live round trip reaches its terminal while previous authorization remains valid`() = runTest {
        val startedAt = testScheduler.currentTime
        val answered = CompletableDeferred<Boolean>()
        // ConsentCenter's own 20s timer fires just after the splash's; prior permission is valid.
        launch { delay(20_050); answered.complete(true) }

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            canRequestAds = { true },
            request = { answered.await() },
        )

        assertTrue(granted)
        assertEquals(20_050, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `first launch timeout fallback releases splash with the newly opened request gate`() = runTest {
        var mayRequestAds = false
        var resolving = true
        val answered = CompletableDeferred<Boolean>()
        val step = async {
            awaitConsentAnswer(
                roundTripMs = 20_000,
                isResolving = { resolving },
                canRequestAds = { mayRequestAds },
                request = { answered.await() },
            )
        }

        // The splash timer can precede ConsentCenter's timer slightly; it must keep waiting.
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(step.isCompleted)
        assertFalse(mayRequestAds)

        advanceTimeBy(50)
        mayRequestAds = true
        resolving = false
        answered.complete(true)
        runCurrent()

        assertTrue(step.await())
        assertEquals(20_050, testScheduler.currentTime)
    }

    @Test
    fun `an unanswered form waits until the owning scope cancels its request`() = runTest {
        var requestCancelled = false
        val step = async {
            awaitConsentAnswer(
                roundTripMs = 20_000,
                isResolving = { true },
                canRequestAds = { false },
                request = {
                    try {
                        CompletableDeferred<Boolean>().await()
                    } finally {
                        requestCancelled = true
                    }
                },
            )
        }

        try {
            advanceTimeBy(610_000)
            runCurrent()
            assertFalse(step.isCompleted)
            assertFalse(requestCancelled)
        } finally {
            step.cancelAndJoin()
        }

        assertTrue(step.isCancelled)
        assertTrue(requestCancelled)
    }

    @Test
    fun `an answer after a long pause is still returned`() = runTest {
        val startedAt = testScheduler.currentTime
        val answered = CompletableDeferred<Boolean>()
        launch { delay(500_000); answered.complete(true) }

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            canRequestAds = { true },
            request = { answered.await() },
        )

        assertTrue(granted)
        assertEquals(500_000, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `nothing alive at the deadline gives up rather than hanging`() = runTest {
        val elapsed = testScheduler.currentTime

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { false },
            request = { CompletableDeferred<Boolean>().await() },
        )

        assertFalse(granted)
        assertEquals(20_000, testScheduler.currentTime - elapsed)
    }

    /**
     * A terminal can answer `false` outright — no network, a form that came back to a dead screen —
     * on a launch where an earlier flow in the same process already resolved. Passing that `false`
     * on lowered a gate nothing would raise again.
     */
    @Test
    fun `an outright no does not lower a gate an earlier answer opened`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { true },
            request = { delay(100); false },
        )

        assertTrue(granted)
    }

    /** An answer published while its callback was in flight still counts. */
    @Test
    fun `an answer already recorded is read when the deadline expires empty`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            canRequestAds = { true },
            request = { CompletableDeferred<Boolean>().await() },
        )

        assertTrue(granted)
    }

    /** `isResolving` is asked once, at the deadline — never before it, never in a poll loop. */
    @Test
    fun `the live-flow check is consulted only when the deadline expires`() = runTest {
        var asked = 0

        awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { asked++; false },
            canRequestAds = { false },
            request = { delay(1_000); true },
        )
        assertEquals(0, asked)

        awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { asked++; false },
            canRequestAds = { false },
            request = { CompletableDeferred<Boolean>().await() },
        )
        assertEquals(1, asked)
    }
}
