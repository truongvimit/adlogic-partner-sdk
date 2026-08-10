package io.onboardkit.ads.erain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The priority rule these tests pin down: a tier wins only once every higher floor has failed,
 * so the delivered fill is the highest floor available — never whichever callback landed first.
 */
class TierRaceTest {

    @Test
    fun `top tier fill wins immediately`() {
        val race = TierRace<String>(3)
        assertEquals(RaceOutcome.Won("high"), race.onFill(0, "high"))
    }

    @Test
    fun `lower tier fill waits for the higher floors to settle`() {
        val race = TierRace<String>(3)
        assertEquals(RaceOutcome.Pending, race.onFill(2, "all"))
        assertEquals(RaceOutcome.Pending, race.onFail(0))
        assertEquals(RaceOutcome.Won("all"), race.onFail(1))
    }

    @Test
    fun `top tier beats an already buffered lower tier`() {
        val race = TierRace<String>(2)
        race.onFill(1, "all")
        assertEquals(RaceOutcome.Won("high"), race.onFill(0, "high"))
        // The loser must come back exactly once so the caller can release it
        assertEquals(listOf("all"), race.drainLosers())
        assertTrue(race.drainLosers().isEmpty())
    }

    @Test
    fun `middle tier wins when only the top floor failed`() {
        val race = TierRace<String>(3)
        assertEquals(RaceOutcome.Pending, race.onFail(0))
        assertEquals(RaceOutcome.Won("mid"), race.onFill(1, "mid"))
    }

    @Test
    fun `race is lost only after every tier failed`() {
        val race = TierRace<String>(3)
        assertEquals(RaceOutcome.Pending, race.onFail(1))
        assertEquals(RaceOutcome.Pending, race.onFail(0))
        assertEquals(RaceOutcome.Lost, race.onFail(2))
    }

    @Test
    fun `callbacks arriving after the decision are reported as late`() {
        val race = TierRace<String>(2)
        assertEquals(RaceOutcome.Won("high"), race.onFill(0, "high"))
        assertEquals(RaceOutcome.Late, race.onFill(1, "all"))
        assertEquals(RaceOutcome.Late, race.onFail(1))
    }

    @Test
    fun `a lost race reports late instead of a second terminal`() {
        val race = TierRace<String>(1)
        assertEquals(RaceOutcome.Lost, race.onFail(0))
        assertEquals(RaceOutcome.Late, race.onFill(0, "all"))
    }

    @Test
    fun `release mid race hands back every buffered fill`() {
        val race = TierRace<String>(3)
        race.onFill(1, "mid")
        race.onFill(2, "all")
        assertEquals(setOf("mid", "all"), race.drainAll().toSet())
        // Draining settles the race, so a straggler cannot leak a new winner
        assertEquals(RaceOutcome.Late, race.onFill(0, "high"))
    }

    @Test
    fun `single tier race behaves like a plain load`() {
        val race = TierRace<String>(1)
        assertEquals(RaceOutcome.Won("only"), race.onFill(0, "only"))
    }
}
