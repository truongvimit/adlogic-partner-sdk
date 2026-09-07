package io.retentionkit.notifications

import android.app.Application
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
import org.robolectric.annotation.Config
import java.util.UUID

/** Real runtime signals and saved OS alarm envelopes; only clock/Android transport are fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNotificationDeliveryTest {
    private lateinit var app: Application
    private lateinit var clock: TestClock
    private lateinit var store: RetentionStore
    private lateinit var runtime: RetentionRuntime
    private lateinit var module: RetentionNotifications
    private lateinit var platform: FakePlatform
    private lateinit var delays: FakeDelays

    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext()
        clock = TestClock()
        store = SharedPreferencesRetentionStore(app, "default_delivery_${UUID.randomUUID()}")
        platform = FakePlatform()
        delays = FakeDelays()
    }
    @After fun after() { RetentionRuntime.uninstallForTests() }

    private fun user() = RetentionUserState(true, false, RetentionEntitlement.NON_SUBSCRIBER,
        clock.now, clock.now, clock.now)

    private fun install(user: RetentionUserState = user()) {
        module = RetentionNotifications(RetentionNotificationOptions(), platform, delays)
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module),
            clock = clock, store = store, initialUserState = user,
            featureProvider = RetentionFeatureProvider { listOf(
                RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification, "Save a note."),
                RetentionFeature("saved_items", "Saved items", R.drawable.rk_ic_notification, "Find saved work.")) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity")) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
        assertEquals("These are production defaults, without synthetic remote overrides", 0L, runtime.config.revision)
        assertTrue(runtime.config.values.isEmpty())
    }

    @Test fun firstCompletedSetupOpenPostsQuietReminderWithoutAnExtraDayOfWaiting() {
        install()
        runtime.signal(RetentionSignal.ProcessForeground)
        assertTrue("The canonical first completed open must submit Reminder", platform.active(NotificationCampaign.REMINDER))
        val reminder = platform.posts.single { it.first == NotificationCampaign.REMINDER }.second
        assertNull(reminder.sound)
        assertEquals(0, reminder.defaults)
        assertFalse(platform.active(NotificationCampaign.DAILY))
    }

    @Test fun firstOpenWaitsForSetupBillingPermissionAndHostThenPublishesWithoutAnotherOpen() {
        install(user().copy(setupCompleted = false, onboardingActive = true,
            entitlement = RetentionEntitlement.UNKNOWN, setupCompletedAtMillis = 0))
        platform.block = "permission_denied"
        runtime.signal(RetentionSignal.ProcessForeground)
        runtime.signal(RetentionSignal.HostUiChanged("consent", true))
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertTrue(platform.posts.isEmpty())
        runtime.signal(RetentionSignal.HostUiChanged("consent", false))
        assertTrue("Permission denial still wins", platform.posts.isEmpty())
        platform.block = null
        runtime.reconcile("notification_permission_result")
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.REMINDER })
        runtime.reconcile("repeated_permission_result")
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals("The same open is not replayed by ready callbacks", 1,
            platform.posts.count { it.first == NotificationCampaign.REMINDER })
    }

    @Test fun reminderHasItsDocumentedFifteenMinuteCooldownWithoutAnInventedFourPerDayLimit() {
        install()
        repeat(5) {
            runtime.signal(RetentionSignal.ProcessForeground)
            assertEquals(it + 1, platform.posts.count { post -> post.first == NotificationCampaign.REMINDER })
            runtime.signal(RetentionSignal.ProcessBackground)
            runtime.signal(RetentionSignal.ProcessForeground)
            assertEquals("Immediate reopen must still respect cooldown", it + 1,
                platform.posts.count { post -> post.first == NotificationCampaign.REMINDER })
            runtime.signal(RetentionSignal.ProcessBackground)
            clock.advance(15 * MINUTE)
        }
    }

    @Test fun coldDueCalendarWaitsForVerifiedEntitlementWithoutSpendingItsSlotAcrossRestart() {
        install(user().copy(entitlement = RetentionEntitlement.UNKNOWN))
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.DAILY }
        clock.advance(alarm.due - clock.now + 1)
        assertEquals(NotificationOutcome.Skipped("entitlement_unknown"), module.receiveAlarm(alarm))
        assertTrue(platform.posts.isEmpty())
        RetentionRuntime.uninstallForTests()
        install(user().copy(entitlement = RetentionEntitlement.UNKNOWN))
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals("Verified free should recover the still-valid due slot", 1,
            platform.posts.count { it.first == NotificationCampaign.DAILY })
        assertEquals(alarm.occurrence, platform.activeOccurrence(NotificationCampaign.DAILY))
        assertEquals(NotificationOutcome.Skipped("stale_alarm"), module.receiveAlarm(alarm))
        assertEquals(1, platform.posts.size)
    }

    @Test fun unknownColdProcessGetsABoundedDurableRetryBeforeItsOriginalExpiry() {
        install(user().copy(entitlement = RetentionEntitlement.UNKNOWN))
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.DAILY }
        clock.advance(alarm.due - clock.now + 1)
        module.receiveAlarm(alarm)
        val retryAt = platform.triggerTimes.getValue(alarm.key)
        assertTrue("A killed process needs a real wake checkpoint before delivery expires",
            retryAt > clock.now && retryAt < alarm.expires)
        assertEquals("Retry cannot change route identity or widen the delivery TTL", alarm,
            platform.scheduled.getValue(alarm.key))
        RetentionRuntime.uninstallForTests()
        clock.advance(retryAt - clock.now)
        install(user().copy(entitlement = RetentionEntitlement.UNKNOWN))
        assertEquals(NotificationOutcome.Skipped("entitlement_unknown"), module.receiveAlarm(alarm))
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.DAILY })
        assertEquals(alarm.occurrence, platform.activeOccurrence(NotificationCampaign.DAILY))
    }

    @Test fun pinnedAutomaticallyFollowsGuardAndRestoresAfterOsRemovalWithoutMarketingCaps() {
        install()
        repeat(3) { open ->
            runtime.signal(RetentionSignal.ProcessForeground)
            clock.advance(31_000)
            delays.fire()
            assertEquals("A missing permanent surface restores on each eligible open", open + 1,
                platform.posts.count { it.first == NotificationCampaign.PINNED })
            runtime.reconcile("already_visible")
            assertEquals(open + 1, platform.posts.count { it.first == NotificationCampaign.PINNED })
            platform.cancel(NotificationCampaign.PINNED) // OS removed the surface, outside the SDK.
            runtime.signal(RetentionSignal.ProcessBackground)
        }
    }

    @Test fun qualifyingAdDeparturesAreNotSilentlyCappedAtTwoPerDay() = assertThreeDepartures(adClicked = true)
    @Test fun qualifyingOrdinaryDeparturesAreNotSilentlyCappedAtTwoPerDay() = assertThreeDepartures(adClicked = false)

    private fun assertThreeDepartures(adClicked: Boolean) {
        install()
        val campaign = if (adClicked) NotificationCampaign.AD_RETURN else NotificationCampaign.APP_EXIT
        repeat(3) { departure ->
            runtime.signal(RetentionSignal.ProcessForeground)
            clock.advance(31_000) // Preserve the SDK anti-overlap guard after the quiet Reminder.
            if (adClicked) runtime.signal(RetentionSignal.AdClicked("click_$departure"))
            runtime.signal(RetentionSignal.ProcessBackground)
            clock.advance(3_000)
            delays.fire()
            assertEquals(departure + 1, platform.posts.count { it.first == campaign })
            platform.cancel(campaign)
            clock.advance(15 * MINUTE)
        }
    }
}
