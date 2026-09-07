package com.itg.template.retention

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.RetentionKit
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.core.RetentionSignal
import io.retentionkit.notifications.NotificationCampaign
import io.retentionkit.notifications.RetentionNotificationOptions
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Instant
import java.util.TimeZone

/** Actual debug fixture, saved schedule and Android receiver; no replacement delivery engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ExampleQaCalendarDeliveryTest {
    private lateinit var application: Application
    private lateinit var originalZone: TimeZone
    private lateinit var kit: RetentionKit
    private val fixtureNow = Instant.parse("2026-09-07T08:56:00Z").toEpochMilli()
    private val manager get() = application.getSystemService(NotificationManager::class.java)

    @Before fun before() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun after() {
        RetentionRuntime.uninstallForTests()
        TimeZone.setDefault(originalZone)
    }

    private fun prepare(options: RetentionNotificationOptions = RetentionNotificationOptions()): String {
        kit = ExampleQa.prepare(application, notifications = options, initialTimeMillis = fixtureNow)
        assertEquals(fixtureNow, kit.runtime.clock.wallTimeMillis())
        assertEquals(fixtureNow.toString(), kit.runtime.store.snapshot("core.user").string("setup_at"))
        kit.runtime.signal(RetentionSignal.ProcessBackground)
        return ExampleQa.savedAlarm(NotificationCampaign.DAILY).also { raw ->
            val alarm = JSONObject(raw)
            assertEquals(Instant.parse("2026-09-07T08:00:00Z").toEpochMilli(), alarm.getLong("due"))
            assertTrue(alarm.getLong("due") < fixtureNow && fixtureNow < alarm.getLong("expires"))
        }
    }

    private fun deliver(raw: String) {
        ExampleQa.deliverSavedAlarm(application, raw)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun active(campaign: NotificationCampaign) = manager.activeNotifications.filter {
        it.tag == "io.retentionkit.notifications" && it.id == campaign.notificationId
    }
    private fun hasEvent(name: String, reason: String? = null) = ExampleQa.events.any {
        it.name == name && (reason == null || it.attributes["reason"] == reason)
    }
    private fun diagnostic() = "now=${kit.runtime.clock.wallTimeMillis()}, setup=${kit.runtime.userState}, events=${ExampleQa.events}"

    @Test fun dueDailyStillInsideTtlPostsWithoutRewindingBeforeSetupAndDoesNotDuplicate() {
        val raw = prepare()
        deliver(raw)
        assertEquals(diagnostic(), 1, active(NotificationCampaign.DAILY).size)
        assertEquals(fixtureNow, kit.runtime.clock.wallTimeMillis())
        deliver(raw)
        assertEquals(1, ExampleQa.events.count { it.name == "retention_noti_post_submitted" && it.attributes["campaign"] == "daily" })
        assertEquals(fixtureNow, kit.runtime.clock.wallTimeMillis())
    }

    @Test fun dueDailyReachesActualBlockedChannelGateInsteadOfClockCooldown() {
        val channel = "qa_calendar_blocked"
        manager.createNotificationChannel(NotificationChannel(channel, "Blocked calendar QA", NotificationManager.IMPORTANCE_NONE))
        val raw = prepare(RetentionNotificationOptions(channelIds = mapOf(NotificationCampaign.DAILY to channel)))
        deliver(raw)
        assertTrue(diagnostic(), hasEvent("retention_noti_skipped", "channel_blocked"))
        assertTrue(active(NotificationCampaign.DAILY).isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(channel).importance)
        assertEquals(fixtureNow, kit.runtime.clock.wallTimeMillis())
    }

    @Test fun futureSavedEnvelopeAdvancesOnlyToDueAndUsesActualReceiver() {
        prepare()
        val raw = ExampleQa.savedAlarm(NotificationCampaign.WINBACK)
        val due = JSONObject(raw).getLong("due")
        assertTrue(due > fixtureNow)
        deliver(raw)
        assertEquals(diagnostic(), 1, active(NotificationCampaign.WINBACK).size)
        assertEquals(due, kit.runtime.clock.wallTimeMillis())
    }

    @Test fun expiredEnvelopeCannotBeMadeFreshByMovingFixtureClockBackward() {
        val raw = prepare()
        val expiredNow = JSONObject(raw).getLong("expires") + 1
        (kit.runtime.clock as ExampleQa.QaClock).now = expiredNow
        deliver(raw)
        assertTrue(diagnostic(), hasEvent("retention_noti_skipped", "expired"))
        assertTrue(active(NotificationCampaign.DAILY).isEmpty())
        assertEquals(expiredNow, kit.runtime.clock.wallTimeMillis())
    }
}
