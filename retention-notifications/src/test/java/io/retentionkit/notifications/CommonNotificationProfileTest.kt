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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommonNotificationProfileTest {
    private lateinit var app: Application
    private lateinit var clock: TestClock
    private lateinit var store: RetentionStore
    private lateinit var runtime: RetentionRuntime
    private lateinit var module: RetentionNotifications
    private lateinit var platform: FakePlatform
    private lateinit var delays: FakeDelays
    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext(); clock = TestClock()
        store = SharedPreferencesRetentionStore(app, "common_${UUID.randomUUID()}")
        platform = FakePlatform(); delays = FakeDelays()
    }
    @After fun after() { RetentionRuntime.uninstallForTests() }
    private fun user() = RetentionUserState(true, false, RetentionEntitlement.NON_SUBSCRIBER,
        clock.now - 30 * DAY, clock.now - 20 * DAY, clock.now - 25 * DAY)
    private fun install(overrides: Map<String, String> = emptyMap(), user: RetentionUserState = user(),
                        options: RetentionNotificationOptions = RetentionNotificationOptions(), emptySchedule: Boolean = true) {
        module = RetentionNotifications(options, platform, delays)
        val base = mapOf("notifications.daily.slots" to "", "notifications.winback.slots" to "", "notifications.lockscreen.slots" to "",
            "notifications.lockscreen.new_user_slots" to "", "notifications.reminder.enabled" to "false", "notifications.pinned.enabled" to "false")
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), clock = clock, store = store,
            initialOverrides = (if (emptySchedule) base else emptyMap()) + overrides, initialUserState = user,
            featureProvider = RetentionFeatureProvider { listOf(
                RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification, "Save a short note."),
                RetentionFeature("saved_items", "Saved items", R.drawable.rk_ic_notification, "Find saved work."),
                RetentionFeature("text_tools", "Text tools", R.drawable.rk_ic_notification, "Format your text.")) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity")) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
    }
    private fun send(campaign: NotificationCampaign, occurrence: String = "${campaign.key}:${UUID.randomUUID()}") =
        module.deliver(campaign, occurrence, runtime.config.revision, clock.now, clock.now + HOUR)

    @Test fun namedProfilesKeepDocumentAndLegacyCadencesSeparateAndStrict() {
        install(emptySchedule = false)
        val common = NotificationProfile.read(runtime.config)
        val legacy = NotificationProfile.read(runtime.config, NotificationPreset.LEGACY_SDK)
        assertEquals(14 * DAY, common.winbackInactivity); assertEquals(45 * DAY, common.winbackMaxInactivity)
        assertEquals(3, common[NotificationCampaign.WINBACK].lifetimeCap); assertEquals(2, common[NotificationCampaign.WINBACK].cap)
        assertEquals(DAY, common.onboardingGrace); assertEquals(3_000L, common.backgroundDelay)
        assertEquals(2L, common.newUserDays); assertEquals(15 * MINUTE, common[NotificationCampaign.REMINDER].cooldown)
        assertTrue(common[NotificationCampaign.APP_EXIT].enabled); assertTrue(common.persistentLockscreen)
        assertEquals(listOf(LocalSlot(8, 0), LocalSlot(19, 0)), common[NotificationCampaign.DAILY].slots)
        assertEquals(listOf(LocalSlot(11, 0), LocalSlot(14, 0)), common[NotificationCampaign.WINBACK].slots)
        assertEquals(listOf(LocalSlot(11, 30), LocalSlot(17, 0), LocalSlot(20, 0)), common[NotificationCampaign.LOCKSCREEN].slots)
        assertEquals(2 * DAY, legacy.winbackInactivity); assertEquals(0L, legacy.onboardingGrace)
        assertFalse(legacy[NotificationCampaign.APP_EXIT].enabled); assertFalse(legacy.arbitration)
        assertTrue(runtime.updateConfig(mapOf("notifications.winback.max_inactivity_ms" to "1")) is RetentionConfigResult.Rejected)
        assertTrue(runtime.updateConfig(mapOf("notifications.winback.lifetime_cap" to "-1")) is RetentionConfigResult.Rejected)
    }

    @Test fun lifetimeCapSurvivesRestartAndLedgerPruningAndDoesNotResetOnNewDay() {
        install()
        repeat(3) {
            assertTrue(send(NotificationCampaign.WINBACK) is NotificationOutcome.PostSubmitted)
            platform.cancel(NotificationCampaign.WINBACK)
            RetentionRuntime.uninstallForTests(); clock.advance(9 * DAY); install()
        }
        // The inactivity maximum is independent: extend it to inspect the already spent lifetime cap.
        runtime.updateConfig(mapOf("notifications.winback.max_inactivity_ms" to "0"))
        assertEquals(NotificationOutcome.Skipped("frequency_cap"), send(NotificationCampaign.WINBACK))
        assertEquals(3L, store.snapshot(STATE).long("total:winback"))
        assertEquals(3, platform.posts.size)
    }

    @Test fun inactivityBoundariesAndFailedPostDoNotSpendLifetimeCount() {
        install(user = user().copy(lastActiveAtMillis = clock.now - 13 * DAY))
        assertEquals(NotificationOutcome.Skipped("not_inactive"), send(NotificationCampaign.WINBACK))
        clock.advance(DAY)
        platform.failPost = true
        assertEquals(NotificationOutcome.Failed("post"), send(NotificationCampaign.WINBACK))
        assertEquals(0L, store.snapshot(STATE).long("total:winback"))
        platform.failPost = false
        assertTrue(send(NotificationCampaign.WINBACK) is NotificationOutcome.PostSubmitted)
        platform.cancel(NotificationCampaign.WINBACK); clock.advance(31 * DAY + 1)
        assertEquals(NotificationOutcome.Skipped("outside_inactivity_window"), send(NotificationCampaign.WINBACK))
    }

    @Test fun uncertainClaimStillReservesLifetimeEvenAfterLedgerPruning() {
        install(overrides = mapOf("notifications.winback.lifetime_cap" to "1", "notifications.winback.max_inactivity_ms" to "0"))
        val profile = NotificationProfile.read(runtime.config)
        assertNull(NotificationDeliveryState(store).claim(NotificationCampaign.WINBACK, "uncertain", clock.now,
            CalendarSlots.date(clock.now, clock.zone), profile[NotificationCampaign.WINBACK], "notes", profile.guardWindow))
        RetentionRuntime.uninstallForTests(); clock.advance(10 * DAY); install()
        assertEquals(NotificationOutcome.Skipped("frequency_cap"), send(NotificationCampaign.WINBACK))
        assertNull(NotificationDeliveryState(store).lastContent(NotificationCampaign.WINBACK))
    }

    @Test fun guardSurvivesRestartAndBackwardClockAndSerializesConcurrentDifferentFamilies() {
        install()
        assertTrue(send(NotificationCampaign.LOCKSCREEN) is NotificationOutcome.PostSubmitted)
        RetentionRuntime.uninstallForTests(); install()
        assertEquals(NotificationOutcome.Skipped("guard_window"), send(NotificationCampaign.DAILY))
        clock.advance(-1_000)
        assertEquals(NotificationOutcome.Skipped("guard_window"), send(NotificationCampaign.DAILY))
        clock.advance(31_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(NotificationCampaign.DAILY, NotificationCampaign.WINBACK).map { c -> pool.submit<NotificationOutcome> { send(c) } }
            val outcomes = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it is NotificationOutcome.PostSubmitted })
            assertEquals(1, outcomes.count { it == NotificationOutcome.Skipped("guard_window") })
        } finally { pool.shutdownNow() }
    }

    @Test fun eligibleHigherPriorityDueCalendarWinsRegardlessOfReceiverOrder() {
        install(overrides = mapOf("notifications.lockscreen.slots" to "11:30"))
        val alarm = platform.scheduled.values.single()
        clock.now = alarm.due
        assertEquals(NotificationOutcome.Skipped("higher_priority_pending"), send(NotificationCampaign.DAILY))
        assertTrue(module.receiveAlarm(alarm) is NotificationOutcome.PostSubmitted)
        assertEquals(NotificationOutcome.Skipped("guard_window"), send(NotificationCampaign.DAILY))
        clock.advance(30_000)
        assertTrue(send(NotificationCampaign.DAILY) is NotificationOutcome.PostSubmitted)
    }

    @Test fun visibleUpdatesBlockOtherUpdatesAndDismissAllowsNextWithoutSpendingAttempt() {
        install()
        assertTrue(send(NotificationCampaign.WINBACK) is NotificationOutcome.PostSubmitted)
        clock.advance(HOUR)
        assertEquals(NotificationOutcome.Skipped("group_visible"), send(NotificationCampaign.APP_EXIT))
        assertEquals(1, store.snapshot(STATE).entries().keys.count { it.startsWith("attempt:") })
        platform.cancel(NotificationCampaign.WINBACK)
        assertTrue(send(NotificationCampaign.APP_EXIT) is NotificationOutcome.PostSubmitted)
    }

    @Test fun durableExitRestoresAfterProcessDeathAndOldCallbackCannotDuplicateNewOne() {
        install(); runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        val exit = platform.scheduled.values.single { it.campaign == NotificationCampaign.APP_EXIT }
        assertEquals(clock.now + 3_000L, exit.due)
        assertEquals(exit, ScheduledNotification.decode(exit.encode()))
        RetentionRuntime.uninstallForTests(); install(); clock.advance(3_000)
        assertEquals(exit.encode(), store.snapshot(STATE).string("schedule:${exit.key}"))
        assertTrue(module.receiveAlarm(exit) is NotificationOutcome.PostSubmitted)
        delays.fire(evenCancelled = true)
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(exit))
        assertEquals(1, platform.posts.size)
        assertFalse(platform.scheduled.containsKey(exit.key))
    }

    @Test fun returnConfigChangePermissionAndSubscriberRecheckCannotResurrectExit() {
        install(); runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        val old = platform.scheduled.values.single()
        runtime.signal(RetentionSignal.ProcessForeground); clock.advance(3_000); delays.fire(evenCancelled = true)
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(old))
        runtime.signal(RetentionSignal.ProcessBackground)
        val blocked = platform.scheduled.values.single()
        platform.block = "permission_denied"; clock.advance(3_000)
        assertEquals(NotificationOutcome.Skipped("permission_denied"), module.receiveAlarm(blocked))
        assertEquals(0L, store.snapshot(STATE).long("total:app_exit"))
        platform.block = null
        runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        val changed = platform.scheduled.values.single()
        runtime.updateConfig(mapOf("notifications.app_exit.enabled" to "false"))
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(changed))
        runtime.updateConfig(mapOf("notifications.app_exit.enabled" to "true"))
        runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        val premium = platform.scheduled.values.single()
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(premium))
        assertTrue(platform.posts.isEmpty())
    }

    @Test fun adExitWinsOverNoClickExitAndSystemTransitionCancelsDurableHandoff() {
        install(); runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.AdClicked("real_click"))
        runtime.signal(RetentionSignal.ProcessBackground)
        assertEquals(NotificationCampaign.AD_RETURN, platform.scheduled.values.single().campaign)
        clock.advance(3_000); delays.fire()
        assertEquals(NotificationCampaign.AD_RETURN, platform.posts.single().first)
        assertTrue(platform.posts.none { it.first == NotificationCampaign.APP_EXIT })
        runtime.signal(RetentionSignal.ProcessBackground); clock.advance(3_000); delays.fire()
        assertEquals(1, platform.posts.size) // repeated background signals belong to the same departure.
        platform.cancel(NotificationCampaign.AD_RETURN); clock.advance(HOUR)
        runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        val exit = platform.scheduled.values.single()
        runtime.signal(RetentionSignal.ExternalTransitionStarted("system", "settings"))
        clock.advance(3_000); delays.fire(evenCancelled = true)
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(exit))
        assertEquals(1, platform.posts.size)
    }

    @Test fun commonOnboardingSuppressesFirstInstallDayButAllowsAbandonmentAfterIt() {
        install(user = user().copy(setupCompleted = false, onboardingActive = true, installedAtMillis = clock.now, setupCompletedAtMillis = 0))
        runtime.signal(RetentionSignal.ProcessBackground); clock.advance(3_000); delays.fire()
        assertTrue(platform.posts.isEmpty()); assertTrue(platform.scheduled.isEmpty())
        clock.advance(DAY)
        runtime.signal(RetentionSignal.ProcessForeground); runtime.signal(RetentionSignal.ProcessBackground)
        clock.advance(3_000); delays.fire()
        assertEquals(NotificationCampaign.ONBOARDING, platform.posts.single().first)
    }

    @Test fun lockscreenHasNoDisplayedTimeoutSurvivesDaysAndDismissesWhenAppOpens() {
        install()
        assertTrue(send(NotificationCampaign.LOCKSCREEN) is NotificationOutcome.PostSubmitted)
        val notification = platform.posts.single().second
        assertEquals(0L, notification.timeoutAfter)
        val intent = shadowOf(notification.contentIntent).savedIntent
        val envelope = (RetentionEntryCodec.read(intent) as RetentionEntryDecodeResult.Valid).entry
        assertNull(envelope.expiresAtMillis); assertEquals(RetentionEntryMode.REUSABLE, envelope.mode)
        clock.advance(9 * DAY); RetentionRuntime.uninstallForTests(); install()
        assertEquals(NotificationOutcome.Skipped("still_active"), send(NotificationCampaign.LOCKSCREEN))
        assertTrue(runtime.entries.capture(Intent(intent)) is RetentionEntryAcceptance.Accepted)
        runtime.signal(RetentionSignal.ProcessForeground)
        assertFalse(platform.active(NotificationCampaign.LOCKSCREEN))
    }

    @Test fun lockscreenReplacementRandomlyExcludesPreviousAcrossProcessRestore() {
        install(overrides = mapOf("notifications.lockscreen.replace" to "true", "notifications.lockscreen.daily_cap" to "50", "notifications.lockscreen.cooldown_ms" to "0", "notifications.guard_window_ms" to "0"))
        repeat(12) {
            val previous = NotificationDeliveryState(store).lastContent(NotificationCampaign.LOCKSCREEN)
            assertTrue(send(NotificationCampaign.LOCKSCREEN) is NotificationOutcome.PostSubmitted)
            assertNotEquals(previous, NotificationDeliveryState(store).lastContent(NotificationCampaign.LOCKSCREEN))
            RetentionRuntime.uninstallForTests(); install()
        }
        assertEquals(1, platform.activeCampaigns.size)
    }

    @Test fun defaultContentHasCampaignCopyRichExitAndAllowlistedDistinctSplashActions() {
        install()
        val content = NotificationCampaign.entries.associateWith { StandardNotificationContent.content(app, it, runtime.features()).first() }
        assertEquals(NotificationCampaign.entries.size - 1, content.values.map { it.title }.toSet().size) // AD_RETURN and APP_EXIT share the intended framing.
        assertTrue(content.values.all { it.body.contains("Save a short note.") })
        val exit = content.getValue(NotificationCampaign.APP_EXIT)
        assertNotEquals(exit.title, exit.expandedTitle); assertEquals("Action now", exit.actions.single().label)
        assertNotNull(exit.imageRes)
        assertTrue(send(NotificationCampaign.APP_EXIT) is NotificationOutcome.PostSubmitted)
        val notification = platform.posts.single().second
        assertEquals("android.app.Notification\$BigPictureStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(exit.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(exit.expandedTitle, notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG).toString())
        val main = shadowOf(notification.contentIntent).savedIntent
        val action = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals("HostSplashActivity", main.component?.className)
        assertFalse(main.filterEquals(action)); assertNull(notification.fullScreenIntent)
    }

    @Test fun emptyHigherPriorityContentCannotStarveValidLowerFamily() {
        install(overrides = mapOf("notifications.lockscreen.slots" to "11:30"), options = RetentionNotificationOptions(
            contentProvider = NotificationContentProvider { context, campaign, features ->
                if (campaign == NotificationCampaign.LOCKSCREEN) emptyList() else StandardNotificationContent.content(context, campaign, features)
            }))
        val alarm = platform.scheduled.values.single()
        clock.now = alarm.due
        assertTrue(send(NotificationCampaign.DAILY) is NotificationOutcome.PostSubmitted)
        assertEquals(NotificationOutcome.Skipped("empty_content"), module.receiveAlarm(alarm))
        assertEquals(listOf(NotificationCampaign.DAILY), platform.posts.map { it.first })
    }

    @Test fun exactNewUserSlotMapSwitchesAfterCohortAndRestoresAfterTimezoneChange() {
        install(overrides = NotificationLegacyConfig.map(mapOf("noti_lockscreen_slots" to "11:30", "noti_lockscreen_slots_new" to "12:30")).overrides,
            user = user().copy(installedAtMillis = clock.now - DAY))
        assertEquals("1230", platform.scheduled.values.single().slot)
        clock.advance(DAY)
        module.reconcile("cohort_elapsed")
        assertEquals("1130", platform.scheduled.values.single().slot)
        val originalDue = platform.scheduled.values.single().due
        clock.zone = java.util.TimeZone.getTimeZone("Asia/Tokyo"); module.reconcile("timezone_changed")
        assertNotEquals(originalDue, platform.scheduled.values.single().due)
        clock.zone = java.util.TimeZone.getTimeZone("UTC"); RetentionRuntime.uninstallForTests(); install()
        assertEquals(originalDue, platform.scheduled.values.single().due)
        assertEquals("1130", platform.scheduled.values.single().slot)
    }

    @Test fun localizedGenericContentRetainsCampaignFramingAndFeatureData() {
        install()
        val configuration = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("vi")) }
        val localized = app.createConfigurationContext(configuration)
        val content = StandardNotificationContent.content(localized, NotificationCampaign.APP_EXIT, runtime.features()).first()
        assertEquals("Trước khi bạn rời đi", content.title)
        assertEquals("Mở ngay", content.actions.single().label)
        assertEquals("Tiếp tục với Notes", content.expandedTitle)
        assertTrue(content.body.contains("Save a short note."))
    }

    @Test fun firstForegroundRefreshCreatesOneSurfaceThenPinnedCanAppearAfterGuard() {
        install(overrides = mapOf("notifications.reminder.enabled" to "true", "notifications.pinned.enabled" to "true"))
        runtime.signal(RetentionSignal.ProcessForeground)
        assertEquals(listOf(NotificationCampaign.REMINDER), platform.posts.map { it.first })
        clock.advance(30_000)
        val outcomes = module.refreshForegroundNotifications()
        assertEquals(NotificationOutcome.Skipped("cooldown"), outcomes[NotificationCampaign.REMINDER])
        assertTrue(outcomes[NotificationCampaign.PINNED] is NotificationOutcome.PostSubmitted)
        assertEquals(2, platform.posts.size)
    }

    @Test fun lifetimeMigrationImportsExistingUnknownAndSubmittedClaimsBeforePruning() {
        install(overrides = mapOf("notifications.winback.lifetime_cap" to "2"))
        store.transaction(STATE) { state ->
            state.remove("total:winback")
            listOf("submitted", "claimed").forEachIndexed { index, status ->
                state.put("attempt:legacy$index", org.json.JSONObject().put("campaign", "winback").put("at", clock.now - 9 * DAY)
                    .put("day", "20260801").put("content", "notes").put("status", status).toString())
            }
        }
        assertEquals(NotificationOutcome.Skipped("frequency_cap"), send(NotificationCampaign.WINBACK))
        assertEquals(2L, store.snapshot(STATE).long("total:winback"))
        assertTrue(platform.posts.isEmpty())
    }

    @Test fun exactLegacyMapIsGenericAndMissingKeysNeverOverwriteConfiguration() {
        val values = mapOf("noti_lockscreen_slots" to "12:00", "noti_lockscreen_slots_new" to "13:00", "notiLockscreenReplace" to "true")
        assertTrue(NotificationLegacyConfig.keys.containsAll(values.keys))
        assertEquals(NotificationLegacyConfig.translate(values), NotificationLegacyConfig.map(values))
        install(overrides = NotificationLegacyConfig.map(values).overrides)
        val profile = NotificationProfile.read(runtime.config)
        assertEquals(listOf(LocalSlot(12, 0)), profile[NotificationCampaign.LOCKSCREEN].slots)
        assertEquals(listOf(LocalSlot(13, 0)), profile[NotificationCampaign.LOCKSCREEN].newUserSlots)
        assertTrue(profile.replaceLockscreen)
        assertEquals(14 * DAY, profile.winbackInactivity)
        assertEquals(setOf("noti_lockscreen_wake_seconds", "notiForPremiumUsers"), NotificationLegacyConfig.map(mapOf("noti_lockscreen_wake_seconds" to "20", "notiForPremiumUsers" to "true")).unsupportedKeys)
    }
}
