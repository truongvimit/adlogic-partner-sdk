package com.ads.module.helper.adnative.pool

import com.ads.module.helper.adnative.pool.LoadMode.FORCED
import com.ads.module.helper.adnative.pool.LoadMode.POLITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePoolEngineTest {
    private fun harness(parity: PoolParity = SYNC, maxAgeMs: Long = 0L, stuckMs: Long = 60_000L) =
        PoolHarness(parity, maxAgeMs, stuckLoadingLogMs = stuckMs)

    private fun PoolHarness.saved(key: String): Int =
        engine.dumpState().lines().first { it.startsWith("$key #") }.substringAfter("saved=").toInt()

    private fun PoolHarness.loading(key: String, deadlineMs: Long = 0L) =
        engine.load(request(key, deadlineMs = deadlineMs), FORCED)

    private fun ts(state: PoolState?): Long = when (state) {
        is PoolState.Loaded -> state.ts
        is PoolState.Showing -> state.ts
        else -> error("no ts in $state")
    }

    // E1 registry

    @Test fun `E1 a config refusal creates no loader and requests nothing, in either mode`() {
        val h = harness()
        h.policy.configByKey["a"] = "premium"
        for (mode in LoadMode.values()) {
            val result = h.engine.load(request("a"), mode)
            assertEquals(LoadResult.SkippedByConfig("premium"), result)
            assertFalse(result.reportsSuccess)
        }
        assertTrue(h.engine.debugSnapshot().loaders.isEmpty())
        assertTrue(h.loader.calls.isEmpty())
        assertEquals(List(2) { PoolEvent.Skipped<FakeAd>("a", LoadResult.SkippedByConfig("premium")) }, h.events)
        assertTrue("config_skip key=a reason=premium" in h.traces)
    }

    @Test fun `E1 a gate skip creates the loader without saving the request`() {
        val h = harness()
        h.loading("busy")
        val result = h.engine.load(request("a"), POLITE)
        assertEquals(LoadResult.SkippedByGate, result)
        assertTrue(result.reportsSuccess)
        assertEquals(PoolState.None, h.state("a"))
        assertFalse(h.view("a")!!.inFlight)
        assertEquals(0, h.saved("a"))
        assertEquals(listOf("busy" to "t0"), h.loader.calls)
        assertEquals(1, h.engine.gateSkipCount)
        assertTrue("gate_skip key=a" in h.traces)
        assertEquals(PoolEvent.Skipped<FakeAd>("a", LoadResult.SkippedByGate), h.events.last())
    }

    @Test fun `E1 a transient refusal creates the loader and saves the request`() {
        val h = harness()
        h.policy.transientAll = "offline"
        val result = h.engine.load(request("a"), POLITE)
        assertEquals(LoadResult.SkippedTransient("offline"), result)
        assertTrue(result.reportsSuccess)
        assertEquals(PoolState.None, h.state("a"))
        assertEquals(2, h.saved("a"))
        assertTrue(h.loader.calls.isEmpty())
    }

    @Test fun `E1 loaders keep their first-use order and survive exhaustion`() {
        val h = harness()
        h.loading("b")
        h.loading("a")
        repeat(2) { h.loader.fail("b") }
        assertEquals(PoolState.LoadError, h.state("b"))
        assertEquals(listOf("b" to 0, "a" to 1), h.engine.debugSnapshot().loaders.map { it.key to it.index })
    }

    // E2 one ad and state per loader

    @Test fun `E2 timestamps strictly increase across loaders on a frozen clock, and follow the clock when it is ahead`() {
        val h = harness()
        val a = h.loaded("a")
        h.loaded("b")
        h.loader.impress(a)
        assertEquals(PoolState.Loaded(1_001), h.state("b"))
        assertEquals(PoolState.Showing(1_002), h.state("a"))
        h.scheduler.advanceBy(5_000)
        h.loaded("c")
        assertEquals(PoolState.Loaded(6_000), h.state("c"))
    }

    @Test fun `E2 a replacement keeps the old ad alive unless destroyReplacedAds`() {
        for (destroy in listOf(false, true)) {
            val h = harness(SYNC.copy(destroyReplacedAds = destroy))
            val old = h.showing("a")
            assertTrue(h.engine.refresh("a"))
            val fresh = h.loader.fill("a")
            assertSame(fresh, h.view("a")!!.ad)
            assertEquals("destroyReplacedAds=$destroy", if (destroy) 1 else 0, old.destroyed)
            assertEquals(0, fresh.destroyed)
        }
    }

    @Test fun `E2 fillAtMs is the engine clock at the fill`() {
        val h = harness()
        h.loading("a")
        h.scheduler.advanceBy(1_234)
        h.loader.fill("a")
        assertEquals(2_234L, h.view("a")!!.fillAtMs)
    }

    // E3 / E8 load results

    @Test fun `E3 result matrix - only a config refusal reports failure`() {
        val h = harness()
        h.policy.configByKey["cfg"] = "placement_off_by_remote"
        h.policy.transientByKey["c"] = "consent_not_granted"
        val config = h.engine.load(request("cfg"), POLITE)
        val requested = h.engine.load(request("a"), POLITE)
        val noOpInFlight = h.engine.load(request("a"), FORCED)
        val gated = h.engine.load(request("b"), POLITE)
        val transient = h.engine.load(request("c"), FORCED)
        h.loader.fill("a")
        val noOpHeld = h.engine.load(request("a"), FORCED)

        assertEquals(LoadResult.SkippedByConfig("placement_off_by_remote"), config)
        assertEquals(LoadResult.Requested, requested)
        assertEquals(LoadResult.NoOpState(PoolState.None, true), noOpInFlight)
        assertEquals(LoadResult.SkippedByGate, gated)
        assertEquals(LoadResult.SkippedTransient("consent_not_granted"), transient)
        assertEquals(LoadResult.NoOpState(PoolState.Loaded(1_000), false), noOpHeld)
        assertEquals(
            listOf(false, true, true, true, true, true),
            listOf(config, requested, noOpInFlight, gated, transient, noOpHeld).map { it.reportsSuccess },
        )
    }

    @Test fun `E3 Requested, NoOpState and SkippedTransient save the request, a gate skip and a config refusal do not`() {
        val h = harness()
        h.loaded("a", listOf("first"))

        assertTrue(h.engine.load(request("a", listOf("noop")), FORCED) is LoadResult.NoOpState)
        assertTrue(h.engine.refresh("a"))
        assertEquals("noop", h.loader.last("a").id)
        h.loader.fill("a")

        h.loading("busy")
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("a", listOf("gated")), POLITE))
        repeat(2) { h.loader.fail("busy") }
        assertTrue(h.engine.refresh("a"))
        assertEquals("noop", h.loader.last("a").id)
        h.loader.fill("a")

        h.policy.transientByKey["a"] = "offline"
        assertTrue(h.engine.load(request("a", listOf("transient")), FORCED) is LoadResult.SkippedTransient)
        h.policy.transientByKey.clear()
        assertTrue(h.engine.refresh("a"))
        assertEquals("transient", h.loader.last("a").id)
        h.loader.fill("a")

        h.policy.configByKey["a"] = "premium"
        assertTrue(h.engine.load(request("a", listOf("config")), FORCED) is LoadResult.SkippedByConfig)
        h.policy.configByKey.clear()
        assertTrue(h.engine.refresh("a"))
        assertEquals("transient", h.loader.last("a").id)
    }

    @Test fun `E8 setOwner and clearOwnerIf publish, and only a matching key clears`() {
        val h = harness()
        val before = h.engine.debugSnapshot().version
        h.engine.setOwner("a")
        assertEquals("a", h.engine.owner)
        assertEquals("a", h.engine.debugSnapshot().owner)
        assertTrue(h.engine.debugSnapshot().version > before)
        h.engine.clearOwnerIf("b")
        assertEquals("a", h.engine.owner)
        h.engine.clearOwnerIf("a")
        assertNull(h.engine.owner)
        h.engine.setOwner("b")
        h.engine.setOwner(null)
        assertNull(h.engine.debugSnapshot().owner)
    }

    @Test fun `E8 the owner's ad wins while it holds one, else the pool lends`() {
        val h = harness()
        val a = h.showing("a")
        val b = h.loaded("b")
        h.engine.setOwner("a")
        assertEquals(PoolSelection("a", a, true), h.selection())
        h.engine.setOwner(null)
        assertEquals(PoolSelection("b", b, true), h.selection())
    }

    // E4 waterfall

    @Test fun `E4 walks ids in order, skips blanks, keeps duplicates and drops one id per failure`() {
        val h = harness()
        h.engine.load(request("a", listOf("t0", " ", "", "t1", "t1", "t2")), FORCED)
        repeat(4) { h.loader.fail("a") }
        assertEquals(listOf("t0", "t1", "t1", "t2"), h.loader.callsFor("a"))
        assertEquals(PoolState.LoadError, h.state("a"))
        assertFalse(h.view("a")!!.inFlight)
        assertEquals(PoolEvent.Exhausted<FakeAd>("a", refresh = false), h.events.last())
        assertTrue(h.engine.gateOpen.value)
    }

    @Test fun `E4 a fill on a later id ends the chain as Loaded`() {
        val h = harness()
        h.loading("a")
        h.loader.fail("a")
        val ad = h.loader.fill("a")
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        assertEquals(PoolState.Loaded(1_000), h.state("a"))
        assertEquals(PoolEvent.Filled("a", ad, refresh = false), h.events.last())
    }

    @Test fun `E4 an all-blank id list exhausts at once`() {
        val h = harness()
        assertEquals(LoadResult.Requested, h.engine.load(request("a", listOf(" ", "")), FORCED))
        assertEquals(PoolState.LoadError, h.state("a"))
        assertTrue(h.loader.calls.isEmpty())
    }

    @Test fun `E4 the per-id deadline moves to the next id and the timed-out id's late fill is destroyed`() {
        val h = harness()
        h.loading("a", deadlineMs = 30_000)
        val first = h.loader.last("a")
        h.scheduler.advanceBy(29_999)
        assertEquals(listOf("t0"), h.loader.callsFor("a"))
        h.scheduler.advanceBy(1)
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        assertTrue("tier_timeout key=a tier=0" in h.traces)

        val late = first.fill()
        assertEquals(1, late.destroyed)
        assertTrue("stale_fill key=a" in h.traces)
        assertNull(h.view("a")!!.ad)
        assertTrue(h.view("a")!!.inFlight)
        first.fail()
        assertEquals(2, h.loader.calls.size)

        val ad = h.loader.fill("a")
        assertSame(ad, h.view("a")!!.ad)
        h.scheduler.advanceBy(120_000)
        assertEquals(1, h.traces.count { it.startsWith("tier_timeout") })
        assertEquals(0, ad.destroyed)
    }

    @Test fun `E4 the last id timing out exhausts the chain`() {
        val h = harness()
        h.loading("a", deadlineMs = 1_000)
        h.scheduler.advanceBy(2_000)
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        assertEquals(PoolState.LoadError, h.state("a"))
    }

    @Test fun `E4 deadline 0 waits forever`() {
        val h = harness()
        h.loading("a")
        h.scheduler.advanceBy(600_000)
        assertEquals(listOf("t0"), h.loader.callsFor("a"))
        assertTrue(h.view("a")!!.inFlight)
    }

    @Test fun `E4 an impression from a replaced ad marks the loader`() {
        val h = harness()
        val old = h.showing("a")
        h.engine.refresh("a")
        val fresh = h.loader.fill("a")
        val freshState = h.state("a")
        h.loader.impress(old)
        assertTrue(h.state("a") is PoolState.Showing)
        assertTrue(ts(h.state("a")) > ts(freshState))
        assertSame(fresh, h.view("a")!!.ad)
        assertEquals(PoolEvent.Impression("a", old), h.events.last())
    }

    @Test fun `E4 clicks are never filtered, a replaced ad's included`() {
        val h = harness()
        val old = h.showing("a")
        h.engine.refresh("a")
        h.loader.fill("a")
        h.loader.click(old)
        assertEquals(PoolEvent.Clicked("a", old), h.events.last())
    }

    @Test fun `E4 a policy refusal before the next id ends the chain`() {
        for (config in listOf(true, false)) {
            val h = harness()
            h.loading("a")
            if (config) h.policy.configByKey["a"] = "premium" else h.policy.transientAll = "offline"
            h.loader.fail("a")
            assertEquals(listOf("t0"), h.loader.callsFor("a"))
            assertEquals(PoolState.LoadError, h.state("a"))
            assertEquals(PoolEvent.Exhausted<FakeAd>("a", refresh = false), h.events.last())
            assertTrue(h.traces.any { it.startsWith("exhausted key=a") && "refused" in it })
        }
    }

    @Test fun `E4 a throwing loader counts as a failure of that id`() {
        val h = harness()
        h.loader.throwOnLoad = IllegalStateException("boom")
        assertEquals(LoadResult.Requested, h.loading("a"))
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        assertEquals(PoolState.LoadError, h.state("a"))
        assertTrue(h.traces.any { it.startsWith("load_threw key=a tier=0") })
    }

    // E5 / D-REFRESH

    @Test fun `E5 refresh needs a loader, a saved request and a held ad`() {
        val h = harness()
        assertFalse(h.engine.refresh("missing"))
        h.loading("busy")
        h.engine.load(request("gated"), POLITE)
        assertFalse(h.engine.refresh("gated"))
        assertFalse(h.engine.refresh("busy"))
        repeat(2) { h.loader.fail("busy") }
        assertFalse(h.engine.refresh("busy"))
        h.policy.transientByKey["saved"] = "offline"
        h.engine.load(request("saved"), FORCED)
        h.policy.transientByKey.clear()
        assertFalse(h.engine.refresh("saved"))
        assertEquals(listOf("busy" to "t0", "busy" to "t1"), h.loader.calls)
    }

    @Test fun `E5 refresh is refused while it runs or while a policy refuses`() {
        val h = harness()
        h.showing("a")
        h.policy.transientAll = "offline"
        assertFalse(h.engine.refresh("a"))
        assertTrue("refresh_skip key=a reason=offline" in h.traces)
        h.policy.transientAll = null
        h.policy.configByKey["a"] = "premium"
        assertFalse(h.engine.refresh("a"))
        h.policy.configByKey.clear()
        assertTrue(h.engine.refresh("a"))
        assertFalse(h.engine.refresh("a"))
        assertEquals(listOf("t0", "t0"), h.loader.callsFor("a"))
    }

    @Test fun `E5 refreshOverwritesUnshown decides whether a Loaded ad is refreshed, a Showing one always is`() {
        for (overwrites in listOf(true, false)) {
            val loaded = harness(SYNC.copy(refreshOverwritesUnshown = overwrites))
            loaded.loaded("a")
            assertEquals("overwrites=$overwrites", overwrites, loaded.engine.refresh("a"))
            val showing = harness(SYNC.copy(refreshOverwritesUnshown = overwrites))
            showing.showing("a")
            assertTrue(showing.engine.refresh("a"))
        }
    }

    @Test fun `D-REFRESH the held ad and its state stay unchanged while refreshing`() {
        val h = harness()
        h.engine.setOwner("a")
        val ad = h.showing("a")
        val shown = h.state("a")!!
        assertTrue(h.engine.refresh("a"))
        val view = h.view("a")!!
        assertEquals(shown, view.state)
        assertSame(ad, view.ad)
        assertTrue(view.refreshing)
        assertFalse(view.inFlight)
        assertEquals(PoolSelection("a", ad, true), h.selection())
        assertEquals(listOf("t0", "t0"), h.loader.callsFor("a"))
        assertEquals(LoadResult.NoOpState(shown, true), h.engine.load(request("a"), FORCED))
    }

    @Test fun `D-REFRESH a fill replaces the ad as Loaded with a newer ts`() {
        val h = harness()
        h.showing("a")
        val shownTs = ts(h.state("a"))
        h.engine.refresh("a")
        h.loader.fail("a")
        val fresh = h.loader.fill("a")
        assertTrue(h.state("a") is PoolState.Loaded)
        assertTrue(ts(h.state("a")) > shownTs)
        assertSame(fresh, h.view("a")!!.ad)
        assertFalse(h.view("a")!!.refreshing)
        assertEquals(PoolEvent.Filled("a", fresh, refresh = true), h.events.last())
    }

    @Test fun `D-REFRESH exhaustion keeps the old ad and its state`() {
        val h = harness()
        val ad = h.showing("a")
        val shown = h.state("a")
        h.engine.refresh("a")
        repeat(2) { h.loader.fail("a") }
        assertSame(ad, h.view("a")!!.ad)
        assertEquals(shown, h.state("a"))
        assertFalse(h.view("a")!!.refreshing)
        assertEquals(PoolEvent.Exhausted<FakeAd>("a", refresh = true), h.events.last())
    }

    @Test fun `D-REFRESH the gate is closed during a refresh`() {
        val h = harness()
        h.showing("a")
        assertTrue(h.engine.gateOpen.value)
        h.engine.refresh("a")
        assertFalse(h.engine.gateOpen.value)
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("resume"), POLITE))
        assertTrue(h.loader.callsFor("resume").isEmpty())
        h.loader.last("a").fail()
        h.loader.last("a").fail()
        assertFalse(h.view("a")!!.refreshing)
        assertTrue(h.engine.gateOpen.value)
    }

    @Test fun `D-REFRESH the last id timing out ends the refresh with the shown ad and the gate as before`() {
        val h = harness()
        val ad = h.showing("a")
        val shown = h.state("a")
        h.engine.load(request("a", deadlineMs = 1_000), FORCED)
        assertTrue(h.engine.refresh("a"))
        assertFalse(h.engine.gateOpen.value)
        h.scheduler.advanceBy(2_000)
        assertEquals(listOf("t0", "t0", "t1"), h.loader.callsFor("a"))
        assertEquals(2, h.traces.count { it.startsWith("tier_timeout key=a") })
        val view = h.view("a")!!
        assertFalse(view.refreshing)
        assertSame(ad, view.ad)
        assertEquals(shown, view.state)
        assertTrue(h.engine.gateOpen.value)
        assertEquals(PoolEvent.Exhausted<FakeAd>("a", refresh = true), h.events.last())
    }

    @Test fun `D-REFRESH a policy refusal before the next id ends the refresh and keeps the shown ad`() {
        for (config in listOf(true, false)) {
            val h = harness()
            val ad = h.showing("a")
            val shown = h.state("a")
            assertTrue(h.engine.refresh("a"))
            if (config) h.policy.configByKey["a"] = "premium" else h.policy.transientAll = "offline"
            h.loader.fail("a")
            assertEquals(listOf("t0", "t0"), h.loader.callsFor("a"))
            val view = h.view("a")!!
            assertFalse(view.refreshing)
            assertSame(ad, view.ad)
            assertEquals(shown, view.state)
            assertTrue(h.engine.gateOpen.value)
            assertEquals(PoolEvent.Exhausted<FakeAd>("a", refresh = true), h.events.last())
            assertTrue(h.traces.any { it.startsWith("exhausted key=a refresh=true") && "refused" in it })
        }
    }

    @Test fun `D-REFRESH refresh bypasses the gate`() {
        val h = harness()
        h.showing("a")
        h.loading("busy")
        assertFalse(h.engine.gateOpen.value)
        assertTrue(h.engine.refresh("a"))
    }

    // E7 polite vs forced

    @Test fun `E7 FORCED bypasses only the gate`() {
        val h = harness()
        h.loading("busy")
        h.policy.configByKey["cfg"] = "premium"
        h.policy.transientByKey["tr"] = "offline"
        assertEquals(LoadResult.Requested, h.engine.load(request("a"), FORCED))
        assertEquals(LoadResult.SkippedByConfig("premium"), h.engine.load(request("cfg"), FORCED))
        assertEquals(LoadResult.SkippedTransient("offline"), h.engine.load(request("tr"), FORCED))
        assertEquals(LoadResult.NoOpState(PoolState.None, true), h.engine.load(request("a"), FORCED))
        assertEquals(0, h.engine.gateSkipCount)
    }

    @Test fun `E7 a skipped POLITE load is not retried by default`() {
        val h = harness()
        h.loading("busy")
        h.engine.load(request("a"), POLITE)
        h.loader.fill("busy")
        assertTrue(h.engine.gateOpen.value)
        assertTrue(h.loader.callsFor("a").isEmpty())
        assertEquals(PoolState.None, h.state("a"))
    }

    @Test fun `E7 retrySkippedPolite replays skipped loads in first-skip order as the gate opens`() {
        val h = harness(SYNC.copy(retrySkippedPolite = true))
        val replayed = mutableListOf<Pair<String, LoadResult>>()
        h.engine.addReplayListener { request, result -> replayed += request.key to result }
        h.loading("busy")
        h.engine.load(request("a"), POLITE)
        h.engine.load(request("c"), POLITE)
        h.engine.load(request("a"), POLITE)
        assertEquals(3, h.engine.gateSkipCount)
        h.loader.fill("busy")
        assertEquals(listOf("busy" to "t0", "a" to "t0"), h.loader.calls)
        h.loader.fill("a")
        assertEquals(listOf("busy" to "t0", "a" to "t0", "c" to "t0"), h.loader.calls)
        assertTrue("replay_polite key=a" in h.traces)
        assertTrue("replay_polite key=c" in h.traces)
        assertEquals(3, h.engine.gateSkipCount)
        assertEquals(listOf("a" to LoadResult.Requested, "c" to LoadResult.Requested), replayed)
    }

    @Test fun `E7 retrySkippedPolite does not queue a key whose own chain closed the gate`() {
        val h = harness(SYNC.copy(retrySkippedPolite = true))
        h.loading("a")
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("a"), POLITE))
        repeat(2) { h.loader.fail("a") }
        assertEquals(listOf("t0", "t1"), h.loader.callsFor("a"))
        assertEquals(PoolState.LoadError, h.state("a"))
        assertFalse(h.view("a")!!.inFlight)
        assertFalse(h.traces.any { it.startsWith("replay_polite") })
        assertTrue(h.engine.dumpState().lines().first().endsWith("pending=[]"))
    }

    @Test fun `E7 retrySkippedPolite does not queue a key that holds an ad`() {
        val h = harness(SYNC.copy(retrySkippedPolite = true))
        val held = h.showing("a")
        h.loading("busy")
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("a"), POLITE))
        h.loader.fill("busy")
        assertEquals(listOf("t0"), h.loader.callsFor("a"))
        assertSame(held, h.view("a")!!.ad)
        assertFalse(h.traces.any { it.startsWith("replay_polite") })
    }

    @Test fun `E7 a pending POLITE replay is dropped once the key loads on its own`() {
        val h = harness(SYNC.copy(retrySkippedPolite = true))
        h.loading("busy")
        h.engine.load(request("a"), POLITE)
        h.engine.load(request("a"), FORCED)
        h.loader.fill("busy")
        h.loader.fill("a")
        assertEquals(listOf("busy" to "t0", "a" to "t0"), h.loader.calls)
        assertFalse(h.traces.any { it.startsWith("replay_polite") })
    }

    // Expiry (decision 3)

    @Test fun `expiry - the scheduled sweep returns the loader to None and a later claim reloads`() {
        for (destroy in listOf(false, true)) {
            val h = harness(SYNC.copy(destroyReplacedAds = destroy), maxAgeMs = 10_000)
            val ad = h.showing("a")
            h.engine.setOwner("a")
            h.scheduler.advanceBy(9_999)
            assertSame(ad, h.view("a")!!.ad)
            h.scheduler.advanceBy(1)
            assertEquals(PoolState.None, h.state("a"))
            assertNull(h.view("a")!!.ad)
            assertTrue("expired key=a" in h.traces)
            assertEquals("destroyReplacedAds=$destroy", if (destroy) 1 else 0, ad.destroyed)
            assertEquals(PoolSelection.hidden<FakeAd>(), h.selection())
            assertEquals(LoadResult.Requested, h.engine.load(request("a"), POLITE))
            assertEquals(listOf("t0", "t0"), h.loader.callsFor("a"))
        }
    }

    @Test fun `expiry - refresh, load, setOwner and clearOwnerIf sweep an expired ad before the timer`() {
        val h = harness(maxAgeMs = 10_000)
        listOf("a", "b", "c", "d").forEach { h.loaded(it) }
        h.clock.now += 10_000
        assertEquals(PoolSelection.hidden<FakeAd>(), h.selection())
        assertFalse(h.engine.refresh("a"))
        assertEquals(PoolState.None, h.state("a"))
        h.engine.setOwner("b")
        assertEquals(PoolState.None, h.state("b"))
        h.engine.clearOwnerIf("c")
        assertEquals(PoolState.None, h.state("c"))
        assertEquals(LoadResult.Requested, h.engine.load(request("d"), POLITE))
        assertEquals(listOf("t0", "t0"), h.loader.callsFor("d"))
        assertEquals(listOf("t0"), h.loader.callsFor("a"))
    }

    @Test fun `expiry - max age 0 schedules no sweep and never expires`() {
        val h = harness(maxAgeMs = 0)
        val ad = h.loaded("a")
        assertEquals(0, h.scheduler.pending)
        h.scheduler.advanceBy(36_000_000)
        assertSame(ad, h.view("a")!!.ad)
    }

    @Test fun `expiry - a longer max age read when the timer fires postpones the sweep`() {
        val h = harness(maxAgeMs = 10_000)
        h.loaded("a")
        h.maxAgeMs = 20_000
        h.scheduler.advanceBy(10_000)
        assertTrue(h.state("a") is PoolState.Loaded)
        h.scheduler.advanceBy(10_000)
        assertEquals(PoolState.None, h.state("a"))
    }

    // Adoption (D-PRE)

    @Test fun `adoptReady holds the splash ad as Loaded, unshown, and forwards its events`() {
        val h = harness()
        h.clock.now = 5_000
        val splash = FakeAd("language1", "splash", 99)
        val sink = h.engine.adoptReady(request("language1"), splash)
        val view = h.view("language1")!!
        assertEquals(PoolState.Loaded(5_000), view.state)
        assertSame(splash, view.ad)
        assertEquals(5_000L, view.fillAtMs)
        assertTrue(view.adopted)
        assertFalse(view.inFlight)
        assertTrue(h.engine.isAdoptedUnshown("language1"))
        assertEquals(PoolSelection("language1", splash, true), h.selection())
        assertEquals(LoadResult.NoOpState(PoolState.Loaded(5_000), false), h.engine.load(request("language1"), POLITE))
        assertTrue(h.loader.calls.isEmpty())

        sink.onImpression(splash)
        assertTrue(h.state("language1") is PoolState.Showing)
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        sink.onClicked(splash)
        assertEquals(PoolEvent.Clicked("language1", splash), h.events.last())
        assertTrue(h.engine.refresh("language1"))
        assertEquals(listOf("language1" to "t0"), h.loader.calls)
    }

    @Test fun `adoptPending counts as in flight and a fill completes it like a chain`() {
        val h = harness()
        val adoption = h.engine.adoptPending(request("language1"))
        val view = h.view("language1")!!
        assertTrue(view.inFlight)
        assertFalse(view.adopted)
        assertFalse(h.engine.gateOpen.value)
        assertEquals(PoolSelection.shimmer<FakeAd>(), h.selection())
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        assertEquals(LoadResult.NoOpState(PoolState.None, true), h.engine.load(request("language1"), FORCED))
        assertEquals(LoadResult.SkippedByGate, h.engine.load(request("other"), POLITE))

        val ad = FakeAd("language1", "splash", 1)
        adoption.complete(ad)
        assertEquals(PoolState.Loaded(1_000), h.state("language1"))
        assertFalse(h.view("language1")!!.inFlight)
        assertFalse(h.view("language1")!!.adopted)
        assertTrue(h.engine.gateOpen.value)
        assertEquals(PoolEvent.Filled("language1", ad, refresh = false), h.events.last())

        adoption.sink.onImpression(ad)
        assertTrue(h.state("language1") is PoolState.Showing)
        val second = FakeAd("language1", "splash", 2)
        adoption.complete(second)
        assertEquals(1, second.destroyed)
        assertSame(ad, h.view("language1")!!.ad)
        assertTrue(h.loader.calls.isEmpty())
    }

    @Test fun `adoptPending completed with null leaves LoadError and opens the gate`() {
        val h = harness()
        val adoption = h.engine.adoptPending(request("language1"))
        adoption.sink.onFailed()
        assertEquals(PoolState.LoadError, h.state("language1"))
        assertFalse(h.view("language1")!!.inFlight)
        assertTrue(h.engine.gateOpen.value)
        assertEquals(PoolEvent.Exhausted<FakeAd>("language1", refresh = false), h.events.last())
        assertEquals(LoadResult.Requested, h.engine.load(request("language1"), POLITE))
    }

    @Test fun `adoptReady supersedes a running chain - the gate opens and the old attempt's results are stale`() {
        val h = harness()
        h.loading("language1")
        val old = h.loader.last("language1")
        assertFalse(h.engine.gateOpen.value)
        val splash = FakeAd("language1", "splash", 99)
        h.engine.adoptReady(request("language1"), splash)
        val view = h.view("language1")!!
        assertFalse(view.inFlight)
        assertTrue(h.engine.gateOpen.value)
        assertTrue(h.engine.isAdoptedUnshown("language1"))

        val late = old.fill()
        assertEquals(1, late.destroyed)
        assertTrue("stale_fill key=language1" in h.traces)
        assertSame(splash, h.view("language1")!!.ad)
        assertTrue(h.engine.isAdoptedUnshown("language1"))
        old.fail()
        assertEquals(listOf("t0"), h.loader.callsFor("language1"))
        assertEquals(0, splash.destroyed)
    }

    @Test fun `adoptReady supersedes a pending adoption, whose late fill is destroyed`() {
        val h = harness()
        val pending = h.engine.adoptPending(request("language1"))
        val ready = FakeAd("language1", "splash", 1)
        h.engine.adoptReady(request("language1"), ready)
        val late = FakeAd("language1", "splash", 2)
        pending.complete(late)
        assertEquals(1, late.destroyed)
        assertSame(ready, h.view("language1")!!.ad)
        assertTrue(h.engine.isAdoptedUnshown("language1"))
        assertFalse(h.view("language1")!!.inFlight)
    }

    @Test fun `adoptPending over a held Loaded ad keeps it selectable, unadopted, and a failure keeps it Loaded`() {
        val h = harness()
        val held = h.loaded("language1")
        val loadedState = h.state("language1")
        val adoption = h.engine.adoptPending(request("language1"))
        val view = h.view("language1")!!
        assertTrue(view.inFlight)
        assertFalse(view.adopted)
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        assertFalse(h.engine.gateOpen.value)
        assertEquals(PoolSelection("language1", held, true), h.selection())

        adoption.complete(null)
        assertSame(held, h.view("language1")!!.ad)
        assertEquals(loadedState, h.state("language1"))
        assertFalse(h.view("language1")!!.inFlight)
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        assertTrue(h.engine.gateOpen.value)
        assertEquals(PoolEvent.Exhausted<FakeAd>("language1", refresh = false), h.events.last())
        assertEquals(0, held.destroyed)
    }

    @Test fun `adoptPending over a Showing ad that fails returns it to Loaded at its fill ts`() {
        val h = harness()
        val held = h.loaded("language1")
        val fillTs = ts(h.state("language1"))
        h.loader.impress(held)
        h.showing("other")
        assertTrue(h.state("language1") is PoolState.Showing)

        h.engine.adoptPending(request("language1")).complete(null)
        assertEquals(PoolState.Loaded(fillTs), h.state("language1"))
        assertSame(held, h.view("language1")!!.ad)
        assertEquals(PoolSelection("language1", held, true), h.selection())
    }

    @Test fun `adoptPending over a held ad that fills replaces it as a normal fill`() {
        val h = harness()
        val held = h.showing("language1")
        val adoption = h.engine.adoptPending(request("language1"))
        val splash = FakeAd("language1", "splash", 7)
        adoption.complete(splash)
        assertSame(splash, h.view("language1")!!.ad)
        assertTrue(h.state("language1") is PoolState.Loaded)
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        assertEquals(0, held.destroyed)
    }

    @Test fun `an adopted ad expires on the scheduled sweep and is no longer adopted-unshown`() {
        val h = harness(maxAgeMs = 10_000)
        val splash = FakeAd("language1", "splash", 1)
        h.engine.adoptReady(request("language1"), splash)
        h.scheduler.advanceBy(9_999)
        assertTrue(h.engine.isAdoptedUnshown("language1"))
        h.scheduler.advanceBy(1)
        assertEquals(PoolState.None, h.state("language1"))
        assertNull(h.view("language1")!!.ad)
        assertFalse(h.view("language1")!!.adopted)
        assertFalse(h.engine.isAdoptedUnshown("language1"))
        assertEquals(PoolSelection.hidden<FakeAd>(), h.selection())
    }

    @Test fun `isAdoptedUnshown turns false as soon as the adopted ad is past max age, before the sweep`() {
        val h = harness(maxAgeMs = 10_000)
        h.engine.adoptReady(request("language1"), FakeAd("language1", "splash", 1))
        h.clock.now += 10_000
        assertFalse(h.engine.isAdoptedUnshown("language1"))
    }

    @Test fun `a pending adoption released before it completes destroys the late ad`() {
        val h = harness()
        val adoption = h.engine.adoptPending(request("language1"))
        h.engine.releaseAll()
        val late = FakeAd("language1", "splash", 1)
        adoption.sink.onLoaded(late)
        assertEquals(1, late.destroyed)
        assertTrue(h.engine.debugSnapshot().loaders.isEmpty())
    }

    // releaseAll / onNewRun

    @Test fun `releaseAll destroys held ads, forgets loaders and owner, and turns late results stale`() {
        val h = harness(maxAgeMs = 10_000)
        val a = h.showing("a")
        val b = h.loaded("b")
        h.loading("c", deadlineMs = 30_000)
        val pendingC = h.loader.last("c")
        h.engine.setOwner("a")

        h.engine.releaseAll()
        assertTrue(h.engine.debugSnapshot().loaders.isEmpty())
        assertNull(h.engine.owner)
        assertTrue(h.engine.gateOpen.value)
        assertEquals(PoolSelection.hidden<FakeAd>(), h.selection())
        assertEquals(1, a.destroyed)
        assertEquals(1, b.destroyed)

        val late = pendingC.fill()
        assertEquals(1, late.destroyed)
        assertTrue(h.engine.debugSnapshot().loaders.isEmpty())
        val tracesBefore = h.traces.size
        h.scheduler.advanceBy(600_000)
        assertEquals(tracesBefore, h.traces.size)
        h.loader.impress(a)
        assertTrue(h.engine.debugSnapshot().loaders.isEmpty())

        assertEquals(LoadResult.Requested, h.engine.load(request("a"), POLITE))
        assertEquals(3, h.view("a")!!.index)
    }

    @Test fun `onNewRun clears the owner and keeps the pool`() {
        val h = harness()
        val ad = h.showing("a")
        h.engine.setOwner("a")
        h.engine.onNewRun()
        assertNull(h.engine.owner)
        assertNull(h.engine.debugSnapshot().owner)
        assertTrue(h.state("a") is PoolState.Showing)
        assertEquals(0, ad.destroyed)
        assertEquals(PoolSelection("a", ad, true), h.selection())
    }

    // Parity and diagnostics

    @Test fun `borrowShowing decides whether another screen re-binds a shown ad`() {
        for (borrow in listOf(true, false)) {
            val h = harness(SYNC.copy(borrowShowing = borrow))
            val shown = h.showing("a")
            h.loading("b")
            h.engine.setOwner("b")
            val expected = if (borrow) PoolSelection("a", shown, true) else PoolSelection.shimmer()
            assertEquals("borrowShowing=$borrow", expected, h.selection())
        }
    }

    @Test fun `a parity update keeps the construction-time async switches and republishes`() {
        val h = harness()
        val shown = h.showing("a")
        h.engine.setOwner("b")
        assertEquals(PoolSelection("a", shown, true), h.selection())
        h.engine.parity = PoolParity(borrowShowing = false)
        assertFalse(h.engine.parity.asyncGate)
        assertFalse(h.engine.parity.asyncSelector)
        assertFalse(h.engine.parity.borrowShowing)
        assertEquals(PoolSelection.hidden<FakeAd>(), h.selection())
    }

    @Test fun `a chain or refresh in flight for stuckLoadingLogMs is traced once, a settled one is not`() {
        val h = harness(stuckMs = 60_000)
        h.showing("r")
        h.engine.refresh("r")
        h.loading("a")
        h.loading("b")
        h.loader.fill("b")
        h.scheduler.advanceBy(60_000)
        assertTrue("stuck_loading key=a refresh=false ms=60000" in h.traces)
        assertTrue("stuck_loading key=r refresh=true ms=60000" in h.traces)
        assertFalse(h.traces.any { it.startsWith("stuck_loading key=b") })
        assertTrue(h.view("a")!!.inFlight)
        h.scheduler.advanceBy(600_000)
        assertEquals(2, h.traces.count { it.startsWith("stuck_loading") })
    }

    @Test fun `stuckLoadingLogMs 0 never traces`() {
        val h = harness(stuckMs = 0)
        h.loading("a")
        h.scheduler.advanceBy(600_000)
        assertFalse(h.traces.any { it.startsWith("stuck_loading") })
    }

    @Test fun `dumpState lists the owner, the gate, the skip count and one line per loader`() {
        val h = harness()
        h.showing("a")
        h.loading("busy")
        h.engine.load(request("c"), POLITE)
        h.engine.setOwner("a")
        val dump = h.engine.dumpState().lines()
        assertEquals(4, dump.size)
        assertTrue(dump[0], dump[0].startsWith("owner=a gate=closed gateSkipCount=1"))
        assertTrue(dump[1], dump[1].startsWith("a #0 state=Showing("))
        assertTrue(dump[2], dump[2].startsWith("busy #1 state=None ad=none inFlight=true"))
        assertTrue(dump[3], dump[3].startsWith("c #2 state=None ad=none inFlight=false"))
    }

    @Test fun `a removed listener hears nothing more, and a throwing one does not break the engine`() {
        val h = harness()
        val heard = mutableListOf<PoolEvent<FakeAd>>()
        val handle = h.engine.addEventListener { heard += it }
        h.engine.addEventListener { error("listener bug") }
        h.loaded("a")
        handle.cancel()
        h.loaded("b")
        assertEquals(1, heard.count { it is PoolEvent.Filled })
        assertTrue(h.state("b") is PoolState.Loaded)
        assertTrue(h.traces.any { it.startsWith("listener_threw") })
    }
}
