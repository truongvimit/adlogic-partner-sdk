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
    private var exitArmedThisDeparture = false
    private data class Click(val id: String, val expires: Long)
    private var click: Click? = null
    private val seenClicks = LinkedHashSet<String>()
    private val delayed = mutableMapOf<NotificationCampaign, AutoCloseable>()
    private val pendingDelayIds = mutableMapOf<NotificationCampaign, String>()
    private val hostBlocks = mutableMapOf<String, Long>()
    private data class ForegroundRequest(val occurrence: String, val created: Long)
    private val foregroundPending = linkedMapOf<NotificationCampaign, ForegroundRequest>()
    private var refreshingForeground = false
    private var retryingDeferred = false

    override fun validateConfig(config: RetentionConfigSnapshot): List<String> = NotificationProfile.errors(config, options.preset) + buildList {
        if (options.smallIconRes <= 0) add("A small notification icon is required")
        if (options.channelIds.values.any { it.isBlank() || it.length > 128 }) add("Invalid notification channel ID")
    }

    override fun attach(runtime: RetentionRuntime) = synchronized(lock) {
        this.runtime = runtime
        platform = suppliedPlatform ?: AndroidNotificationPlatform(runtime.localizedContext())
        delays = suppliedDelays ?: MainNotificationDelays()
        delivery = NotificationDeliveryState(runtime.store)
        platform.createChannels(options)
        // Onboarding stays session-bound; common exit campaigns also keep a durable inexact fallback.
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
                RetentionSignal.ProcessBackground -> {
                    foregroundPending.clear()
                    armBackground()
                }
                RetentionSignal.ProcessForeground -> {
                    exitArmedThisDeparture = false
                    invalidateDelayed()
                    if (profile().persistentLockscreen) safe("opened_app_cancel_lock") {
                        platform.cancel(NotificationCampaign.LOCKSCREEN)
                        runtime.store.transaction(STATE) { it.remove("active:lockscreen") }
                    }
                    foregroundRefresh = true
                }
                is RetentionSignal.ExternalTransitionStarted -> invalidateDelayed()
                is RetentionSignal.HostUiChanged -> {
                    if (signal.visible) {
                        hostBlocks[signal.owner] = runtime.clock.elapsedRealtimeMillis() + signal.durationMillis
                        invalidateDelayed()
                    } else hostBlocks.remove(signal.owner)
                    phaseChanged = true
                }
                is RetentionSignal.ExternalTransitionFinished -> phaseChanged = true
                is RetentionSignal.OnboardingChanged -> {
                    if (!signal.active) invalidateDelayed()
                    phaseChanged = true
                }
                is RetentionSignal.EntitlementChanged -> {
                    if (signal.entitlement != RetentionEntitlement.NON_SUBSCRIBER) invalidateDelayed()
                }
                is RetentionSignal.ConfigurationChanged, RetentionSignal.SetupCompleted -> invalidateDelayed()
                else -> Unit
            }
        }
        if (phaseChanged) reconcile("eligibility_changed")
        if (foregroundRefresh) refreshForegroundNotifications()
    }

    /** Intentional quiet foreground surfaces. Other marketing families require real background triggers. */
    fun refreshForegroundNotifications(): Map<NotificationCampaign, NotificationOutcome> {
        synchronized(lock) {
            if (closed) return foregroundCampaigns.associateWith { NotificationOutcome.Skipped("not_installed") }
            val now = runtime.clock.wallTimeMillis()
            foregroundCampaigns.forEach { foregroundPending[it] = ForegroundRequest("${it.key}:${UUID.randomUUID()}", now) }
        }
        return retryForegroundNotifications()
    }

    private fun retryForegroundNotifications(): Map<NotificationCampaign, NotificationOutcome> {
        val requests = synchronized(lock) {
            if (closed || !runtime.isForeground || refreshingForeground) return emptyMap()
            refreshingForeground = true
            foregroundPending.toMap()
        }
        try {
            return requests.mapValues { (campaign, request) ->
                val current = synchronized(lock) { profile() to generation }
                val outcome = deliver(campaign, request.occurrence, current.first.revision,
                    request.created, request.created + current.first[campaign].ttl, current.second)
                synchronized(lock) {
                    if (foregroundPending[campaign] == request && (outcome is NotificationOutcome.PostSubmitted ||
                            outcome == NotificationOutcome.Skipped("still_active"))) foregroundPending.remove(campaign)
                }
                outcome
            }
        } finally { synchronized(lock) { refreshingForeground = false } }
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

    override fun reconcile(reason: String) {
        reconcileSchedules(reason)
        retryDeferredCalendars()
        // Setup/Billing/config and explicit permission reconciliation can complete after the one
        // process-foreground event. Preserve that open until it can post; callbacks do not invent
        // another open after successful submission. Partner renderers run outside our state lock.
        retryForegroundNotifications()
    }

    private fun reconcileSchedules(reason: String) = synchronized(lock) {
        if (closed) return
        val profile = profile()
        val now = runtime.clock.wallTimeMillis()
        val zone = runtime.clock.timeZone()
        val state = runtime.store.snapshot(STATE)
        val previous = state.entries().filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode)
        val desired = previous.filter { !it.campaign.calendar && profile.durableExit && profile.enabled && profile[it.campaign].enabled &&
            it.revision == profile.revision && now < it.expires }.toMutableList()
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
            s.entries().filterKeys { it.startsWith("deferred:") }.forEach { (key, raw) ->
                if (desired.none { it.encode() == raw }) s.remove(key)
            }
        }
        previous.filter { old -> desired.none { it.key == old.key } }.forEach { safe("cancel_alarm") { platform.cancelAlarm(it) } }
        // Re-arm all desired identities, including after a failed setWindow or OS restart.
        desired.forEach { alarm -> safe("schedule") {
            // UNKNOWN is a current-process Billing state, not a decision to skip today's slot.
            // Keep its original due/expiry/identity and schedule only expiry cleanup while waiting;
            // do not repeatedly arm a past-due alarm and spin the cold receiver.
            val deferred = state.string("deferred:${alarm.key}") == alarm.encode()
            platform.schedule(alarm, if (deferred && runtime.userState.entitlement == RetentionEntitlement.UNKNOWN) alarm.expires else alarm.due)
        } }
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
        }
        val outcome = deliver(alarm.campaign, alarm.occurrence, alarm.revision, alarm.due, alarm.expires)
        synchronized(lock) {
            if (closed) return outcome
            // Rendering can synchronously replace config; an old completion must not rewrite it.
            runtime.store.transaction(STATE) { state ->
                if (state.string("schedule:${alarm.key}") != alarm.encode()) return@transaction
                if (alarm.campaign.calendar && outcome == NotificationOutcome.Skipped("entitlement_unknown") &&
                    runtime.clock.wallTimeMillis() < alarm.expires) {
                    state.put("deferred:${alarm.key}", alarm.encode())
                } else {
                    state.remove("deferred:${alarm.key}")
                    val calendarDate = alarm.calendarDate
                    if (calendarDate != null) state.put("handled:${alarm.key}", maxOf(calendarDate, state.string("handled:${alarm.key}", calendarDate)!!))
                    else state.remove("schedule:${alarm.key}")
                }
            }
            if (!alarm.campaign.calendar) safe("cancel_exit_alarm") { platform.cancelAlarm(alarm) }
        }
        reconcile("alarm_received")
        return outcome
    }

    private fun retryDeferredCalendars() {
        val pending = synchronized(lock) {
            if (closed || retryingDeferred || runtime.userState.entitlement == RetentionEntitlement.UNKNOWN) return
            retryingDeferred = true
            runtime.store.snapshot(STATE).entries().filterKeys { it.startsWith("deferred:") }.values.map(ScheduledNotification::decode)
        }
        try { pending.forEach(::receiveAlarm) }
        finally { synchronized(lock) { retryingDeferred = false } }
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
        invalidateDelayed(cancelDurableExit = false)
        hostBlocks.clear()
        foregroundPending.clear()
        telemetryHandler.removeCallbacksAndMessages(null)
        if (active === this) active = null
        // OS calendar alarms stay durable for the next Application install. No background timer survives.
    }

    private fun profile() = NotificationProfile.read(runtime.config, options.preset)

    private fun invalidateDelayed(cancelDurableExit: Boolean = true) {
        generation++
        click = null
        delayed.values.forEach { it.close() }
        delayed.clear()
        pendingDelayIds.clear()
        if (cancelDurableExit) {
            val exits = runtime.store.transaction(STATE) { state ->
                state.entries().filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode)
                    .filter { !it.campaign.calendar }.onEach { state.remove("schedule:${it.key}") }
            }
            exits.forEach { safe("cancel_exit_alarm") { platform.cancelAlarm(it) } }
        }
    }

    private fun armBackground() {
        if (runtime.isForeground) return
        val profile = profile()
        val click = this.click
        this.click = null
        if (runtime.userState.setupCompleted && profile.durableExit) {
            if (exitArmedThisDeparture) return
            exitArmedThisDeparture = true
        }
        if (click != null && runtime.clock.elapsedRealtimeMillis() < click.expires) {
            if (profile.durableExit && runtime.userState.setupCompleted) armDurableExit(NotificationCampaign.AD_RETURN, click.id, profile, click.expires)
            else armDelay(NotificationCampaign.AD_RETURN, click.id, click.expires, profile)
        } else if (runtime.userState.setupCompleted) {
            if (profile.durableExit) armDurableExit(NotificationCampaign.APP_EXIT, UUID.randomUUID().toString(), profile)
            else armDelay(NotificationCampaign.APP_EXIT, UUID.randomUUID().toString(), runtime.clock.elapsedRealtimeMillis() + profile[NotificationCampaign.APP_EXIT].ttl, profile)
        }
        if (runtime.userState.onboardingActive && !runtime.userState.setupCompleted) {
            armDelay(NotificationCampaign.ONBOARDING, UUID.randomUUID().toString(), runtime.clock.elapsedRealtimeMillis() + profile.onboardingTtl, profile)
        }
    }

    private fun armDurableExit(campaign: NotificationCampaign, token: String, profile: NotificationProfile, tokenExpiry: Long? = null) {
        if (!profile.enabled || !profile[campaign].enabled) return
        // Persist BEFORE scheduling: receivers reject stale/replaced/revoked exits after process death.
        val now = runtime.clock.wallTimeMillis()
        val due = now + profile.backgroundDelay
        val expires = now + minOf(profile[campaign].ttl, tokenExpiry?.minus(runtime.clock.elapsedRealtimeMillis()) ?: profile[campaign].ttl)
        if (expires <= due) return
        val alarm = ScheduledNotification(campaign, "exit", UUID.nameUUIDFromBytes(token.toByteArray(Charsets.UTF_8)).toString(), due, expires, profile.revision)
        val old = runtime.store.transaction(STATE) { state ->
            val previous = state.entries().filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode).filter { !it.campaign.calendar }
            previous.forEach { state.remove("schedule:${it.key}") }
            state.put("schedule:${alarm.key}", alarm.encode())
            previous
        }
        old.forEach { safe("cancel_exit_alarm") { platform.cancelAlarm(it) }; delayed.remove(it.campaign)?.close() }
        safe("schedule_exit") { platform.schedule(alarm) }
        delayed[campaign] = delays.post(profile.backgroundDelay) { receiveAlarm(alarm) }
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
            if (profile.winbackMaxInactivity > 0 && now - lastActive > profile.winbackMaxInactivity) return "outside_inactivity_window"
        }
        platform.blocked(options.channel(campaign))?.let { return it }
        if (campaign == NotificationCampaign.LOCKSCREEN && !profile.replaceLockscreen && platform.active(campaign)) return "still_active"
        if (campaign == NotificationCampaign.PINNED && platform.active(campaign)) return "still_active"
        return null
    }

    private fun arbitration(campaign: NotificationCampaign, availableHigherContent: Set<NotificationCampaign>): String? {
        val profile = profile()
        if (!profile.arbitration) return null
        if (campaign.updates && NotificationCampaign.entries.any { it.updates && platform.active(it) }) return "group_visible"
        val now = runtime.clock.wallTimeMillis()
        // Calendar/exit callbacks may race. An eligible higher-priority due occurrence wins,
        // regardless of receiver ordering; a blocked/capped campaign must not starve others.
        val pending = runtime.store.snapshot(STATE).entries().filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode)
        if (pending.any { candidate ->
                candidate.campaign in availableHigherContent && candidate.campaign.priority > campaign.priority && candidate.due <= now &&
                    gate(candidate.campaign, candidate.revision, candidate.due, candidate.expires, null) == null &&
                    !(candidate.campaign.updates && NotificationCampaign.entries.any { it.updates && platform.active(it) }) &&
                    delivery.budgetBlocked(candidate.campaign, now, CalendarSlots.date(now, runtime.clock.timeZone()), profile[candidate.campaign]) == null
            }) return "higher_priority_pending"
        return null
    }

    internal fun deliver(campaign: NotificationCampaign, occurrence: String, revision: Long, due: Long, expires: Long,
                         expectedGeneration: Long? = null): NotificationOutcome {
        try {
            synchronized(lock) {
                gate(campaign, revision, due, expires, expectedGeneration)?.let { return skipped(campaign, it) }
            }
            val features = runtime.features()
            val allowed = features.map { it.id }.toSet()
            fun prepareContent(candidate: NotificationCampaign): List<NotificationContent> {
                val values = options.contentProvider.content(runtime.localizedContext(), candidate, features).map { it.copy(actions = it.actions.toList()) }
                require(values.size <= 100 && values.map { it.id }.distinct().size == values.size) { "Invalid content catalogue" }
                values.forEach { item ->
                    require(idPattern.matches(item.id) && item.title.isNotBlank() && item.title.length <= 200 && item.body.length <= 1000 && item.expandedTitle.isNotBlank() && item.expandedTitle.length <= 200)
                    require(item.destination in allowed && item.actions.size <= 4 && item.actions.map { it.id }.distinct().size == item.actions.size)
                    require(item.actions.all { idPattern.matches(it.id) && it.label.isNotBlank() && it.label.length <= 200 && it.destination in allowed })
                }
                return values
            }
            val content = prepareContent(campaign)
            if (content.isEmpty()) return skipped(campaign, "empty_content")
            val dueHigher = synchronized(lock) {
                if (!profile().arbitration) emptySet() else runtime.store.snapshot(STATE).entries()
                    .filterKeys { it.startsWith("schedule:") }.values.map(ScheduledNotification::decode)
                    .filter { it.campaign.priority > campaign.priority && it.due <= runtime.clock.wallTimeMillis() && runtime.clock.wallTimeMillis() < it.expires }
                    .map { it.campaign }.toSet()
            }
            // Partner content callbacks remain outside locks. Invalid/empty higher-priority content
            // is not an eligible pending notification and must not starve a valid lower one.
            val availableHigherContent = dueHigher.filter { higher ->
                try { prepareContent(higher).isNotEmpty() } catch (error: Exception) { report("priority_content", error); false }
            }.toSet()
            synchronized(lock) {
                gate(campaign, revision, due, expires, expectedGeneration)?.let { return skipped(campaign, it) }
                arbitration(campaign, availableHigherContent)?.let { return skipped(campaign, it) }
            }
            val last = delivery.lastContent(campaign)
            val index = content.indexOfFirst { it.id == last }
            val chosen = if (campaign == NotificationCampaign.LOCKSCREEN && options.preset == NotificationPreset.COMMON_PLAN && content.size > 1)
                content.filter { it.id != last }.random() else content[(index + 1).mod(content.size)]
            val persistentLockscreen = campaign == NotificationCampaign.LOCKSCREEN && profile().persistentLockscreen
            val context = runtime.localizedContext()
            fun entryIntent(action: String, destination: String): PendingIntent {
                val entry = RetentionEntry(source(campaign), destination, action,
                    token = UUID.nameUUIDFromBytes("$occurrence:$action:$destination".toByteArray(Charsets.UTF_8)).toString(),
                    // Delivery/display TTL controls posting and OS timeout, not an accepted user's
                    // setup journey. Core retains routes for at most seven days; reusable surfaces
                    // materialize that bounded lifetime on each actual tap.
                    campaignId = campaign.key, instanceId = occurrence, createdAtMillis = due,
                    mode = if (campaign == NotificationCampaign.PINNED || persistentLockscreen) RetentionEntryMode.REUSABLE else RetentionEntryMode.ONCE)
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
                .setPriority(when {
                    campaign.foreground -> Notification.PRIORITY_LOW
                    options.preset == NotificationPreset.COMMON_PLAN && (campaign.updates || campaign == NotificationCampaign.LOCKSCREEN) -> Notification.PRIORITY_HIGH
                    else -> Notification.PRIORITY_DEFAULT
                })
                .setVisibility(if (persistentLockscreen) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setWhen(due).setShowWhen(true)
            if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(options.channel(campaign)).setTimeoutAfter(if (persistentLockscreen) 0L else (expires - runtime.clock.wallTimeMillis()).coerceAtLeast(1))
            if (campaign.foreground || options.preset == NotificationPreset.COMMON_PLAN && campaign == NotificationCampaign.DAILY) builder.setSound(null).setVibrate(null).setDefaults(0)
            else if (options.preset == NotificationPreset.COMMON_PLAN) builder.setDefaults(Notification.DEFAULT_ALL)
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
                arbitration(campaign, availableHigherContent)?.let { return@synchronized skipped(campaign, it) }
                delivery.claim(campaign, occurrence, now, CalendarSlots.date(now, runtime.clock.timeZone()), profile()[campaign], chosen.id, profile().guardWindow)
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
                event("post_submitted", campaign, mapOf("receipt_persisted" to persisted.toString()) + if (campaign == NotificationCampaign.LOCKSCREEN) mapOf("wake_capability" to "os_controlled") else emptyMap())
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
        private val foregroundCampaigns = listOf(NotificationCampaign.REMINDER, NotificationCampaign.PINNED)
        @Volatile var active: RetentionNotifications? = null
        fun source(campaign: NotificationCampaign): RetentionEntrySource = when (campaign) {
            NotificationCampaign.DAILY -> RetentionEntrySource.DAILY
            NotificationCampaign.WINBACK -> RetentionEntrySource.WINBACK
            NotificationCampaign.ONBOARDING -> RetentionEntrySource.ONBOARDING_ABANDONMENT
            NotificationCampaign.AD_RETURN -> RetentionEntrySource.AD_RETURN
            NotificationCampaign.REMINDER -> RetentionEntrySource.REMINDER
            NotificationCampaign.PINNED -> RetentionEntrySource.PINNED
            NotificationCampaign.LOCKSCREEN -> RetentionEntrySource.LOCKSCREEN
            NotificationCampaign.APP_EXIT -> RetentionEntrySource.OTHER
        }
    }
}
