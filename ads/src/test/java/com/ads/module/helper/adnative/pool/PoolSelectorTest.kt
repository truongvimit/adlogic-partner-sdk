package com.ads.module.helper.adnative.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolSelectorTest {
    private val now = 100_000L
    private var nextIndex = 0

    private fun ad(key: String) = FakeAd(key, "t0", 0)

    private fun loader(
        key: String,
        state: PoolState,
        inFlight: Boolean = false,
        refreshing: Boolean = false,
        fillAtMs: Long = now - 1_000,
        index: Int = nextIndex++,
    ): PoolLoaderView<FakeAd> {
        val held = state is PoolState.Loaded || state is PoolState.Showing
        return PoolLoaderView(key, state, if (held) ad(key) else null, inFlight, refreshing,
            if (held) fillAtMs else 0L, index)
    }

    private fun select(
        vararg loaders: PoolLoaderView<FakeAd>,
        owner: String? = null,
        parity: PoolParity = PoolParity(),
        maxAgeMs: Long = 0L,
    ): PoolSelection<FakeAd> = selectFromPool(PoolSnapshot(loaders.toList(), owner, 1L), parity, now, maxAgeMs)

    private fun assertLender(expected: String, selection: PoolSelection<FakeAd>, snapshot: List<PoolLoaderView<FakeAd>>) {
        assertEquals(expected, selection.lenderKey)
        assertSame(snapshot.first { it.key == expected }.ad, selection.ad)
        assertTrue(selection.show)
    }

    @Test fun `rule 1 - the owner's own Showing ad beats a newer borrowed Loaded ad`() {
        val loaders = listOf(loader("own", PoolState.Showing(10)), loader("other", PoolState.Loaded(20)))
        assertLender("own", select(*loaders.toTypedArray(), owner = "own"), loaders)
    }

    @Test fun `rule 1 - the owner's Loaded ad beats a newer Loaded ad`() {
        val loaders = listOf(loader("own", PoolState.Loaded(10)), loader("other", PoolState.Loaded(20)))
        assertLender("own", select(*loaders.toTypedArray(), owner = "own"), loaders)
    }

    @Test fun `rule 2 - the newest Loaded ad wins over any Showing ad`() {
        val loaders = listOf(
            loader("a", PoolState.Loaded(10)),
            loader("b", PoolState.Loaded(20)),
            loader("c", PoolState.Showing(30)),
        )
        assertLender("b", select(*loaders.toTypedArray()), loaders)
    }

    @Test fun `rule 2 - an owner without an ad falls through to the newest Loaded`() {
        val loaders = listOf(loader("own", PoolState.None, inFlight = true), loader("lfo", PoolState.Loaded(5)))
        assertLender("lfo", select(*loaders.toTypedArray(), owner = "own"), loaders)
    }

    @Test fun `rule 3 - with nothing Loaded the newest Showing ad is borrowed`() {
        val loaders = listOf(
            loader("a", PoolState.Showing(10)),
            loader("b", PoolState.Showing(20)),
            loader("own", PoolState.None, inFlight = true),
        )
        assertLender("b", select(*loaders.toTypedArray(), owner = "own"), loaders)
    }

    @Test fun `rule 3 off - borrowShowing false hides instead of re-binding a shown ad`() {
        val parity = PoolParity(borrowShowing = false)
        assertEquals(PoolSelection.hidden<FakeAd>(), select(loader("a", PoolState.Showing(10)), parity = parity))
        assertEquals(
            PoolSelection.shimmer<FakeAd>(),
            select(loader("a", PoolState.Showing(10)), loader("b", PoolState.None, inFlight = true), parity = parity),
        )
    }

    @Test fun `rule 3 off - the owner's own Showing ad is still selected`() {
        val loaders = listOf(loader("own", PoolState.Showing(10)))
        assertLender("own", select(*loaders.toTypedArray(), owner = "own", parity = PoolParity(borrowShowing = false)), loaders)
    }

    @Test fun `rule 4 - nothing held gives a shimmer while anything is in flight, else hidden`() {
        assertEquals(PoolSelection.hidden<FakeAd>(), select())
        assertEquals(PoolSelection.hidden<FakeAd>(), select(loader("a", PoolState.LoadError), loader("b", PoolState.None)))
        assertEquals(
            PoolSelection.shimmer<FakeAd>(),
            select(loader("a", PoolState.LoadError), loader("b", PoolState.None, inFlight = true), owner = "a"),
        )
    }

    @Test fun `a refreshing owner is still selected by rule 1 in both refresh models`() {
        for (marks in listOf(true, false)) {
            val loaders = listOf(
                loader("own", PoolState.Showing(10), refreshing = true),
                loader("other", PoolState.Loaded(20)),
            )
            val selection = select(*loaders.toTypedArray(), owner = "own", parity = PoolParity(refreshMarksInFlight = marks))
            assertLender("own", selection, loaders)
        }
    }

    @Test fun `a refreshing loader lends its held ad to other slots`() {
        val loaders = listOf(loader("a", PoolState.Loaded(10), refreshing = true))
        assertLender("a", select(*loaders.toTypedArray(), owner = "elsewhere"), loaders)
    }

    @Test fun `a refresh counts for rule 4 only when refreshMarksInFlight`() {
        val refreshing = loader("a", PoolState.Showing(10), refreshing = true)
        val marking = select(refreshing, parity = PoolParity(borrowShowing = false, refreshMarksInFlight = true))
        val invisible = select(refreshing, parity = PoolParity(borrowShowing = false, refreshMarksInFlight = false))
        assertEquals(PoolSelection.shimmer<FakeAd>(), marking)
        assertEquals(PoolSelection.hidden<FakeAd>(), invisible)
    }

    @Test fun `anyInFlight counts a refresh only when refreshMarksInFlight`() {
        val snapshot = PoolSnapshot(listOf(loader("a", PoolState.Loaded(1), refreshing = true)), null, 1L)
        assertTrue(anyInFlight(snapshot, PoolParity(refreshMarksInFlight = true)))
        assertFalse(anyInFlight(snapshot, PoolParity(refreshMarksInFlight = false)))
        val normal = PoolSnapshot(listOf(loader("b", PoolState.None, inFlight = true)), null, 1L)
        assertTrue(anyInFlight(normal, PoolParity(refreshMarksInFlight = false)))
    }

    @Test fun `an expired ad is invisible to every rule, the owner's included`() {
        val maxAge = 60_000L
        val loaders = listOf(
            loader("own", PoolState.Showing(30), fillAtMs = now - maxAge),
            loader("old", PoolState.Loaded(20), fillAtMs = now - maxAge - 1),
            loader("fresh", PoolState.Showing(10), fillAtMs = now - maxAge + 1),
        )
        assertLender("fresh", select(*loaders.toTypedArray(), owner = "own", maxAgeMs = maxAge), loaders)
    }

    @Test fun `expired ads leave a shimmer only while something is in flight`() {
        val stale = loader("a", PoolState.Loaded(10), fillAtMs = now - 10_000)
        assertEquals(PoolSelection.hidden<FakeAd>(), select(stale, maxAgeMs = 10_000))
        val refreshingStale = loader("b", PoolState.Loaded(11), fillAtMs = now - 10_000, refreshing = true)
        assertEquals(PoolSelection.shimmer<FakeAd>(), select(stale, refreshingStale, maxAgeMs = 10_000))
    }

    @Test fun `max age 0 never expires`() {
        val loaders = listOf(loader("a", PoolState.Loaded(10), fillAtMs = 0L))
        assertLender("a", select(*loaders.toTypedArray(), maxAgeMs = 0L), loaders)
    }

    @Test fun `equal timestamps fall back to the lower insertion index, whatever the list order`() {
        val first = loader("first", PoolState.Loaded(50), index = 0)
        val second = loader("second", PoolState.Loaded(50), index = 1)
        assertLender("first", select(second, first), listOf(first, second))
        val shownFirst = loader("first", PoolState.Showing(50), index = 0)
        val shownSecond = loader("second", PoolState.Showing(50), index = 1)
        assertLender("first", select(shownSecond, shownFirst), listOf(shownFirst, shownSecond))
    }
}
