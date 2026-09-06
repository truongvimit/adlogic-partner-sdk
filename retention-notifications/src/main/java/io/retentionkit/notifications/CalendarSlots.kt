package io.retentionkit.notifications

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** java.util.Calendar works on API 24 without desugaring. One occurrence per local date/slot. */
internal data class LocalSlot(val hour: Int, val minute: Int) {
    val key: String get() = String.format(Locale.ROOT, "%02d%02d", hour, minute)
    companion object {
        fun parse(csv: String): List<LocalSlot>? {
            if (csv.isEmpty()) return emptyList()
            val values = csv.split(',')
            if (values.size > 24) return null
            val result = values.map { raw ->
                val match = Regex("([01][0-9]|2[0-3]):([0-5][0-9])").matchEntire(raw.trim()) ?: return null
                LocalSlot(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }
            return result.distinct().takeIf { it.size == result.size }?.sortedBy { it.key }
        }
    }
}
internal data class ScheduledNotification(
    val campaign: NotificationCampaign, val slot: String, val localDate: String,
    val due: Long, val expires: Long, val revision: Long,
) {
    val key get() = "${campaign.key}:$slot"
    val occurrence get() = "$key:$localDate"
    fun encode(): String = JSONObject().put("campaign", campaign.name).put("slot", slot).put("date", localDate)
        .put("due", due).put("expires", expires).put("revision", revision).toString()
    companion object {
        fun decode(raw: String): ScheduledNotification {
            require(raw.length <= 1024)
            val j = JSONObject(raw)
            val result = ScheduledNotification(NotificationCampaign.valueOf(j.getString("campaign")), j.getString("slot"),
                j.getString("date"), j.getLong("due"), j.getLong("expires"), j.getLong("revision"))
            require(result.campaign.calendar && result.slot.matches(Regex("[0-9]{4}")) && result.localDate.matches(Regex("[0-9]{8}")))
            require(result.due > 0 && result.expires > result.due && result.revision >= 0)
            return result
        }
    }
}
internal object CalendarSlots {
    fun date(now: Long, zone: TimeZone): String = GregorianCalendar(zone).apply { timeInMillis = now }.let {
        String.format(Locale.ROOT, "%04d%02d%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }

    fun next(campaign: NotificationCampaign, slot: LocalSlot, now: Long, zone: TimeZone, ttl: Long,
             revision: Long, lastHandledDate: String?): ScheduledNotification {
        val day = GregorianCalendar(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        // A backwards clock must not repeat an already handled civil date. Jump to its next day.
        if (lastHandledDate != null && date(now, zone) <= lastHandledDate) {
            require(lastHandledDate.matches(Regex("[0-9]{8}")))
            day.set(lastHandledDate.substring(0, 4).toInt(), lastHandledDate.substring(4, 6).toInt() - 1, lastHandledDate.substring(6, 8).toInt(), 12, 0, 0)
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        repeat(3) {
            val localDate = date(day.timeInMillis, zone)
            // Lenient Calendar shifts a missing DST time forward by the gap; repeated hours use
            // Calendar's standard-time occurrence. Identity remains one civil date/slot.
            val time = GregorianCalendar(zone).apply {
                clear(); isLenient = true
                set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), slot.hour, slot.minute, 0)
            }.timeInMillis
            if (time + ttl > now) return ScheduledNotification(campaign, slot.key, localDate, time, time + ttl, revision)
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        error("Cannot compute local notification slot")
    }
}
