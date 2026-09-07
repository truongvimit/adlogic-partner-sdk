package io.retentionkit.notifications

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledNotificationCompatibilityTest {
    @Test fun existingCalendarRecordKeepsItsDateAndPendingIntentIdentity() {
        val record = ScheduledNotification.decode("""{"campaign":"DAILY","slot":"0800","date":"20260907","due":1788739200000,"expires":1788742800000,"revision":2}""")
        assertEquals("20260907", record.calendarDate)
        assertEquals("daily:0800:20260907", record.occurrence)
        assertEquals("daily:0800", record.key)
        assertEquals("20260907", JSONObject(record.encode()).getString("date"))
        assertFalse(JSONObject(record.encode()).has("occurrenceDiscriminator"))
    }

    @Test fun existingExitRecordKeepsItsDepartureIdentityWithoutPretendingItIsADate() {
        val record = ScheduledNotification.decode("""{"campaign":"APP_EXIT","slot":"exit","date":"departure-123","due":1788739200000,"expires":1788739500000,"revision":2}""")
        assertNull(record.calendarDate)
        assertEquals("departure-123", record.occurrenceDiscriminator)
        assertEquals("app_exit:exit:departure-123", record.occurrence)
        assertEquals("app_exit:exit", record.key)
        assertEquals("departure-123", JSONObject(record.encode()).getString("date"))
        assertFalse(JSONObject(record.encode()).has("occurrenceDiscriminator"))
    }
}
