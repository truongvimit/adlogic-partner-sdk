package io.retentionkit.notifications

import io.retentionkit.core.RetentionStore
import org.json.JSONObject

internal const val STATE = "notifications.state.v1"

/** Claim, cap reservation, cooldown and rotation share one durable namespace. */
internal class NotificationDeliveryState(private val store: RetentionStore) {
    fun lastContent(campaign: NotificationCampaign): String? = store.snapshot(STATE).string("content:${campaign.key}")
    fun claim(campaign: NotificationCampaign, occurrence: String, now: Long, day: String, profile: CampaignProfile, contentId: String): String? =
        store.transaction(STATE) { s ->
            s.entries().filterKeys { it.startsWith("attempt:") }.forEach { (key, raw) ->
                if (JSONObject(raw).getLong("at") < now - 8 * DAY) s.remove(key)
            }
            s.entries().filterKeys { it.startsWith("opened:") }.forEach { (key, raw) -> if (raw.toLong() < now - 8 * DAY) s.remove(key) }
            val key = "attempt:$occurrence"
            if (s.string(key) != null) return@transaction "duplicate"
            val records = s.entries().filterKeys { it.startsWith("attempt:") }.values.map(::JSONObject)
            if (records.size >= 4096) return@transaction "ledger_capacity"
            // Never reset a local-day budget by moving the clock backwards.
            val budgetDay = maxOf(day, s.string("budget_day:${campaign.key}", day)!!)
            val reserved = records.filter { it.getString("campaign") == campaign.key && it.getString("status") in setOf("claimed", "submitted") }
            if (reserved.count { it.getString("day") == budgetDay } >= profile.cap) return@transaction "daily_cap"
            val last = maxOf(s.long("last:${campaign.key}"), reserved.filter { it.getString("status") == "claimed" }.maxOfOrNull { it.getLong("at") } ?: 0)
            if (last > 0 && (now < last || now - last < profile.cooldown)) return@transaction "cooldown"
            s.put("budget_day:${campaign.key}", budgetDay)
            s.put(key, JSONObject().put("campaign", campaign.key).put("at", now).put("day", budgetDay)
                .put("content", contentId).put("status", "claimed").toString())
            null
        }

    fun finish(campaign: NotificationCampaign, occurrence: String, submitted: Boolean) = store.transaction(STATE) { s ->
        val key = "attempt:$occurrence"
        val record = JSONObject(s.string(key) ?: error("Missing delivery claim"))
        check(record.getString("status") == "claimed")
        record.put("status", if (submitted) "submitted" else "failed")
        s.put(key, record.toString())
        if (submitted) {
            s.put("last:${campaign.key}", record.getLong("at"))
            s.put("content:${campaign.key}", record.getString("content"))
            s.put("active:${campaign.key}", occurrence)
        }
    }
}
