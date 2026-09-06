package io.retentionkit.notifications

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.retentionkit.core.*
import java.util.UUID

sealed class NotificationOutcome {
    data class PostSubmitted(val notificationId: Int, val receiptPersisted: Boolean = true) : NotificationOutcome()
    data class Skipped(val reason: String) : NotificationOutcome()
    data class Failed(val stage: String) : NotificationOutcome()
}

/** Standard marketing campaigns. Install once in Application through RetentionRuntime. */
class RetentionNotifications internal constructor(
    options: RetentionNotificationOptions,
    private val suppliedPlatform: NotificationPlatform?,
    private val suppliedDelays: NotificationDelays?,
) : RetentionModule {
    @JvmOverloads constructor(options: RetentionNotificationOptions = RetentionNotificationOptions()) : this(options, null, null)
    override val id = "notifications"
    private val options = options.copy(channelIds = options.channelIds.toMap())
    private val lock = Any()
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private lateinit var runtime: RetentionRuntime
    private lateinit var platform: NotificationPlatform
    private lateinit var delays: NotificationDelays
    private lateinit var delivery: NotificationDeliveryState
    @Volatile private var closed = true
    private var generation = 0L
    private data class Click(val id: String, val expires: Long)
    private var click: Click? = null
    private val seenClicks = LinkedHashSet<String>()
    private val delayed = mutableMapOf<NotificationCampaign, AutoCloseable>()
    private val pendingDelayIds = mutableMapOf<NotificationCampaign, String>()
    private val hostBlocks = mutableMapOf<String, Long>()

    override fun validateConfig(config: RetentionConfigSnapshot): List<String> = NotificationProfile.errors(config) + buildList {
        if (options.smallIconRes <= 0) add("A small notification icon is required")
        if (options.channelIds.values.any { it.isBlank() || it.length > 128 }) add("Invalid notification channel ID")
    }

    override fun attach(runtime: RetentionRuntime) = synchronized(lock) {
        this.runtime = runtime
        platform = suppliedPlatform ?: AndroidNotificationPlatform(runtime.localizedContext())
        delays = suppliedDelays ?: MainNotificationDelays()
        delivery = NotificationDeliveryState(runtime.store)
        platform.createChannels(options)
        // Short ad/onboarding delays intentionally do not survive a new process.
        closed = false
        active = this
    }

    override fun onSignal(signal: RetentionSignal) {
        var foregroundRefresh = false
        var phaseChanged = false
        synchronized(lock) {
            if (closed) return
            when (signal) {
                is RetentionSignal.AdClicked -> {
                    if (runtime.isForeground && idPattern.matches(signal.clickId) && seenClicks.add(signal.clickId)) {
                        while (seenClicks.size > 32) seenClicks.remove(seenClicks.first())
                        click = Click(signal.clickId, runtime.clock.elapsedRealtimeMillis() + profile().clickTtl)
                    }
                }
                RetentionSignal.ProcessBackground -> armBackground()
                RetentionSignal.ProcessForeground -> { invalidateDelayed(); foregroundRefresh = true }
                is RetentionSignal.ExternalTransitionStarted -> invalidateDelayed()
                is RetentionSignal.HostUiChanged -> {
                    if (signal.visible) {
                        hostBlocks[signal.owner] = runtime.clock.elapsedRealtimeMillis() + signal.durationMillis
                        invalidateDelayed()
                    } else hostBlocks.remove(signal.owner)
                }
                is RetentionSignal.OnboardingChanged -> {
                    if (!signal.active) invalidateDelayed()
                    phaseChanged = true
                }
                is RetentionSignal.ConfigurationChanged, is RetentionSignal.EntitlementChanged,
                RetentionSignal.SetupCompleted -> invalidateDelayed()
                else -> Unit
            }
        }
        if (phaseChanged) reconcile("onboarding_changed")
        if (foregroundRefresh) refreshForegroundNotifications()
    }

    /** Intentional quiet foreground surfaces. Other marketing families require real background triggers. */
    fun refreshForegroundNotifications(): Map<NotificationCampaign, NotificationOutcome> =
        listOf(NotificationCampaign.REMINDER, NotificationCampaign.PINNED).associateWith { campaign ->
            if (closed) NotificationOutcome.Skipped("not_installed") else {
                val now = runtime.clock.wallTimeMillis()
                deliver(campaign, "${campaign.key}:${UUID.randomUUID()}", profile().revision, now, now + profile()[campaign].ttl)
            }
        }

    /** Optional host hook after entries.capture returns Accepted. Dedupe survives Activity recreation. */
    fun recordOpened(entry: RetentionEntry): Boolean = try {
        recordOpenedSafely(entry)
    } catch (error: Exception) {
        report("opened", error)
        false
    }

    private fun recordOpenedSafely(entry: RetentionEntry): Boolean {
        if (closed) return false
        val campaign = NotificationCampaign.entries.firstOrNull { it.key == entry.campaignId && source(it) == entry.source } ?: return false
        if (runtime.entries.pending(entry.token) != entry) return false
        val first = synchronized(lock) {
            runtime.store.transaction(STATE) { state ->
                val key = "opened:${entry.token}"
                if (state.string(key) != null) false else { state.put(key, runtime.clock.wallTimeMillis()); true }
            }.also { accepted ->
                if (accepted && campaign != NotificationCampaign.PINNED && platform.activeOccurrence(campaign) == entry.instanceId) {
                    platform.cancel(campaign)
                }
            }
        }
        if (first) event("opened", campaign)
        return first
    }

    override fun reconcile(reason: String) = synchronized(lock) {
        if (closed) return
        val profile = profile()
        val now = runtime.clock.wallTimeMillis()
        val zone = runtime.clock.timeZone()
        val state = runtime.store.snapshot(STATE)
        val previous = state.entries().filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode)
        val desired = mutableListOf<ScheduledNotification>()
        if (profile.enabled) NotificationCampaign.entries.filter { it.calendar && profile[it].enabled }.forEach { campaign ->
            val installed = runtime.userState.installedAtMillis
            val newUser = now >= installed && now - installed < profile.newUserDays * DAY
            val slots = if (campaign == NotificationCampaign.LOCKSCREEN && newUser) profile[campaign].newUserSlots else profile[campaign].slots
            slots.forEach { slot ->
                desired += CalendarSlots.next(campaign, slot, now, zone, profile[campaign].ttl, profile.revision,
                    state.string("handled:${campaign.key}:${slot.key}"))
            }
        }
        // Publish the whole desired revision before OS effects. A stale delivered callback is rejected.
        runtime.store.transaction(STATE) { s ->
            s.entries().keys.filter { it.startsWith("schedule:") }.forEach(s::remove)
            desired.forEach { s.put("schedule:${it.key}", it.encode()) }
        }
        previous.filter { old -> desired.none { it.key == old.key } }.forEach { safe("cancel_alarm") { platform.cancelAlarm(it) } }
        // Re-arm all desired identities, including after a failed setWindow or OS restart.
        desired.forEach { safe("schedule") { platform.schedule(it) } }
        NotificationCampaign.entries.filter { campaign ->
            !profile.enabled || !profile[campaign].enabled || runtime.marketingEligibility(
                graceMillis = if (campaign == NotificationCampaign.ONBOARDING) profile.onboardingGrace else profile.grace,
                requireBackground = false,
                phase = if (campaign == NotificationCampaign.ONBOARDING) RetentionMarketingPhase.ONBOARDING else RetentionMarketingPhase.AFTER_SETUP
            ) is RetentionEligibility.Blocked
        }.forEach { safe("cancel_ineligible") { platform.cancel(it) } }
        runtime.diagnostics.record("notifications.reconcile", "$reason: ${desired.size} desired inexact alarms, revision ${profile.revision}")
    }

    internal fun receiveAlarm(alarm: ScheduledNotification): NotificationOutcome {
        synchronized(lock) {
            if (closed) return NotificationOutcome.Skipped("not_installed")
            val current = runtime.store.snapshot(STATE).string("schedule:${alarm.key}")
            if (current != alarm.encode() || alarm.revision != runtime.config.revision) return skipped(alarm.campaign, "stale_alarm")
            if (runtime.clock.wallTimeMillis() < alarm.due) {
                // Clock moved backwards after the OS queued delivery. Keep the desired future slot.
                safe("rearm_early") { platform.schedule(alarm) }
                return skipped(alarm.campaign, "not_due")
            }
            runtime.store.transaction(STATE) { state ->
                state.put("handled:${alarm.key}", maxOf(alarm.localDate, state.string("handled:${alarm.key}", alarm.localDate)!!))
            }
            reconcile("alarm_received") // Keep the next slot even when this occurrence is blocked/fails.
        }
        return deliver(alarm.campaign, alarm.occurrence, alarm.revision, alarm.due, alarm.expires)
    }

    internal fun dismiss(campaign: NotificationCampaign, occurrence: String) = synchronized(lock) {
        if (closed) return@synchronized
        // OS metadata is authoritative even if the receipt failed after notify. An old delete
        // callback must not cancel a newer replacement merely because disk still names the old one.
        if (platform.activeOccurrence(campaign) == occurrence) {
            if (safe("dismiss_cancel") { platform.cancel(campaign) }) {
                safe("dismiss_state") { runtime.store.transaction(STATE) { it.remove("active:${campaign.key}") } }
                event("dismissed", campaign)
            }
        } else if (!platform.active(campaign)) {
            // A swipe has already removed the matching notification before deleteIntent runs.
            val matches = runtime.store.transaction(STATE) { s ->
                if (s.string("active:${campaign.key}") != occurrence) false else { s.remove("active:${campaign.key}"); true }
            }
            if (matches) event("dismissed", campaign)
        }
    }

    override fun shutdown() = synchronized(lock) {
        if (closed) return
        closed = true
        invalidateDelayed()
        hostBlocks.clear()
        telemetryHandler.removeCallbacksAndMessages(null)
        if (active === this) active = null
        // OS calendar alarms stay durable for the next Application install. No background timer survives.
    }

    private fun profile() = NotificationProfile.read(runtime.config)

    private fun invalidateDelayed() {
        generation++
        click = null
        delayed.values.forEach { it.close() }
        delayed.clear()
        pendingDelayIds.clear()
    }

    private fun armBackground() {
        if (runtime.isForeground) return
        val profile = profile()
        val click = this.click
        this.click = null
        if (click != null && runtime.clock.elapsedRealtimeMillis() < click.expires) {
            armDelay(NotificationCampaign.AD_RETURN, click.id, click.expires, profile)
        }
        if (runtime.userState.onboardingActive && !runtime.userState.setupCompleted) {
            armDelay(NotificationCampaign.ONBOARDING, UUID.randomUUID().toString(), runtime.clock.elapsedRealtimeMillis() + profile.onboardingTtl, profile)
        }
    }

    private fun armDelay(campaign: NotificationCampaign, token: String, tokenExpiry: Long, profile: NotificationProfile) {
        if (!profile.enabled || !profile[campaign].enabled) return
        delayed.remove(campaign)?.close()
        val capturedGeneration = generation
        val delayId = UUID.randomUUID().toString()
        pendingDelayIds[campaign] = delayId
        val now = runtime.clock.wallTimeMillis()
        val expiry = now + minOf(profile[campaign].ttl, (tokenExpiry - runtime.clock.elapsedRealtimeMillis()).coerceAtLeast(0))
        delayed[campaign] = delays.post(profile.backgroundDelay) {
            val valid = synchronized(lock) {
                if (closed || capturedGeneration != generation || pendingDelayIds[campaign] != delayId || runtime.clock.elapsedRealtimeMillis() >= tokenExpiry) false
                else { delayed.remove(campaign); pendingDelayIds.remove(campaign); true }
            }
            if (valid) deliver(campaign, "${campaign.key}:${UUID.nameUUIDFromBytes(token.toByteArray(Charsets.UTF_8))}", profile.revision, now, expiry, capturedGeneration)
        }
    }

    private fun gate(campaign: NotificationCampaign, revision: Long, due: Long, expires: Long, expectedGeneration: Long?): String? {
        if (closed) return "not_installed"
        val profile = profile()
        if (revision != profile.revision) return "stale_revision"
        if (!profile.enabled || !profile[campaign].enabled) return "disabled"
        val now = runtime.clock.wallTimeMillis()
        if (now < due || now >= expires) return "expired"
        if (expectedGeneration != null && generation != expectedGeneration) return "cancelled_generation"
        hostBlocks.entries.removeAll { runtime.clock.elapsedRealtimeMillis() >= it.value }
        if (hostBlocks.isNotEmpty()) return "host_ui"
        val eligibility = runtime.marketingEligibility(
            graceMillis = if (campaign == NotificationCampaign.ONBOARDING) profile.onboardingGrace else profile.grace,
            requireBackground = !campaign.foreground,
            phase = if (campaign == NotificationCampaign.ONBOARDING) RetentionMarketingPhase.ONBOARDING else RetentionMarketingPhase.AFTER_SETUP)
        if (eligibility is RetentionEligibility.Blocked) return eligibility.reason.name.lowercase()
        if (campaign.foreground && !runtime.isForeground) return "background"
        if (campaign == NotificationCampaign.WINBACK) {
            val lastActive = runtime.userState.lastActiveAtMillis
            if (lastActive <= 0 || now < lastActive || now - lastActive < profile.winbackInactivity) return "not_inactive"
        }
        platform.blocked(options.channel(campaign))?.let { return it }
        if (campaign == NotificationCampaign.LOCKSCREEN && !profile.replaceLockscreen && platform.active(campaign)) return "still_active"
        if (campaign == NotificationCampaign.PINNED && platform.active(campaign)) return "still_active"
        return null
    }

    internal fun deliver(campaign: NotificationCampaign, occurrence: String, revision: Long, due: Long, expires: Long,
                         expectedGeneration: Long? = null): NotificationOutcome {
        try {
            synchronized(lock) { gate(campaign, revision, due, expires, expectedGeneration)?.let { return skipped(campaign, it) } }
            val features = runtime.features()
            val allowed = features.map { it.id }.toSet()
            val content = options.contentProvider.content(runtime.localizedContext(), campaign, features).map { it.copy(actions = it.actions.toList()) }
            if (content.isEmpty()) return skipped(campaign, "empty_content")
            require(content.size <= 100 && content.map { it.id }.distinct().size == content.size) { "Invalid content catalogue" }
            content.forEach { item ->
                require(idPattern.matches(item.id) && item.title.isNotBlank() && item.title.length <= 200 && item.body.length <= 1000)
                require(item.destination in allowed && item.actions.size <= 4 && item.actions.map { it.id }.distinct().size == item.actions.size)
                require(item.actions.all { idPattern.matches(it.id) && it.label.isNotBlank() && it.label.length <= 200 && it.destination in allowed })
            }
            val last = delivery.lastContent(campaign)
            val index = content.indexOfFirst { it.id == last }
            val chosen = content[(index + 1).mod(content.size)]
            val context = runtime.localizedContext()
            fun entryIntent(action: String, destination: String): PendingIntent {
                val entry = RetentionEntry(source(campaign), destination, action,
                    token = UUID.nameUUIDFromBytes("$occurrence:$action:$destination".toByteArray(Charsets.UTF_8)).toString(),
                    campaignId = campaign.key, instanceId = occurrence, createdAtMillis = due, expiresAtMillis = expires,
                    mode = if (campaign == NotificationCampaign.PINNED) RetentionEntryMode.REUSABLE else RetentionEntryMode.ONCE)
                val intent = runtime.createEntryIntent(entry) ?: error("No valid explicit host route")
                return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val main = entryIntent("open", chosen.destination)
            val dismiss = NotificationDismissReceiver.pending(context, campaign, occurrence)
            val actions = chosen.actions.map { PreparedNotificationAction(it.id, it.label, it.iconRes, entryIntent(it.id, it.destination)) }
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, options.channel(campaign)) else Notification.Builder(context)
            val request = NotificationRenderRequest(context, campaign, chosen, main, dismiss, actions)
            // Partner code runs outside the module/store locks. It may synchronously update config.
            options.renderer.decorate(request, builder)
            val nativeActions = actions.map { Notification.Action.Builder(it.iconRes, it.label, it.pendingIntent).build() }.toMutableList()
            if (campaign == NotificationCampaign.REMINDER) nativeActions.add(0, Notification.Action.Builder(0, context.getString(R.string.rk_n_later), dismiss).build())
            builder.addExtras(Bundle().apply { putString(OCCURRENCE_EXTRA, occurrence) })
            builder.setSmallIcon(options.smallIconRes).setContentTitle(chosen.title).setContentText(chosen.body)
                .setContentIntent(main).setDeleteIntent(dismiss).setActions(*nativeActions.toTypedArray())
                .setAutoCancel(campaign != NotificationCampaign.PINNED)
                .setOngoing(campaign == NotificationCampaign.PINNED).setOnlyAlertOnce(campaign.foreground)
                .setPriority(if (campaign.foreground) Notification.PRIORITY_LOW else Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setWhen(due).setShowWhen(true)
            if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(options.channel(campaign)).setTimeoutAfter((expires - runtime.clock.wallTimeMillis()).coerceAtLeast(1))
            if (campaign.foreground) builder.setSound(null).setVibrate(null).setDefaults(0)
            val notification = builder.build().apply {
                // Clear any custom renderer full-screen capability without requesting its permission.
                fullScreenIntent = null
                flags = flags and Notification.FLAG_HIGH_PRIORITY.inv()
            }
            return synchronized(lock) {
                gate(campaign, revision, due, expires, expectedGeneration)?.let { return@synchronized skipped(campaign, it) }
                val now = runtime.clock.wallTimeMillis()
                if (content.size > 1 && delivery.lastContent(campaign) != last && delivery.lastContent(campaign) == chosen.id) {
                    return@synchronized skipped(campaign, "rotation_changed")
                }
                delivery.claim(campaign, occurrence, now, CalendarSlots.date(now, runtime.clock.timeZone()), profile()[campaign], chosen.id)
                    ?.let { return@synchronized skipped(campaign, it) }
                // Claim is durable before notify. Recheck after the storage boundary as well.
                val blocked = gate(campaign, revision, due, expires, expectedGeneration)
                if (blocked != null) {
                    delivery.finish(campaign, occurrence, false)
                    return@synchronized skipped(campaign, blocked)
                }
                try { platform.post(campaign, notification) } catch (error: Exception) {
                    safe("release_failed_claim") { delivery.finish(campaign, occurrence, false) }
                    report("post", error)
                    event("post_failed", campaign)
                    return@synchronized NotificationOutcome.Failed("post")
                }
                val persisted = safe("persist_receipt") { delivery.finish(campaign, occurrence, true) }
                event("post_submitted", campaign, mapOf("receipt_persisted" to persisted.toString()))
                NotificationOutcome.PostSubmitted(campaign.notificationId, persisted)
            }
        } catch (error: Exception) {
            report("prepare_or_state", error)
            event("failed", campaign, mapOf("stage" to "prepare_or_state"))
            return NotificationOutcome.Failed("prepare_or_state")
        }
    }

    private fun skipped(campaign: NotificationCampaign, reason: String): NotificationOutcome.Skipped {
        event("skipped", campaign, mapOf("reason" to reason))
        return NotificationOutcome.Skipped(reason)
    }
    private fun event(name: String, campaign: NotificationCampaign, attributes: Map<String, String> = emptyMap()) {
        // Sink callbacks may call core.signal/updateConfig. Dispatch outside module/store locks to
        // avoid lock inversion with core's serialized signal delivery. Drop queued telemetry on shutdown.
        val event = RetentionEvent("retention_noti_$name", attributes + ("campaign" to campaign.key))
        telemetryHandler.post { if (!closed) runtime.emit(event) }
    }
    private fun report(stage: String, error: Exception) = runtime.diagnostics.record("notifications.$stage", "Notification operation failed", RetentionDiagnosticLevel.ERROR, error)
    private inline fun safe(stage: String, action: () -> Unit): Boolean = try { action(); true } catch (error: Exception) { report(stage, error); false }

    internal companion object {
        @Volatile var active: RetentionNotifications? = null
        fun source(campaign: NotificationCampaign): RetentionEntrySource = when (campaign) {
            NotificationCampaign.DAILY -> RetentionEntrySource.DAILY
            NotificationCampaign.WINBACK -> RetentionEntrySource.WINBACK
            NotificationCampaign.ONBOARDING -> RetentionEntrySource.ONBOARDING_ABANDONMENT
            NotificationCampaign.AD_RETURN -> RetentionEntrySource.AD_RETURN
            NotificationCampaign.REMINDER -> RetentionEntrySource.REMINDER
            NotificationCampaign.PINNED -> RetentionEntrySource.PINNED
            NotificationCampaign.LOCKSCREEN -> RetentionEntrySource.LOCKSCREEN
        }
    }
}
