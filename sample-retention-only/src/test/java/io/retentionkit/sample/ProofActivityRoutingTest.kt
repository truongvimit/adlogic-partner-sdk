package io.retentionkit.sample

import android.os.Looper
import android.widget.TextView
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Actual Activity/capture/core UI gate; no mocked route verdict or fake SDK completion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ProofApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ProofActivityRoutingTest {
    private lateinit var runtime: RetentionRuntime
    private var controller: ActivityController<ProofActivity>? = null
    private var dispatches = 0
    private lateinit var token: String
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication() as ProofApplication
        runtime = checkNotNull(RetentionRuntime.get())
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.ProcessForeground)
        val real = app.profile
        app.profile = object : ProofProfile by real {
            override fun dispatchPending(runtime: RetentionRuntime, token: String): ProofRoute {
                dispatches++
                return real.dispatchPending(runtime, token)
            }
        }
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        RetentionRuntime.uninstallForTests()
    }

    private fun launchUnderExternalTransition() {
        runtime.signal(RetentionSignal.ExternalTransitionStarted("feedback.source", "feature", 120_000))
        val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, "word_count", "rescue_feature")
        token = entry.token
        controller = Robolectric.buildActivity(ProofActivity::class.java, runtime.createEntryIntent(entry)).setup().visible()
        main.idle()
        assertNotNull(runtime.entries.pending(token))
        assertTrue(status().contains("Tool: uppercase; pending entry: true"))
    }

    private fun status(): String = controller!!.get().findViewById<TextView>(R.id.rk_proof_status).text.toString()

    @Test fun feedbackSourceFinishingAfterFirstResumeRoutesWithoutAnotherHomeCycle() {
        launchUnderExternalTransition()
        // Models the source FeedbackActivity's onDestroy closing its own external scope.
        runtime.signal(RetentionSignal.ExternalTransitionFinished("feedback.source"))
        main.idleFor(Duration.ofMillis(500))
        assertNull("The resumed destination must retry after the source scope closes", runtime.entries.pending(token))
        assertTrue(status().contains("Tool: word_count; pending entry: false"))
        val completedCalls = dispatches
        main.idleFor(Duration.ofSeconds(2))
        assertEquals("A consumed entry must not keep retrying", completedCalls, dispatches)
    }

    @Test fun pauseCancelsRetryAndNextResumeConsumesTheStillPendingEntry() {
        launchUnderExternalTransition()
        controller!!.pause()
        val pausedCalls = dispatches
        runtime.signal(RetentionSignal.ExternalTransitionFinished("feedback.source"))
        main.idleFor(Duration.ofSeconds(2))
        assertNotNull(runtime.entries.pending(token))
        assertEquals("No route effects while paused", pausedCalls, dispatches)
        controller!!.resume()
        main.idle()
        assertNull(runtime.entries.pending(token))
        assertTrue(status().contains("Tool: word_count; pending entry: false"))
    }

    @Test fun persistentUiBlockStopsAutomaticRetryButPreservesTheEntry() {
        launchUnderExternalTransition()
        main.idleFor(Duration.ofSeconds(10))
        assertTrue("Readiness is retried within a bounded window", dispatches in 2..21)
        val boundedCalls = dispatches
        main.idleFor(Duration.ofSeconds(10))
        assertEquals("A blocked entry cannot poll indefinitely", boundedCalls, dispatches)
        assertNotNull(runtime.entries.pending(token))
        assertTrue(status().contains("pending entry: true"))
    }
}
