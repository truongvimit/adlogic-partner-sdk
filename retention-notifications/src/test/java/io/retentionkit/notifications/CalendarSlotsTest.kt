package io.retentionkit.notifications

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CalendarSlotsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun time(value: String, zone: TimeZone = utc) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = zone }.parse(value)!!.time
    private fun next(now: Long, zone: TimeZone = utc, slot: LocalSlot = LocalSlot(8, 0), last: String? = null) =
        CalendarSlots.next(NotificationCampaign.DAILY, slot, now, zone, HOUR, 2, last)

    @Test fun slotBoundaryNeverIntroducesAnArbitraryFiveSecondGap() {
        val due = time("2026-09-07 08:00:00")
        assertEquals(due, next(due - 2_000).due)
        assertEquals(due, next(due + 2_000).due)
        assertEquals(due + DAY, next(due + HOUR).due)
    }
    @Test fun handledDateSuppressesRepeatAfterClockMovesBack() {
        assertEquals("20260908", next(time("2026-09-07 07:00:00"), last = "20260907").localDate)
        assertEquals("20260908", next(time("2026-09-01 07:00:00"), last = "20260907").localDate)
    }
    @Test fun springGapMovesForwardAndFallOverlapProducesOneIdentity() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        val spring = next(time("2026-03-29 00:00:00", paris), paris, LocalSlot(2, 30))
        assertEquals(time("2026-03-29 01:30:00", utc), spring.due) // 03:30 CEST, shifted by missing hour
        val fall = next(time("2026-10-25 00:00:00", paris), paris, LocalSlot(2, 30))
        assertEquals(time("2026-10-25 01:30:00", utc), fall.due) // later 02:30, standard time
        val following = next(fall.due + 1, paris, LocalSlot(2, 30), fall.localDate)
        assertEquals("20261026", following.localDate)
        assertNotEquals(fall.occurrence, following.occurrence)
    }
    @Test fun timezoneChangeKeepsCivilSlotsAndRecomputesUtcDue() {
        val now = time("2026-09-07 00:00:00")
        val east = next(now, TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        assertEquals(time("2026-09-07 01:00:00"), east.due)
        assertEquals(time("2026-09-07 08:00:00"), next(now).due)
        assertEquals(east.occurrence, next(now).occurrence)
    }
    @Test fun malformedDuplicateAndOversizedSlotsRejectWholeProfile() {
        assertNull(LocalSlot.parse("08:00,08:00")); assertNull(LocalSlot.parse("8:00"))
        assertNull(LocalSlot.parse("08:61")); assertNull(LocalSlot.parse("08:00,"))
        assertEquals(emptyList<LocalSlot>(), LocalSlot.parse(""))
        assertEquals(listOf(LocalSlot(8, 0), LocalSlot(19, 0)), LocalSlot.parse("19:00, 08:00"))
    }
}
