package io.retentionkit.notifications

import io.retentionkit.core.RetentionStore
import io.retentionkit.core.RetentionTransaction
import org.json.JSONObject

internal const val STATE = "notifications.state.v1"

/** Claim, lifetime reservation, cross-family guard and rotation share one durable namespace. */
internal class NotificationDeliveryState(private val store: RetentionStore) {
    fun lastContent(campaign: NotificationCampaign): String? = store.snapshot(STATE).string("content:${campaign.key}")
    fun budgetBlocked(campaign: NotificationCampaign, now: Long, day: String, profile: CampaignProfile): String? =
        store.transaction(STATE) { budgetBlocked(it, campaign, now, day, profile) }

    private fun importRetainedHistory(s: RetentionTransaction, campaign: NotificationCampaign) {
        if (s.string("total:${campaign.key}") != null) return
        val retained = s.entries().filterKeys { it.startsWith("attempt:") }.values.map(::JSONObject).count {
            it.getString("campaign") == campaign.key && it.getString("status") in setOf("claimed", "submitted")
        }
        s.put("total:${campaign.key}", retained.toLong())
    }

    private fun budgetBlocked(s: RetentionTransaction, campaign: NotificationCampaign, now: Long, day: String, profile: CampaignProfile): String? {
        importRetainedHistory(s, campaign)
        // Lifetime count is independent of the bounded occurrence ledger: pruning never resets it.
        if (profile.lifetimeCap > 0 && s.long("total:${campaign.key}") >= profile.lifetimeCap) return "frequency_cap"
        val budgetDay = maxOf(day, s.string("budget_day:${campaign.key}", day)!!)
        val reserved = s.entries().filterKeys { it.startsWith("attempt:") }.values.map(::JSONObject)
            .filter { it.getString("campaign") == campaign.key && it.getString("status") in setOf("claimed", "submitted") }
        if (profile.cap > 0 && reserved.count { it.getString("day") == budgetDay } >= profile.cap) return "daily_cap"
        val last = maxOf(s.long("last:${campaign.key}"), reserved.filter { it.getString("status") == "claimed" }.maxOfOrNull { it.getLong("at") } ?: 0)
        if (last > 0 && (now < last || now - last < profile.cooldown)) return "cooldown"
        return null
    }

    fun claim(campaign: NotificationCampaign, occurrence: String, now: Long, day: String, profile: CampaignProfile,
              contentId: String, guardWindow: Long = 0): String? = store.transaction(STATE) { s ->
        importRetainedHistory(s, campaign)
        s.entries().filterKeys { it.startsWith("attempt:") }.forEach { (key, raw) ->
            if (JSONObject(raw).getLong("at") < now - 8 * DAY) s.remove(key)
        }
        s.entries().filterKeys { it.startsWith("opened:") }.forEach { (key, raw) -> if (raw.toLong() < now - 8 * DAY) s.remove(key) }
        val key = "attempt:$occurrence"
        if (s.string(key) != null) return@transaction "duplicate"
        val records = s.entries().filterKeys { it.startsWith("attempt:") }.values.map(::JSONObject)
        if (records.size >= 4096) return@transaction "ledger_capacity"
        budgetBlocked(s, campaign, now, day, profile)?.let { return@transaction it }
        if (guardWindow > 0) {
            val last = maxOf(s.long("guard:last"), records.filter {
                it.optBoolean("guarded") && it.getString("status") == "claimed"
            }.maxOfOrNull { it.getLong("at") } ?: 0)
            if (last > 0 && (now < last || now - last < guardWindow)) return@transaction "guard_window"
        }
        s.put("budget_day:${campaign.key}", maxOf(day, s.string("budget_day:${campaign.key}", day)!!))
        // Reserve before notify; uncertain process death spends this slot, never invents success.
        s.put("total:${campaign.key}", s.long("total:${campaign.key}") + 1)
        s.put(key, JSONObject().put("campaign", campaign.key).put("at", now).put("day", s.string("budget_day:${campaign.key}"))
            .put("content", contentId).put("guarded", guardWindow > 0).put("status", "claimed").toString())
        null
    }

    /** Only the caller that has not invoked notify may reopen its reservation for a valid retry. */
    fun abortBeforeNotify(campaign: NotificationCampaign, occurrence: String): Boolean = store.transaction(STATE) { s ->
        val key = "attempt:$occurrence"
        val record = s.string(key)?.let(::JSONObject) ?: return@transaction false
        if (record.getString("status") != "claimed" || record.getString("campaign") != campaign.key) return@transaction false
        s.remove(key)
        s.put("total:${campaign.key}", (s.long("total:${campaign.key}") - 1).coerceAtLeast(0))
        true
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
            if (record.optBoolean("guarded")) s.put("guard:last", maxOf(s.long("guard:last"), record.getLong("at")))
        } else s.put("total:${campaign.key}", (s.long("total:${campaign.key}") - 1).coerceAtLeast(0))
    }
}
