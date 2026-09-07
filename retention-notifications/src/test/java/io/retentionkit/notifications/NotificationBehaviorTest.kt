package io.retentionkit.notifications

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationBehaviorTest {
    private lateinit var app: Application
    private lateinit var clock: TestClock
    private lateinit var store: RetentionStore
    private lateinit var runtime: RetentionRuntime
    private lateinit var module: RetentionNotifications
    private lateinit var platform: FakePlatform
    private lateinit var delays: FakeDelays
    private val events = mutableListOf<RetentionEvent>()

    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext()
        clock = TestClock()
        store = SharedPreferencesRetentionStore(app, "notifications_test_${UUID.randomUUID()}")
        platform = FakePlatform(); delays = FakeDelays(); events.clear()
    }
    @After fun after() { RetentionRuntime.uninstallForTests() }
    private fun install(overrides: Map<String, String> = emptyMap(), options: RetentionNotificationOptions = RetentionNotificationOptions(preset = NotificationPreset.LEGACY_SDK),
                        user: RetentionUserState = user(), eventSink: RetentionEventSink = RetentionEventSink { events += it }) {
        module = RetentionNotifications(options, platform, delays)
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), clock = clock, store = store,
            initialOverrides = overrides, initialUserState = user,
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification), RetentionFeature("saved_items", "Saved items", R.drawable.rk_ic_notification)) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "PartnerActivity")) }, eventSink = eventSink))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
        assertTrue(runtime.diagnostics.snapshot().joinToString(), RetentionNotifications.active === module)
    }
    private fun user() = RetentionUserState(true, false, RetentionEntitlement.NON_SUBSCRIBER,
        clock.now - 4 * DAY, clock.now - 3 * DAY, clock.now - 3 * DAY)
    private fun send(campaign: NotificationCampaign = NotificationCampaign.DAILY, occurrence: String = "daily:${UUID.randomUUID()}",
                     revision: Long = runtime.config.revision): NotificationOutcome =
        module.deliver(campaign, occurrence, revision, clock.now, clock.now + HOUR)

    @Test fun defaultsAreCompleteAndInvalidPatchKeepsLastGoodProfile() {
        install()
        val profile = NotificationProfile.read(runtime.config, NotificationPreset.LEGACY_SDK)
        assertEquals(listOf(LocalSlot(8, 0), LocalSlot(19, 0)), profile[NotificationCampaign.DAILY].slots)
        assertEquals(listOf(LocalSlot(11, 0), LocalSlot(14, 0)), profile[NotificationCampaign.WINBACK].slots)
        assertEquals(listOf(LocalSlot(11, 30), LocalSlot(17, 0), LocalSlot(20, 0)), profile[NotificationCampaign.LOCKSCREEN].slots)
        assertEquals(2 * DAY, profile.winbackInactivity); assertEquals(0L, profile.onboardingGrace)
        val revision = runtime.config.revision
        listOf("daily.slots" to "08:00,99:00", "daily.enabled" to "TRUE", "daily.daily_cap" to "0", "profile_version" to "2", "unknown" to "true").forEach {
            assertTrue(runtime.updateConfig(mapOf("notifications.${it.first}" to it.second)) is RetentionConfigResult.Rejected)
        }
        assertEquals(revision, runtime.config.revision)
        assertTrue(runtime.updateConfig(mapOf("notifications.daily.enabled" to "false")) is RetentionConfigResult.Applied)
        assertEquals(profile[NotificationCampaign.WINBACK], NotificationProfile.read(runtime.config, NotificationPreset.LEGACY_SDK)[NotificationCampaign.WINBACK])
    }

    @Test fun separateUserPermissionChannelAndForegroundGatesDoNotSpendBudget() {
        install(user = user().copy(entitlement = RetentionEntitlement.UNKNOWN))
        assertEquals(NotificationOutcome.Skipped("entitlement_unknown"), send())
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertEquals(NotificationOutcome.Skipped("subscriber"), send())
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        platform.block = "permission_denied"
        assertEquals(NotificationOutcome.Skipped("permission_denied"), send())
        platform.block = "channel_blocked"
        assertEquals(NotificationOutcome.Skipped("channel_blocked"), send())
        platform.block = null
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        assertEquals(1, platform.posts.size)
        assertEquals(1, store.snapshot(STATE).entries().keys.count { it.startsWith("attempt:") })
    }

    @Test fun ordinarySetupGraceIsMeasuredFromCompletion() {
        install(user = user().copy(setupCompleted = false, setupCompletedAtMillis = 0))
        assertEquals(NotificationOutcome.Skipped("setup_incomplete"), send())
        runtime.signal(RetentionSignal.SetupCompleted)
        assertEquals(NotificationOutcome.Skipped("cooldown"), send())
        clock.advance(DAY)
        assertTrue(send() is NotificationOutcome.PostSubmitted)
    }

    @Test fun dailyAndWinbackRequireBackgroundAndWinbackRequiresRealInactivity() {
        install()
        runtime.signal(RetentionSignal.ProcessForeground)
        platform.posts.clear()
        assertEquals(NotificationOutcome.Skipped("foreground"), send())
        runtime.signal(RetentionSignal.ProcessBackground)
        assertEquals(NotificationOutcome.Skipped("not_inactive"), send(NotificationCampaign.WINBACK))
        clock.advance(2 * DAY)
        assertTrue(send(NotificationCampaign.WINBACK) is NotificationOutcome.PostSubmitted)
        assertTrue(send(NotificationCampaign.DAILY) is NotificationOutcome.PostSubmitted)
    }

    @Test fun reminderIsQuietAndLaterIsBroadcastButCtasAreDistinctActivities() {
        install()
        runtime.signal(RetentionSignal.ProcessForeground)
        val reminder = platform.posts.single { it.first == NotificationCampaign.REMINDER }.second
        assertEquals(0, reminder.defaults)
        assertEquals(Notification.PRIORITY_LOW, reminder.priority)
        assertTrue(shadowOf(reminder.actions[0].actionIntent).isBroadcast)
        assertTrue(shadowOf(reminder.actions[1].actionIntent).isActivity)
        val pinned = platform.posts.single { it.first == NotificationCampaign.PINNED }.second
        val first = shadowOf(pinned.actions[0].actionIntent).savedIntent
        val second = shadowOf(pinned.actions[1].actionIntent).savedIntent
        assertFalse(first.filterEquals(second))
        val one = (RetentionEntryCodec.read(first) as RetentionEntryDecodeResult.Valid).entry
        val two = (RetentionEntryCodec.read(second) as RetentionEntryDecodeResult.Valid).entry
        assertEquals("notes", one.destination); assertEquals("saved_items", two.destination)
        assertEquals(RetentionEntryMode.REUSABLE, one.mode)
        assertNotNull(pinned.bigContentView)
        assertNull(pinned.fullScreenIntent)
    }

    @Test fun allSevenFamiliesRenderWithActualSourcesAndNoFullScreenIntent() {
        install()
        NotificationCampaign.entries.filter { it.calendar }.forEach { assertTrue(send(it) is NotificationOutcome.PostSubmitted) }
        runtime.signal(RetentionSignal.ProcessForeground)
        runtime.signal(RetentionSignal.AdClicked("click-one"))
        runtime.signal(RetentionSignal.ProcessBackground)
        clock.advance(3_000); delays.fire()
        assertTrue(platform.posts.any { it.first == NotificationCampaign.AD_RETURN })
        RetentionRuntime.uninstallForTests()
        store = SharedPreferencesRetentionStore(app, "unfinished_${UUID.randomUUID()}")
        install(user = user().copy(setupCompleted = false, onboardingActive = true, setupCompletedAtMillis = 0))
        runtime.signal(RetentionSignal.ProcessBackground)
        clock.advance(3_000); delays.fire()
        assertEquals(NotificationCampaign.entries.filter { it != NotificationCampaign.APP_EXIT }.toSet(), platform.posts.map { it.first }.toSet())
        platform.posts.forEach { (campaign, notification) ->
            val entry = (RetentionEntryCodec.read(shadowOf(notification.contentIntent).savedIntent) as RetentionEntryDecodeResult.Valid).entry
            assertEquals(RetentionNotifications.source(campaign), entry.source)
            assertNull(notification.fullScreenIntent)
            notification.bigContentView?.let { assertNotNull(it.apply(app, null)) }
        }
    }

    @Test fun onboardingRequiresActiveUnfinishedScopeAndInstallBasedOptionalGrace() {
        install(overrides = mapOf("notifications.onboarding.grace_ms" to DAY.toString()),
            user = user().copy(setupCompleted = false, onboardingActive = true, installedAtMillis = clock.now, setupCompletedAtMillis = 0))
        runtime.signal(RetentionSignal.ProcessBackground); clock.advance(3_000); delays.fire()
        assertTrue(platform.posts.isEmpty())
        clock.advance(DAY)
        runtime.signal(RetentionSignal.OnboardingChanged(false)); runtime.signal(RetentionSignal.OnboardingChanged(true))
        runtime.signal(RetentionSignal.ProcessBackground); clock.advance(3_000); delays.fire()
        assertEquals(NotificationCampaign.ONBOARDING, platform.posts.single().first)
        runtime.signal(RetentionSignal.SetupCompleted)
        assertEquals(NotificationOutcome.Skipped("wrong_phase"), send(NotificationCampaign.ONBOARDING))
    }

    @Test fun adDelayCancelsOnForegroundSystemTransitionExpiryAndProcessRestart() {
        install(overrides = mapOf("notifications.reminder.enabled" to "false", "notifications.pinned.enabled" to "false"))
        fun clickAndLeave() {
            runtime.signal(RetentionSignal.ProcessForeground)
            runtime.signal(RetentionSignal.AdClicked(UUID.randomUUID().toString()))
            runtime.signal(RetentionSignal.ProcessBackground)
        }
        clickAndLeave(); runtime.signal(RetentionSignal.ProcessForeground); clock.advance(3_000); delays.fire(evenCancelled = true)
        clickAndLeave(); runtime.signal(RetentionSignal.ExternalTransitionStarted("permission", "system")); clock.advance(3_000); delays.fire(evenCancelled = true)
        runtime.signal(RetentionSignal.ExternalTransitionFinished("permission"))
        clickAndLeave(); clock.advance(300_000); delays.fire()
        clickAndLeave(); RetentionRuntime.uninstallForTests(); install(); clock.advance(3_000); delays.fire(evenCancelled = true)
        assertTrue(platform.posts.isEmpty())
        // A prior cancelled click must not follow a later unrelated background transition.
        runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        clock.advance(3_000); delays.fire()
        assertTrue(platform.posts.isEmpty())
    }

    @Test fun rendererDisablePermissionAndPremiumAreRecheckedBeforeClaim() {
        listOf("disabled", "channel", "premium", "expired").forEach { change ->
            RetentionRuntime.uninstallForTests()
            store = SharedPreferencesRetentionStore(app, "render_${UUID.randomUUID()}")
            platform = FakePlatform()
            install(options = RetentionNotificationOptions(preset = NotificationPreset.LEGACY_SDK, renderer = NotificationRenderer { _, _ ->
                when (change) {
                    "disabled" -> runtime.updateConfig(mapOf("notifications.daily.enabled" to "false"))
                    "channel" -> platform.block = "channel_blocked"
                    "premium" -> runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
                    "expired" -> clock.advance(HOUR)
                }
            }))
            assertTrue(send() is NotificationOutcome.Skipped)
            assertTrue(platform.posts.isEmpty())
            assertFalse(store.snapshot(STATE).entries().keys.any { it.startsWith("attempt:") })
        }
    }

    @Test fun delayedRendererCanCompleteAfterConcurrentConfigUpdateWithoutPosting() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        install(options = RetentionNotificationOptions(preset = NotificationPreset.LEGACY_SDK, renderer = NotificationRenderer { _, _ ->
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
        }))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<NotificationOutcome> { send() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(runtime.updateConfig(mapOf("notifications.daily.enabled" to "false")) is RetentionConfigResult.Applied)
            release.countDown()
            assertEquals(NotificationOutcome.Skipped("stale_revision"), result.get(3, TimeUnit.SECONDS))
            assertTrue(platform.posts.isEmpty())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun failedPostAndRendererFailureDoNotSpendCooldownOrRotation() {
        install()
        platform.failPost = true
        assertEquals(NotificationOutcome.Failed("post"), send())
        assertNull(NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        platform.failPost = false
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        assertEquals("notes", NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        RetentionRuntime.uninstallForTests()
        clock.advance(2 * HOUR)
        install(options = RetentionNotificationOptions(preset = NotificationPreset.LEGACY_SDK, renderer = NotificationRenderer { _, _ -> error("bad renderer") }))
        assertTrue(send() is NotificationOutcome.Failed)
        assertEquals("notes", NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
    }

    @Test fun duplicateClaimAndRotationPersistAcrossProcessRestore() {
        install()
        val occurrence = "daily:stable"
        assertTrue(send(occurrence = occurrence) is NotificationOutcome.PostSubmitted)
        assertEquals(NotificationOutcome.Skipped("duplicate"), send(occurrence = occurrence))
        RetentionRuntime.uninstallForTests(); clock.advance(2 * HOUR); install()
        assertEquals(NotificationOutcome.Skipped("duplicate"), send(occurrence = occurrence))
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        assertEquals("saved_items", NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        assertEquals(NotificationOutcome.Skipped("daily_cap"), send())
    }

    @Test fun pendingCrashClaimReservesBudgetWithoutInventingSuccessfulSubmission() {
        install(overrides = mapOf("notifications.daily.daily_cap" to "1"))
        val delivery = NotificationDeliveryState(store)
        assertNull(delivery.claim(NotificationCampaign.DAILY, "crash", clock.now, CalendarSlots.date(clock.now, clock.zone), NotificationProfile.read(runtime.config, NotificationPreset.LEGACY_SDK)[NotificationCampaign.DAILY], "notes"))
        RetentionRuntime.uninstallForTests(); install()
        assertEquals(NotificationOutcome.Skipped("daily_cap"), send())
        assertNull(NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        assertTrue(platform.posts.isEmpty())
        clock.advance(DAY)
        assertTrue(send() is NotificationOutcome.PostSubmitted)
    }

    @Test fun manyConcurrentTriggersCannotExceedCapOrRepeatContent() {
        install(overrides = mapOf("notifications.daily.cooldown_ms" to "0", "notifications.daily.daily_cap" to "1"))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..12).map { executor.submit<NotificationOutcome> { send() } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, platform.posts.size)
            assertEquals(1, store.snapshot(STATE).entries().keys.count { it.startsWith("attempt:") })
        } finally { executor.shutdownNow() }
    }

    @Test fun lockscreenSkipUsesActualActiveStateAcrossMidnightThenReplaceRotates() {
        install()
        assertTrue(send(NotificationCampaign.LOCKSCREEN) is NotificationOutcome.PostSubmitted)
        clock.advance(DAY)
        RetentionRuntime.uninstallForTests(); install()
        assertEquals(NotificationOutcome.Skipped("still_active"), send(NotificationCampaign.LOCKSCREEN))
        runtime.updateConfig(mapOf("notifications.lockscreen.replace" to "true"))
        assertTrue(send(NotificationCampaign.LOCKSCREEN) is NotificationOutcome.PostSubmitted)
        assertEquals("saved_items", NotificationDeliveryState(store).lastContent(NotificationCampaign.LOCKSCREEN))
        assertEquals(1, platform.activeCampaigns.size)
    }

    @Test fun reducedSlotsCancelOwnedAlarmsAndOldRevisionCannotPostOrResurrect() {
        install()
        val old = platform.scheduled.values.first { it.campaign == NotificationCampaign.DAILY }
        runtime.updateConfig(mapOf("notifications.daily.slots" to "08:00"))
        assertTrue(platform.cancelledAlarms.any { it.campaign == NotificationCampaign.DAILY && it.slot == "1900" })
        val desired = platform.scheduled.toMap()
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(old))
        assertEquals(desired, platform.scheduled)
        runtime.updateConfig(mapOf("notifications.enabled" to "false"))
        assertTrue(platform.scheduled.isEmpty())
        assertTrue(platform.posts.isEmpty())
        RetentionRuntime.uninstallForTests(); install()
        assertTrue(platform.scheduled.isEmpty())
    }

    @Test fun alarmTtlSkipsExpiredOccurrenceAndStillRearmsNextDay() {
        install()
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.DAILY }
        clock.now = alarm.expires
        assertEquals(NotificationOutcome.Skipped("expired"), module.receiveAlarm(alarm))
        val next = platform.scheduled.getValue(alarm.key)
        assertTrue(next.due > clock.now)
        assertNotEquals(alarm.calendarDate, next.calendarDate)
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(alarm))
        assertTrue(platform.posts.isEmpty())
    }

    @Test fun schedulingFailureIsDiagnosedAndReconcileRetriesDesiredIdentity() {
        platform.failSchedule = true
        install()
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "notifications.schedule" })
        platform.failSchedule = false
        module.reconcile("permission_return")
        assertEquals(7, platform.scheduled.size)
    }

    @Test fun oldDismissCannotCancelReplacementAndOpenedIsCapturedThenDeduplicated() {
        install(overrides = mapOf("notifications.daily.cooldown_ms" to "0"))
        send(occurrence = "daily:old"); send(occurrence = "daily:new")
        module.dismiss(NotificationCampaign.DAILY, "daily:old")
        assertTrue(platform.active(NotificationCampaign.DAILY))
        val delivered = shadowOf(platform.posts.last().second.contentIntent).savedIntent
        val entry = (RetentionEntryCodec.read(delivered) as RetentionEntryDecodeResult.Valid).entry
        assertFalse(module.recordOpened(entry))
        val accepted = runtime.entries.capture(delivered) as RetentionEntryAcceptance.Accepted
        assertTrue(module.recordOpened(accepted.entry))
        assertFalse(module.recordOpened(accepted.entry))
        assertFalse(platform.active(NotificationCampaign.DAILY))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, events.count { it.name == "retention_noti_opened" })
    }

    @Test fun eventSinkFailureOrReentrantConfigDoesNotRetrySubmittedPost() {
        install(eventSink = RetentionEventSink { event ->
            if (event.name == "retention_noti_post_submitted") runtime.updateConfig(mapOf("notifications.daily.enabled" to "false"))
            error("analytics offline")
        })
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, platform.posts.size)
        assertFalse(platform.active(NotificationCampaign.DAILY))
        assertTrue(runtime.diagnostics.snapshot().any { it.component == "event_sink" })
    }

    @Test fun replacedOnboardingCallbackCannotUseNewerDelayEvenIfCancellationLosesRace() {
        install(overrides = mapOf("notifications.onboarding.daily_cap" to "2", "notifications.onboarding.cooldown_ms" to "0"),
            user = user().copy(setupCompleted = false, onboardingActive = true, setupCompletedAtMillis = 0))
        runtime.signal(RetentionSignal.ProcessBackground)
        val first = delays.work.single()
        runtime.signal(RetentionSignal.ProcessBackground)
        clock.advance(3_000)
        first.callback() // platform had already dequeued the cancelled task
        assertTrue(platform.posts.isEmpty())
        delays.fire()
        assertEquals(1, platform.posts.size)
    }

    @Test fun postReceiptDiskFailureStaysUnknownAndCannotSpendAnotherAttempt() {
        val delegate = store
        var failReceipt = true
        store = object : RetentionStore {
            override fun snapshot(namespace: String) = delegate.snapshot(namespace)
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T = delegate.transaction(namespace) { state ->
                val result = block(state)
                if (failReceipt && namespace == STATE && state.entries().values.any { it.contains("\"status\":\"submitted\"") }) {
                    throw RetentionStorageException("Injected disk failure at receipt")
                }
                result
            }
        }
        install(overrides = mapOf("notifications.daily.daily_cap" to "1"))
        assertEquals(NotificationOutcome.PostSubmitted(NotificationCampaign.DAILY.notificationId, false), send())
        assertEquals(1, platform.posts.size)
        assertNull(NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        // Later works from the actual OS occurrence even though the durable receipt is absent.
        val occurrence = platform.posts.single().second.extras.getString(OCCURRENCE_EXTRA)!!
        module.dismiss(NotificationCampaign.DAILY, occurrence)
        assertFalse(platform.active(NotificationCampaign.DAILY))
        failReceipt = false
        RetentionRuntime.uninstallForTests(); install()
        assertEquals(NotificationOutcome.Skipped("daily_cap"), send())
        assertEquals(1, platform.posts.size)
    }

    @Test fun claimCommitFailurePreventsNotifyAndLeavesNoPartialBudget() {
        val delegate = store
        var failClaim = true
        store = object : RetentionStore {
            override fun snapshot(namespace: String) = delegate.snapshot(namespace)
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T = delegate.transaction(namespace) { state ->
                val result = block(state)
                if (failClaim && namespace == STATE && state.entries().keys.any { it.startsWith("attempt:") }) {
                    throw RetentionStorageException("Injected disk failure at claim")
                }
                result
            }
        }
        install()
        assertTrue(send() is NotificationOutcome.Failed)
        assertTrue(platform.posts.isEmpty())
        assertFalse(store.snapshot(STATE).entries().keys.any { it.startsWith("attempt:") })
        failClaim = false
        assertTrue(send() is NotificationOutcome.PostSubmitted)
    }

    @Test fun entitlementChangeWithdrawsPreviouslySubmittedMarketingOnly() {
        install()
        send(NotificationCampaign.DAILY); send(NotificationCampaign.LOCKSCREEN)
        assertEquals(2, platform.activeCampaigns.size)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertTrue(platform.activeCampaigns.isEmpty())
        assertEquals(2, platform.posts.size) // no invented re-post/callback on cancellation
    }

    @Test fun contentCatalogueRemovalAndSingleItemRotationRemainValidAfterRestore() {
        install()
        send()
        RetentionRuntime.uninstallForTests(); clock.advance(2 * HOUR)
        install(options = RetentionNotificationOptions(preset = NotificationPreset.LEGACY_SDK, contentProvider = NotificationContentProvider { _, _, _ ->
            listOf(NotificationContent("new_content", "Saved items", "Find your work", "saved_items"))
        }))
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        assertEquals("new_content", NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
        clock.advance(DAY)
        assertTrue(send() is NotificationOutcome.PostSubmitted)
        assertEquals("new_content", NotificationDeliveryState(store).lastContent(NotificationCampaign.DAILY))
    }

    @Test fun legacyMigrationIsExplicitAndDoesNotInferMissingFlags() {
        val migration = NotificationLegacyConfig.translate(mapOf("notiDailyEnabled" to "false", "noti_daily_slots" to "09:30", "notiForPremiumUsers" to "true"))
        assertEquals(mapOf("notifications.daily.enabled" to "false", "notifications.daily.slots" to "09:30"), migration.overrides)
        assertEquals(setOf("notiForPremiumUsers"), migration.unsupportedKeys)
        install()
        assertTrue(runtime.updateConfig(migration.overrides) is RetentionConfigResult.Applied)
    }
}

internal class TestClock : RetentionClock {
    var now = 1_788_739_200_000L // fixed date, test outcomes do not depend on host timezone
    var elapsed = 1_000L
    var zone: TimeZone = TimeZone.getTimeZone("UTC")
    override fun wallTimeMillis() = now
    override fun elapsedRealtimeMillis() = elapsed
    override fun timeZone() = zone
    fun advance(millis: Long) { now += millis; elapsed += millis }
}
internal class FakePlatform : NotificationPlatform {
    var block: String? = null
    var failPost = false
    var failSchedule = false
    val posts = mutableListOf<Pair<NotificationCampaign, Notification>>()
    val activeCampaigns = mutableSetOf<NotificationCampaign>()
    val scheduled = mutableMapOf<String, ScheduledNotification>()
    val triggerTimes = mutableMapOf<String, Long>()
    val cancelledAlarms = mutableListOf<ScheduledNotification>()
    override fun createChannels(options: RetentionNotificationOptions) {}
    override fun blocked(channel: String) = block
    override fun activeOccurrence(campaign: NotificationCampaign): String? = if (campaign in activeCampaigns) posts.lastOrNull { it.first == campaign }?.second?.extras?.getString(OCCURRENCE_EXTRA) else null
    override fun active(campaign: NotificationCampaign) = campaign in activeCampaigns
    override fun post(campaign: NotificationCampaign, notification: Notification) { if (failPost) error("notify failure"); posts += campaign to notification; activeCampaigns += campaign }
    override fun cancel(campaign: NotificationCampaign) { activeCampaigns -= campaign }
    override fun schedule(alarm: ScheduledNotification, triggerAtMillis: Long) { if (failSchedule) error("alarm failure"); scheduled[alarm.key] = alarm; triggerTimes[alarm.key] = triggerAtMillis }
    override fun cancelAlarm(alarm: ScheduledNotification) { cancelledAlarms += alarm; scheduled.remove(alarm.key) }
}
internal class FakeDelays : NotificationDelays {
    data class Work(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    val work = mutableListOf<Work>()
    override fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable {
        val task = Work(delayMillis, callback); work += task
        return AutoCloseable { task.cancelled = true }
    }
    fun fire(evenCancelled: Boolean = false) {
        val snapshot = work.toList(); work.clear()
        snapshot.filter { evenCancelled || !it.cancelled }.forEach { it.callback() }
    }
}
