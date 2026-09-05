package io.onboardkit.ui.splash

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    fun `an answer inside the round trip is returned`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            hasAnswered = { false },
            request = { delay(1_000); true },
        )

        assertTrue(granted)
    }

    /**
     * `false` is not a refusal — it is "no answer yet", the one case that holds ads back. A refusal
     * answers `true`: the flow resolves DENIED and the ads run non-personalized.
     */
    @Test
    fun `no answer yet inside the round trip holds the gate shut`() = runTest {
        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { false },
            hasAnswered = { false },
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
            hasAnswered = { false },
            request = { answered.await() },
        )

        assertTrue(granted)
        assertEquals(40_000, testScheduler.currentTime - startedAt)
    }

    /**
     * The other half of "still open": the round trip is in the air and no form has appeared yet.
     *
     * `ConsentCenter` arms a deadline of its own for exactly this, and that one fails **open** —
     * it resolves the step `true` and lets the ads run, because a slow network is not a refusal.
     * The splash's deadline expires a hair earlier, so giving up there would answer `false` and
     * shut the gate moments before the flow was going to open it.
     */
    @Test
    fun `a round trip still in the air outlives the deadline and takes its fail-open answer`() = runTest {
        val startedAt = testScheduler.currentTime
        val answered = CompletableDeferred<Boolean>()
        // ConsentCenter's own 20s timer, firing just after the splash's and resolving open.
        launch { delay(20_050); answered.complete(true) }

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            hasAnswered = { false },
            request = { answered.await() },
        )

        assertTrue(granted)
        assertEquals(20_050, testScheduler.currentTime - startedAt)
    }

    /**
     * UMP can leave a non-cancelable form on screen with no terminal left to fire — a dead WebView
     * renderer does exactly that. The wait has to end anyway, because finishing the splash is what
     * destroys the window that orphaned dialog lives on.
     */
    @Test
    fun `a form that never answers is given up on at the ceiling`() = runTest {
        val startedAt = testScheduler.currentTime

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            hasAnswered = { false },
            request = { CompletableDeferred<Boolean>().await() },
        )

        assertFalse(granted)
        // The default ceiling, spelled out: 20s round trip + the 180s production ceiling.
        assertEquals(200_000, testScheduler.currentTime - startedAt)
    }

    /**
     * The ceiling is an escape from a form the user is stuck looking at. Out of sight there is
     * nothing to escape, and handing off from the background would finish the splash without
     * starting anything.
     */
    @Test
    fun `the ceiling does not fire while the screen is in the background`() = runTest {
        val startedAt = testScheduler.currentTime
        var visible = false
        val answered = CompletableDeferred<Boolean>()
        // Away in a browser reading the privacy policy for well past the ceiling, then back to
        // accept.
        launch { delay(500_000); visible = true; answered.complete(true) }

        val granted = awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { true },
            hasAnswered = { false },
            isVisible = { visible },
            formWaitMs = 180_000,
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
            hasAnswered = { false },
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
            hasAnswered = { true },
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
            hasAnswered = { true },
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
            hasAnswered = { false },
            request = { delay(1_000); true },
        )
        assertEquals(0, asked)

        awaitConsentAnswer(
            roundTripMs = 20_000,
            isResolving = { asked++; false },
            hasAnswered = { false },
            request = { CompletableDeferred<Boolean>().await() },
        )
        assertEquals(1, asked)
    }
}
