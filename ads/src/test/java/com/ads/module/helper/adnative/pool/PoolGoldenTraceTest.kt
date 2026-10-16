package com.ads.module.helper.adnative.pool

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays a trace taken from a DashDrama 1.0.6 device log (see the fixture header) and checks that
 * the engine issues exactly DD's requests, in DD's order, and that every impression DD logged comes
 * from the ad the selector hands the slot on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolGoldenTraceTest {
    private class Step(val atMs: Long, val verb: String, val args: List<String>, val text: String) {
        override fun toString(): String = text
    }

    private class Trace(val placements: Map<String, List<String>>, val steps: List<Step>)

    @Test fun `a fast fills - sync gate and selector`() {
        val h = PoolHarness(SYNC, maxAgeMs = MAX_AGE_MS)
        replay(load("a_fast_fills.trace"), h, settle = {}) {
            (h.engine.selection as StateFlow<PoolSelection<FakeAd>>).value
        }
    }

    @Test fun `a fast fills - async gate and selector settled between steps`() = runTest {
        val h = PoolHarness(PoolParity(), maxAgeMs = MAX_AGE_MS, dispatcher = StandardTestDispatcher(testScheduler))
        replay(load("a_fast_fills.trace"), h, settle = { runCurrent() }) {
            (h.engine.selection as SharedFlow<PoolSelection<FakeAd>>).replayCache.last()
        }
        h.engine.close()
    }

    private fun replay(trace: Trace, h: PoolHarness, settle: () -> Unit, rendered: () -> PoolSelection<FakeAd>) {
        h.clock.now = trace.steps.first().atMs
        var declared = 0
        for (step in trace.steps) {
            h.scheduler.advanceTo(step.atMs)
            settle()
            if (step.verb == "request") {
                val (key, tier) = step.args
                assertTrue("$step: no request was made", declared < h.loader.calls.size)
                assertEquals("$step", key to tier, h.loader.calls[declared++])
                continue
            }
            assertEquals("requests not in the trace before '$step': ${h.loader.calls.drop(declared)}",
                declared, h.loader.calls.size)
            run(step, trace, h, rendered)
            settle()
        }
        assertEquals("requests not in the trace: ${h.loader.calls.drop(declared)}", declared, h.loader.calls.size)
        assertTrue("no stale fill and no timeout in a fast-fill run",
            h.traces.none { it.startsWith("stale_fill") || it.startsWith("tier_timeout") })
    }

    private fun run(step: Step, trace: Trace, h: PoolHarness, rendered: () -> PoolSelection<FakeAd>) {
        val engine = h.engine
        val key = step.args[0]
        fun request() = PoolRequest(key, trace.placements.getValue(key), layoutRes = 0, adConfigKey = key,
            tierDeadlineMs = TIER_DEADLINE_MS)
        when (step.verb) {
            "claim" -> {
                val result = engine.load(request(), LoadMode.valueOf(step.args[1]))
                assertResult(step, step.args[2], result)
                if (result.reportsSuccess) engine.setOwner(key)
            }
            "preload" -> assertResult(step, step.args[1], engine.load(request(), LoadMode.POLITE))
            "refresh" -> assertEquals("$step", step.args[1].toBooleanStrict(), engine.refresh(key))
            "set_owner" -> engine.setOwner(key)
            "clear_owner" -> engine.clearOwnerIf(key)
            "fail" -> attempt(step, h).fail()
            "fill" -> attempt(step, h).fill()
            "impression" -> {
                val shown = rendered()
                assertEquals("$step: the slot renders another loader", key, shown.lenderKey)
                val ad = checkNotNull(shown.ad) { "$step: nothing rendered" }
                assertEquals("$step: impression from a destroyed ad", 0, ad.destroyed)
                h.loader.impress(ad)
            }
            "select" -> {
                val expected = when (key) {
                    "shimmer" -> PoolSelection.shimmer<FakeAd>()
                    "hidden" -> PoolSelection.hidden<FakeAd>()
                    else -> null
                }
                val actual = rendered()
                assertEquals("$step: rendered differs from the synchronous select", engine.currentSelection(), actual)
                if (expected != null) {
                    assertEquals("$step", expected, actual)
                } else {
                    assertEquals("$step", key, actual.lenderKey)
                    assertTrue("$step", actual.show && actual.ad != null)
                }
            }
            else -> fail("unknown verb in '$step'")
        }
    }

    private fun attempt(step: Step, h: PoolHarness): FakeLoader.Attempt {
        val (key, tier) = step.args
        val attempt = h.loader.last(key)
        assertEquals("$step: the current attempt of $key is another tier", tier, attempt.id)
        assertFalse("$step: attempt already settled", attempt.ad != null)
        return attempt
    }

    private fun assertResult(step: Step, expected: String, result: LoadResult) {
        val actual = when (result) {
            LoadResult.Requested -> "requested"
            is LoadResult.NoOpState -> "noop"
            LoadResult.SkippedByGate -> "gate_skip"
            is LoadResult.SkippedTransient -> "transient_skip"
            is LoadResult.SkippedByConfig -> "config_skip"
        }
        assertEquals("$step", expected, actual)
    }

    private fun load(name: String): Trace {
        val text = requireNotNull(javaClass.classLoader?.getResource("pool/dashdrama/$name")) { "missing $name" }.readText()
        val placements = linkedMapOf<String, List<String>>()
        val steps = mutableListOf<Step>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(Regex("\\s+")).filterNot { it.startsWith("@") }
            if (tokens[0] == "placement") {
                placements[tokens[1]] = tokens.drop(2)
                continue
            }
            steps += Step(timeOfDayMs(tokens[0]), tokens[1], tokens.drop(2), line)
        }
        check(steps.isNotEmpty() && placements.isNotEmpty()) { "empty trace $name" }
        return Trace(placements, steps)
    }

    private fun timeOfDayMs(text: String): Long {
        val (h, m, rest) = text.split(":")
        val (s, ms) = rest.split(".")
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1_000 + ms.toLong()
    }

    private companion object {
        const val MAX_AGE_MS = 3_600_000L
        const val TIER_DEADLINE_MS = 30_000L
    }
}
