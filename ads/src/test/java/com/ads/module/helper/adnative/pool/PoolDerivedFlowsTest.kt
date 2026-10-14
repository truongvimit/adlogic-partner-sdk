package com.ads.module.helper.adnative.pool

import com.ads.module.helper.adnative.pool.LoadMode.POLITE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PoolDerivedFlowsTest {
    private val async = PoolParity(asyncGate = true, asyncSelector = true)

    private fun TestScope.harness(parity: PoolParity) =
        PoolHarness(parity, dispatcher = StandardTestDispatcher(testScheduler))

    private fun TestScope.collect(h: PoolHarness): MutableList<PoolSelection<FakeAd>> {
        val values = mutableListOf<PoolSelection<FakeAd>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.engine.selection.toList(values) }
        return values
    }

    @Test fun `the gate starts open in both modes`() = runTest {
        for (parity in listOf(async, SYNC)) {
            val h = harness(parity)
            assertTrue(h.engine.gateOpen.value)
            runCurrent()
            assertTrue(h.engine.gateOpen.value)
            h.engine.close()
        }
    }

    @Test fun `async gate lags - two same-tick POLITE loads on different keys both request`() = runTest {
        val h = harness(async)
        assertEquals(LoadResult.Requested, h.engine.load(request("language2"), POLITE))
        assertEquals(LoadResult.Requested, h.engine.load(request("step_ob1"), POLITE))
        assertEquals(listOf("language2" to "t0", "step_ob1" to "t0"), h.loader.calls)
        runCurrent()
        assertFalse(h.engine.gateOpen.value)
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("step_ob2"), POLITE))
        h.engine.close()
    }

    @Test fun `sync gate - the second same-tick POLITE load is skipped`() = runTest {
        val h = harness(SYNC)
        assertEquals(LoadResult.Requested, h.engine.load(request("language2"), POLITE))
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("step_ob1"), POLITE))
        assertEquals(listOf("language2" to "t0"), h.loader.calls)
        h.engine.close()
    }

    @Test fun `async gate reopens only once the dispatcher maps the settled snapshot`() = runTest {
        val h = harness(async)
        h.engine.load(request("a"), POLITE)
        runCurrent()
        h.loader.fill("a")
        assertFalse(h.engine.gateOpen.value)
        runCurrent()
        assertTrue(h.engine.gateOpen.value)
        h.engine.close()
    }

    @Test fun `async selector emits no initial hidden value before the first publish is processed`() = runTest {
        val h = harness(async)
        assertTrue((h.engine.selection as SharedFlow).replayCache.isEmpty())
        val values = collect(h)
        h.engine.load(request("a"), POLITE)
        assertTrue(values.isEmpty())
        runCurrent()
        assertEquals(listOf(PoolSelection.shimmer<FakeAd>()), values)
        h.engine.close()
    }

    @Test fun `sync selector holds hidden from the start and updates inside the publish`() = runTest {
        val h = harness(SYNC)
        val selection = h.engine.selection as StateFlow<PoolSelection<FakeAd>>
        assertEquals(PoolSelection.hidden<FakeAd>(), selection.value)
        h.engine.load(request("a"), POLITE)
        assertEquals(PoolSelection.shimmer<FakeAd>(), selection.value)
        val ad = h.loader.fill("a")
        assertEquals(PoolSelection("a", ad, true), selection.value)
        h.engine.close()
    }

    @Test fun `async selector replays the latest selection to a late subscriber`() = runTest {
        val h = harness(async)
        h.engine.load(request("a"), POLITE)
        runCurrent()
        val ad = h.loader.fill("a")
        runCurrent()
        assertEquals(PoolSelection("a", ad, true), h.engine.selection.first())
        val late = collect(h)
        assertEquals(listOf(PoolSelection("a", ad, true)), late)
        h.engine.close()
    }

    @Test fun `async selector lags the main thread until the dispatcher runs`() = runTest {
        val h = harness(async)
        val values = collect(h)
        h.engine.load(request("a"), POLITE)
        runCurrent()
        val ad = h.loader.fill("a")
        assertEquals(listOf(PoolSelection.shimmer<FakeAd>()), values)
        assertEquals(PoolSelection("a", ad, true), h.engine.currentSelection())
        runCurrent()
        assertEquals(PoolSelection("a", ad, true), values.last())
        h.engine.close()
    }

    @Test fun `distinctUntilChanged suppresses equal selections from new snapshots`() = runTest {
        val h = harness(async)
        val values = collect(h)
        h.engine.load(request("a"), POLITE)
        runCurrent()
        val version = h.engine.debugSnapshot().version
        h.engine.setOwner("x")
        runCurrent()
        h.engine.clearOwnerIf("y")
        runCurrent()
        assertTrue(h.engine.debugSnapshot().version > version)
        assertEquals(listOf(PoolSelection.shimmer<FakeAd>()), values)
        val ad = h.loader.fill("a")
        runCurrent()
        assertEquals(listOf(PoolSelection.shimmer(), PoolSelection("a", ad, true)), values)
        h.engine.close()
    }

    @Test fun `gate and selector modes are independent`() = runTest {
        val asyncGateOnly = harness(PoolParity(asyncGate = true, asyncSelector = false))
        asyncGateOnly.engine.load(request("a"), POLITE)
        assertTrue(asyncGateOnly.engine.gateOpen.value)
        assertEquals(PoolSelection.shimmer<FakeAd>(), (asyncGateOnly.engine.selection as StateFlow).value)
        asyncGateOnly.engine.close()

        val asyncSelectorOnly = harness(PoolParity(asyncGate = false, asyncSelector = true))
        asyncSelectorOnly.engine.load(request("a"), POLITE)
        assertFalse(asyncSelectorOnly.engine.gateOpen.value)
        assertTrue((asyncSelectorOnly.engine.selection as SharedFlow).replayCache.isEmpty())
        asyncSelectorOnly.engine.close()
    }

    @Test fun `close stops the async derivation`() = runTest {
        val h = harness(async)
        h.engine.close()
        h.engine.load(request("a"), POLITE)
        runCurrent()
        assertTrue(h.engine.gateOpen.value)
        assertTrue((h.engine.selection as SharedFlow).replayCache.isEmpty())
    }

    @Test fun `async gate - a retried POLITE skip never requests inside the skipping call and is replayed after it`() = runTest {
        val h = harness(async.copy(retrySkippedPolite = true))
        val replayed = mutableListOf<Pair<String, LoadResult>>()
        h.engine.addReplayListener { request, result -> replayed += request.key to result }
        h.engine.load(request("busy"), LoadMode.FORCED)
        runCurrent()
        h.loader.fill("busy")
        assertFalse(h.engine.gateOpen.value)
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("a"), POLITE))
        assertTrue(h.loader.callsFor("a").isEmpty())
        assertEquals(1, h.engine.gateSkipCount)
        h.scheduler.advanceBy(0)
        assertEquals(listOf("t0"), h.loader.callsFor("a"))
        assertEquals(listOf("a" to LoadResult.Requested), replayed)
        assertEquals(1, h.engine.gateSkipCount)
        h.engine.close()
    }

    @Test fun `async gate - a retried skip of an existing key is replayed though nothing else publishes`() = runTest {
        val h = harness(async.copy(retrySkippedPolite = true))
        h.engine.load(request("a"), LoadMode.FORCED)
        repeat(2) { h.loader.fail("a") }
        h.engine.load(request("busy"), LoadMode.FORCED)
        runCurrent()
        h.loader.fill("busy")
        assertFalse(h.engine.gateOpen.value)
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("a"), POLITE))
        runCurrent()
        assertTrue(h.engine.gateOpen.value)
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        h.scheduler.advanceBy(0)
        assertEquals(listOf("t0", "t1", "t0"), h.loader.callsFor("a"))
        assertTrue(h.engine.dumpState().lines().first().endsWith("pending=[]"))
        h.engine.close()
    }
}
