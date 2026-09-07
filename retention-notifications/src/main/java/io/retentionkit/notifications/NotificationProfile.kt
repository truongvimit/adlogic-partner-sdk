package io.retentionkit.notifications

import io.retentionkit.core.RetentionConfigSnapshot

/** COMMON_PLAN follows the shared Noti plan; LEGACY_SDK preserves the original SDK cadence. */
enum class NotificationPreset { COMMON_PLAN, LEGACY_SDK }

/** Stable family identities. APP_EXIT is an observable confirmed departure, never an OS kill callback. */
enum class NotificationCampaign(val key: String, val notificationId: Int) {
    DAILY("daily", 7101), WINBACK("winback", 7102), ONBOARDING("onboarding", 7103),
    AD_RETURN("ad_return", 7104), REMINDER("reminder", 7105), PINNED("pinned", 7106),
    LOCKSCREEN("lockscreen", 7107), APP_EXIT("app_exit", 7108);
    internal val calendar: Boolean get() = this == DAILY || this == WINBACK || this == LOCKSCREEN
    internal val foreground: Boolean get() = this == REMINDER || this == PINNED
    internal val updates: Boolean get() = this == WINBACK || this == AD_RETURN || this == APP_EXIT || this == ONBOARDING
    internal val priority: Int get() = when { this == LOCKSCREEN -> 4; updates -> 3; this == DAILY -> 2; this == REMINDER -> 1; else -> 0 }
}

internal const val DAY = 86_400_000L
internal const val HOUR = 3_600_000L
internal const val MINUTE = 60_000L
internal val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

