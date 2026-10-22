package com.ads.module.helper.adnative.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselSelectorTest {
    private val now = 100_000L
    private var nextIndex = 0

    private fun loader(key: String, state: PoolState, inFlight: Boolean = false): PoolLoaderView<FakeAd> {
        val held = state is PoolState.Loaded || state is PoolState.Showing
        return PoolLoaderView(key, state, if (held) FakeAd(key, "t0", 0) else null, inFlight, false,
            if (held) now - 1_000 else 0L, nextIndex++)
    }

    private fun pages(
        vararg loaders: PoolLoaderView<FakeAd>,
        firstShown: FakeAd? = null,
        secondShown: FakeAd? = null,
        parity: PoolParity = PoolParity(),
    ): CarouselSelection<FakeAd> = selectCarouselPages(
        PoolSnapshot(loaders.toList(), "lfo", 1L), "lfo", "lfo_carousel", firstShown, secondShown, parity, now, 0L,
    )

    @Test fun `each page shows its own key's ad`() {
        val lfo = loader("lfo", PoolState.Showing(10))
        val own = loader("lfo_carousel", PoolState.Showing(20))
        val result = pages(lfo, own, loader("ob1", PoolState.Loaded(30)))
        assertSame(lfo.ad, result.first.ad)
        assertSame(own.ad, result.second.ad)
    }

    @Test fun `page 1 without its own ad borrows the newest unshown one, never page 0's`() {
        val lfo = loader("lfo", PoolState.Loaded(30))
        val older = loader("ob1", PoolState.Loaded(10))
        val newer = loader("ob2", PoolState.Loaded(20))
        val result = pages(lfo, older, newer)
        assertSame(lfo.ad, result.first.ad)
        assertSame(newer.ad, result.second.ad)
    }

    @Test fun `page 1 never borrows a shown ad`() {
        val lfo = loader("lfo", PoolState.Showing(30))
        val result = pages(lfo, loader("ob1", PoolState.Showing(10)))
        assertNull(result.second.ad)
        assertFalse(result.second.show)
    }

    @Test fun `page 1 keeps the ad it shows over a newer unshown one`() {
        val lfo = loader("lfo", PoolState.Showing(30))
        val kept = loader("ob1", PoolState.Showing(10))
        val result = pages(lfo, kept, loader("ob2", PoolState.Loaded(40)), secondShown = kept.ad)
        assertSame(kept.ad, result.second.ad)
    }

    @Test fun `page 1's own fill replaces the ad it borrowed`() {
        val lfo = loader("lfo", PoolState.Showing(30))
        val borrowed = loader("ob1", PoolState.Showing(10))
        val own = loader("lfo_carousel", PoolState.Loaded(40))
        assertSame(own.ad, pages(lfo, borrowed, own, secondShown = borrowed.ad).second.ad)
    }

    @Test fun `page 0 without its own ad keeps what it shows rather than take page 1's`() {
        val old = loader("lfo1", PoolState.Showing(10))
        val second = loader("lfo_carousel", PoolState.Showing(20))
        val result = pages(old, second, loader("lfo", PoolState.None, inFlight = true),
            firstShown = old.ad, secondShown = second.ad)
        assertSame(old.ad, result.first.ad)
        assertSame(second.ad, result.second.ad)
    }

    @Test fun `the two pages never show one ad`() {
        val only = loader("ob1", PoolState.Loaded(10))
        val result = pages(only)
        assertSame(only.ad, result.first.ad)
        assertNotSame(result.first.ad, result.second.ad)
        assertNull(result.second.ad)
    }

    @Test fun `an empty page shimmers only while something loads`() {
        assertTrue(pages(loader("lfo_carousel", PoolState.None, inFlight = true)).second.show)
        assertFalse(pages(loader("lfo_carousel", PoolState.LoadError)).second.show)
        assertEquals(null, pages().second.lenderKey)
    }
}
