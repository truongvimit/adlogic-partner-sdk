package io.retentionkit.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationPlatformTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun permissionThenChannelGateHonorsExistingChoicesAndDoesNotRequestPermission() {
        val platform = AndroidNotificationPlatform(app)
        val options = RetentionNotificationOptions()
        platform.createChannels(options)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("permission_denied", platform.blocked(options.channel(NotificationCampaign.DAILY)))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(options.channel(NotificationCampaign.REMINDER)).importance)
        assertNull(manager.getNotificationChannel(options.channel(NotificationCampaign.REMINDER)).sound)
        assertNull(platform.blocked(options.channel(NotificationCampaign.DAILY)))
        // Custom ID represents an old user-disabled channel: creating channels must not override it.
        manager.createNotificationChannel(NotificationChannel("old_blocked", "Old", NotificationManager.IMPORTANCE_NONE))
        val migration = options.copy(channelIds = mapOf(NotificationCampaign.DAILY to "old_blocked"))
        platform.createChannels(migration)
        assertEquals("channel_blocked", platform.blocked("old_blocked"))
        assertEquals("channel_missing", platform.blocked("not_created"))
    }

    @Test fun commonChannelGroupsUseCanonicalImportanceAndPreserveLegacyOptInIds() {
        val platform = AndroidNotificationPlatform(app)
        val common = RetentionNotificationOptions()
        platform.createChannels(common)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals("lock_screen_alerts", common.channel(NotificationCampaign.LOCKSCREEN))
        assertEquals("updates_news", common.channel(NotificationCampaign.WINBACK))
        assertEquals(common.channel(NotificationCampaign.WINBACK), common.channel(NotificationCampaign.APP_EXIT))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("updates_news").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("lock_screen_alerts").importance)
        assertEquals(Notification.VISIBILITY_PUBLIC, manager.getNotificationChannel("lock_screen_alerts").lockscreenVisibility)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("daily_tips").importance)
        assertNull(manager.getNotificationChannel("daily_tips").sound)
        val legacy = common.copy(preset = NotificationPreset.LEGACY_SDK)
        platform.createChannels(legacy)
        assertEquals("rk_retention_winback", legacy.channel(NotificationCampaign.WINBACK))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("rk_retention_winback").importance)
    }

    @Test fun inexactAlarmIdentityReplacesRevisionAndCancellationRemovesOnlyOwnedSlot() {
        val platform = AndroidNotificationPlatform(app)
        val old = ScheduledNotification(NotificationCampaign.DAILY, "0800", "20260907", 1_900_000_000_000, 1_900_003_600_000, 1)
        val next = old.copy(revision = 2)
        platform.schedule(old); platform.schedule(next)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        val scheduled = alarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)
        assertEquals(10 * MINUTE, scheduled.windowLengthMs)
        assertTrue(shadowOf(scheduled.operation).isBroadcast)
        assertEquals(next.encode(), shadowOf(scheduled.operation).savedIntent.getStringExtra(ALARM_EXTRA))
        platform.cancelAlarm(next)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun manifestContainsOnlyMarketingPermissionsAndInternalReceivers() {
        val pkg = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        val permissions = pkg.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.WAKE_LOCK in permissions)
        assertFalse(permissions.any { it.contains("EXACT_ALARM") || it.contains("FULL_SCREEN") || it.contains("FOREGROUND_SERVICE") || it == Manifest.permission.TURN_SCREEN_ON })
        val owned = pkg.receivers.orEmpty().filter { it.name.startsWith("io.retentionkit.notifications.") }
        assertEquals(4, owned.size)
        assertTrue(owned.all { !it.exported })
    }

    @Test @Config(sdk = [24]) fun minSdkUsesInexactCalendarAndNormalNotificationWithoutChannels() {
        val platform = AndroidNotificationPlatform(app)
        platform.createChannels(RetentionNotificationOptions())
        assertNull(platform.blocked("unused_before_o"))
        val alarm = CalendarSlots.next(NotificationCampaign.DAILY, LocalSlot(8, 0), 1_900_000_000_000, java.util.TimeZone.getTimeZone("UTC"), HOUR, 1, null)
        platform.schedule(alarm)
        assertEquals(1, shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        val notification = Notification.Builder(app).setSmallIcon(R.drawable.rk_ic_notification).setContentTitle("Tool").build()
        platform.post(NotificationCampaign.DAILY, notification)
        assertTrue(platform.active(NotificationCampaign.DAILY))
        platform.cancel(NotificationCampaign.DAILY)
        assertFalse(platform.active(NotificationCampaign.DAILY))
    }
}