internal data class CampaignProfile(
    val enabled: Boolean, val slots: List<LocalSlot>, val newUserSlots: List<LocalSlot>,
    val ttl: Long, val cooldown: Long, val cap: Int, val lifetimeCap: Int = 0,
)
internal data class NotificationProfile(
    val revision: Long, val enabled: Boolean, val grace: Long, val newUserDays: Long,
    val winbackInactivity: Long, val onboardingGrace: Long, val backgroundDelay: Long,
    val clickTtl: Long, val onboardingTtl: Long, val replaceLockscreen: Boolean,
    val campaigns: Map<NotificationCampaign, CampaignProfile>,
    val winbackMaxInactivity: Long, val guardWindow: Long, val arbitration: Boolean,
    val persistentLockscreen: Boolean, val durableExit: Boolean,
) {
    operator fun get(campaign: NotificationCampaign) = campaigns.getValue(campaign)

    companion object {
        val defaults: Map<String, String> get() = defaults(NotificationPreset.COMMON_PLAN)
        fun defaults(preset: NotificationPreset): Map<String, String> = buildMap {
            val common = preset == NotificationPreset.COMMON_PLAN
            put("profile_version", "1"); put("enabled", "true"); put("setup_grace_ms", DAY.toString())
            put("new_user_days", "2"); put("winback.inactivity_ms", ((if (common) 14 else 2) * DAY).toString())
            put("winback.max_inactivity_ms", (if (common) 45 * DAY else 0L).toString())
            put("onboarding.grace_ms", (if (common) DAY else 0L).toString()); put("background_delay_ms", "3000")
            put("ad_return.token_ttl_ms", "300000"); put("onboarding.token_ttl_ms", "300000")
            put("lockscreen.replace", "false"); put("lockscreen.persistent", common.toString())
            put("guard_window_ms", (if (common) 30_000L else 0L).toString())
            put("arbitration.enabled", common.toString()); put("app_exit.durable", common.toString())
            NotificationCampaign.entries.forEach { campaign ->
                val key = campaign.key
                put("$key.enabled", (common || campaign != NotificationCampaign.APP_EXIT).toString())
                put("$key.ttl_ms", (if (campaign.foreground) 6 * HOUR else if (campaign.calendar) HOUR else 5 * MINUTE).toString())
                put("$key.cooldown_ms", when (campaign) {
                    NotificationCampaign.DAILY -> HOUR
                    NotificationCampaign.WINBACK -> if (common) HOUR else 12 * HOUR
                    NotificationCampaign.ONBOARDING -> DAY
                    NotificationCampaign.LOCKSCREEN -> HOUR
                    else -> 15 * MINUTE
                }.toString())
                put("$key.daily_cap", when (campaign) {
                    NotificationCampaign.WINBACK -> if (common) "2" else "1"
                    NotificationCampaign.ONBOARDING -> "1"
                    NotificationCampaign.REMINDER -> "4"
                    NotificationCampaign.LOCKSCREEN -> "3"
                    else -> "2"
                })
                put("$key.lifetime_cap", if (common && campaign == NotificationCampaign.WINBACK) "3" else "0")
            }
            put("daily.slots", "08:00,19:00"); put("winback.slots", "11:00,14:00")
            put("lockscreen.slots", "11:30,17:00,20:00"); put("lockscreen.new_user_slots", "11:30,17:00,20:00")
        }.mapKeys { "notifications.${it.key}" }

        fun errors(config: RetentionConfigSnapshot, preset: NotificationPreset = NotificationPreset.COMMON_PLAN): List<String> = buildList {
            val defaults = defaults(preset)
            config.values.filterKeys { it.startsWith("notifications.") }.forEach { (key, value) ->
                if (key !in defaults) { add("Unknown notification key: $key"); return@forEach }
                val valid = when {
                    key.endsWith(".enabled") || key.endsWith(".replace") || key.endsWith(".persistent") || key.endsWith(".durable") -> value.toBooleanStrictOrNull() != null
                    key.endsWith(".slots") || key.endsWith("_slots") -> LocalSlot.parse(value) != null
                    key.endsWith("profile_version") -> value == "1"
                    key.endsWith("new_user_days") -> value.toLongOrNull() in 0L..365L
                    key.endsWith("daily_cap") -> value.toLongOrNull() in 1L..50L
                    key.endsWith("lifetime_cap") -> value.toLongOrNull() in 0L..10_000L
                    key.endsWith("background_delay_ms") -> value.toLongOrNull() in 1L..60_000L
                    key.endsWith("token_ttl_ms") -> value.toLongOrNull() in 1L..300_000L
                    key.endsWith("ttl_ms") -> value.toLongOrNull() in 1L..7 * DAY
                    key.endsWith("_ms") -> value.toLongOrNull() in 0L..365 * DAY
                    else -> false
                }
                if (!valid) add("Invalid value for $key")
            }
            val merged = defaults + config.values
            val delay = merged["notifications.background_delay_ms"]?.toLongOrNull() ?: return@buildList
            listOf("ad_return", "onboarding").forEach {
                val ttl = merged["notifications.$it.token_ttl_ms"]?.toLongOrNull() ?: return@forEach
                if (delay >= ttl) add("Background delay must be shorter than $it token TTL")
            }
            val min = merged["notifications.winback.inactivity_ms"]?.toLongOrNull()
            val max = merged["notifications.winback.max_inactivity_ms"]?.toLongOrNull()
            if (min != null && max != null && max != 0L && max < min) add("Winback maximum inactivity must be zero (unbounded) or at least the minimum")
        }

        fun read(config: RetentionConfigSnapshot, preset: NotificationPreset = NotificationPreset.COMMON_PLAN): NotificationProfile {
            val v = defaults(preset) + config.values
            fun str(key: String) = v.getValue("notifications.$key")
            fun num(key: String) = str(key).toLong()
            fun bool(key: String) = str(key).toBooleanStrict()
            return NotificationProfile(config.revision, bool("enabled"), num("setup_grace_ms"), num("new_user_days"),
                num("winback.inactivity_ms"), num("onboarding.grace_ms"), num("background_delay_ms"),
                num("ad_return.token_ttl_ms"), num("onboarding.token_ttl_ms"), bool("lockscreen.replace"),
                NotificationCampaign.entries.associateWith { c -> CampaignProfile(bool("${c.key}.enabled"),
                    if (c.calendar) LocalSlot.parse(str("${c.key}.slots"))!! else emptyList(),
                    if (c == NotificationCampaign.LOCKSCREEN) LocalSlot.parse(str("lockscreen.new_user_slots"))!! else emptyList(),
                    num("${c.key}.ttl_ms"), num("${c.key}.cooldown_ms"), num("${c.key}.daily_cap").toInt(), num("${c.key}.lifetime_cap").toInt()) },
                num("winback.max_inactivity_ms"), num("guard_window_ms"), bool("arbitration.enabled"),
                bool("lockscreen.persistent"), bool("app_exit.durable"))
        }
    }
}

/** Maps only present known legacy values. Unsupported keys are reported, never guessed or enabled. */
data class NotificationConfigMigration(val overrides: Map<String, String>, val unsupportedKeys: Set<String>)
object NotificationLegacyConfig {
    private val names = mapOf("notiDailyEnabled" to "daily.enabled", "noti_daily_slots" to "daily.slots",
            "notiWinbackEnabled" to "winback.enabled", "noti_winback_slots" to "winback.slots",
            "notiLockscreenEnabled" to "lockscreen.enabled", "noti_lockscreen_slots" to "lockscreen.slots",
            "noti_lockscreen_slots_new" to "lockscreen.new_user_slots", "notiLockscreenReplace" to "lockscreen.replace",
            "notiClickedAdsEnabled" to "ad_return.enabled", "notiOnOpenEnabled" to "reminder.enabled",
            "noti_new_user_days" to "new_user_days")
    /** Exact legacy fetch keys. Returned copy cannot mutate the canonical alias table. */
    @get:JvmStatic val keys: Set<String> get() = names.keys.toSet()
    @JvmStatic fun map(values: Map<String, String>): NotificationConfigMigration {
        return NotificationConfigMigration(values.filterKeys { it in names }.mapKeys { "notifications.${names.getValue(it.key)}" }, values.keys - names.keys)
    }
    /** Source-compatible alias for the existing Translate migration entry point. */
    @JvmStatic fun translate(values: Map<String, String>): NotificationConfigMigration = map(values)
}
