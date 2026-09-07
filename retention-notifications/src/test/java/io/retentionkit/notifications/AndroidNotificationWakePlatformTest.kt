package io.retentionkit.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationWakePlatformTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun normalWakePermissionAndSupportedLevelAreCheckedWithoutHiddenTurnScreenOnGrant() {
        val platform = AndroidNotificationWakePlatform(app)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        shadowOf(app).denyPermissions(Manifest.permission.WAKE_LOCK, Manifest.permission.TURN_SCREEN_ON)
        assertEquals("wake_permission_denied", platform.blocked())
        shadowOf(app).grantPermissions(Manifest.permission.WAKE_LOCK)
        power.setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, false)
        assertEquals("wake_level_unsupported", platform.blocked())
        power.setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, true)
        assertNull(platform.blocked())
    }

    @Test @Config(sdk = [24]) fun wakeCheckpointIsInexactCpuWakeWithIdentitySeparateFromCalendar() {
        val wake = AndroidNotificationWakePlatform(app)
        val calendar = AndroidNotificationPlatform(app)
        val slot = ScheduledNotification(NotificationCampaign.LOCKSCREEN, "1130", "20260907", 1_900_000_000_000, 1_900_003_600_000, 1)
        calendar.schedule(slot)
        wake.schedule(slot.occurrence, slot.due + 30_000)
        wake.schedule(slot.occurrence, slot.due + 60_000)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertEquals(2, alarms.scheduledAlarms.size)
        val checkpoint = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == WAKE_ACTION }
        assertEquals(AlarmManager.RTC_WAKEUP, checkpoint.type)
        assertNotEquals("A zero window would request an exact alarm", 0L, checkpoint.windowLengthMs)
        val intent = shadowOf(checkpoint.operation).savedIntent
        assertEquals(slot.occurrence, intent.getStringExtra(OCCURRENCE_EXTRA))
        assertEquals(slot.due + 60_000, intent.getLongExtra("checkpoint_at", 0))
        wake.cancel(slot.occurrence)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(ALARM_ACTION, shadowOf(alarms.scheduledAlarms.single().operation).savedIntent.action)
    }

    @Test fun screenObserverUsesSystemStateAndUnregistersOnClose() {
        val platform = AndroidNotificationWakePlatform(app)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val states = mutableListOf<Boolean>()
        val observation = platform.observe { states += it }
        power.setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), states)
        observation.close(); observation.close()
        power.setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), states)
    }
}
