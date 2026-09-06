package com.itg.template.retention

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itg.template.R
import io.retentionkit.RetentionKit
import io.retentionkit.core.*
import io.retentionkit.notifications.NotificationCampaign
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android adapter tests with an explicitly synthetic engine clock/state.
 * Calendar tests deliver the actual saved alarm to its actual receiver; they do not test OS wake timing.
 * Parent-owned device runner grants this app POST_NOTIFICATIONS before invocation. */
@RunWith(AndroidJUnit4::class)
class RetentionExampleEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = application.getSystemService(NotificationManager::class.java)
    private var scenario: ActivityScenario<RetentionPlaygroundActivity>? = null
    private lateinit var kit: RetentionKit
    @Before fun permissionPrecondition() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Grant POST_NOTIFICATIONS to the example package before this suite", PackageManager.PERMISSION_GRANTED,
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        cancelOwnedNotifications()
    }
    @After fun restoreNormalProfile() {
        try { scenario?.close() } finally {
            scenario = null
            instrumentation.runOnMainSync { ExampleQa.restore(application) }
            cancelOwnedNotifications()
        }
    }
    private fun prepare(setup: Boolean = true, extra: Map<String, String> = emptyMap(), launch: Intent? = null) {
        instrumentation.runOnMainSync { kit = ExampleQa.prepare(application, setup, extra) }
        scenario = ActivityScenario.launch(launch ?: Intent(application, RetentionPlaygroundActivity::class.java))
        await { kit.runtime.isForeground && kit.runtime.activities.current() is RetentionPlaygroundActivity }
    }
    private fun background() {
        assertTrue(UiDevice.getInstance(instrumentation).pressHome())
        await { !kit.runtime.isForeground }
    }
    private fun active(campaign: NotificationCampaign) = manager.activeNotifications.firstOrNull {
        it.tag == "io.retentionkit.notifications" && it.id == campaign.notificationId
    }
    private fun assertSubmitted(campaign: NotificationCampaign) {
        await(8000) { active(campaign) != null }
        val notification = active(campaign)!!.notification
        assertNull("No full-screen marketing", notification.fullScreenIntent)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertFalse(notification.extras.getString("io.retentionkit.notifications.occurrence.v1").isNullOrEmpty())
        await { ExampleQa.events.any { it.name == "retention_noti_post_submitted" && it.attributes["campaign"] == campaign.key } }
    }
    private fun calendar(campaign: NotificationCampaign) {
        prepare()
        val raw = ExampleQa.savedAlarm(campaign)
        background()
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, raw) }
        assertSubmitted(campaign)
        val occurrence = active(campaign)!!.notification.extras.getString("io.retentionkit.notifications.occurrence.v1")
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, raw) }
        assertEquals(occurrence, active(campaign)!!.notification.extras.getString("io.retentionkit.notifications.occurrence.v1"))
        assertEquals(1, ExampleQa.events.count { it.name == "retention_noti_post_submitted" && it.attributes["campaign"] == campaign.key })
    }
    @Test fun dailyActualSavedEnvelopePostsOnceInBackground() = calendar(NotificationCampaign.DAILY)
    @Test fun winbackActualSavedEnvelopePostsOnceInBackground() = calendar(NotificationCampaign.WINBACK)
    @Test fun lockscreenActualSavedEnvelopePostsWithoutFullScreenOrWakeRequest() = calendar(NotificationCampaign.LOCKSCREEN)
    @Test fun onboardingNeedsUnfinishedActiveSetupAndActualHome() {
        prepare(setup = false)
        background()
        assertSubmitted(NotificationCampaign.ONBOARDING)
    }
    @Test fun adReturnNeedsFreshDebugClickAndActualHome() {
        prepare()
        instrumentation.runOnMainSync { kit.adClicked("debug-test-click") }
        background()
        assertSubmitted(NotificationCampaign.AD_RETURN)
    }
    @Test fun quietReminderUsesRealForegroundAndLaterAction() {
        prepare(extra = mapOf("notifications.reminder.enabled" to "true"))
        assertSubmitted(NotificationCampaign.REMINDER)
        val notification = active(NotificationCampaign.REMINDER)!!.notification
        assertNotNull(notification.deleteIntent)
        notification.deleteIntent.send()
        await { active(NotificationCampaign.REMINDER) == null }
    }
    @Test fun pinnedHasFourDistinctActionsAndEveryActionReachesRealFeature() {
        prepare(extra = mapOf("notifications.pinned.enabled" to "true"))
        assertSubmitted(NotificationCampaign.PINNED)
        val actions = active(NotificationCampaign.PINNED)!!.notification.actions.toList()
        assertEquals(4, actions.size)
        assertEquals(4, actions.map { it.actionIntent }.toSet().size)
        lateinit var original: RetentionPlaygroundActivity
        lateinit var launchIntent: Intent
        scenario!!.onActivity { original = it; launchIntent = Intent(it.intent) }
        val originalTask = original.taskId
        try {
            for ((index, action) in actions.withIndex()) {
                action.actionIntent.send()
                val expected = kit.runtime.features()[index].label
                await {
                    var displayed = false
                    instrumentation.runOnMainSync {
                        val current = kit.runtime.activities.current() as? RetentionPlaygroundActivity
                        if (current != null) {
                            assertSame("Pinned taps must reuse the current destination Activity", original, current)
                            assertEquals(originalTask, current.taskId)
                            displayed = current.findViewById<TextView>(R.id.rk_feature_title)?.text?.toString() == expected
                        }
                    }
                    displayed
                }
            }
        } finally {
            // ActivityScenario filters lifecycle callbacks by its original Intent identity. The
            // app correctly calls setIntent on each action, so restore only the harness identity
            // after every real route assertion, immediately before teardown. Production is unchanged.
            instrumentation.runOnMainSync { original.intent = launchIntent }
        }
    }

    @Test fun calendarForegroundAndStaleConfigCannotPost() {
        prepare()
        val foreground = ExampleQa.savedAlarm(NotificationCampaign.DAILY)
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, foreground) }
        assertNull(active(NotificationCampaign.DAILY))
        val stale = ExampleQa.savedAlarm(NotificationCampaign.DAILY)
        instrumentation.runOnMainSync { kit.runtime.updateConfig(mapOf("notifications.daily.enabled" to "false")) }
        background()
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, stale) }
        assertNull(active(NotificationCampaign.DAILY))
    }
    @Test fun subscriberCancelsFreshAdReturnBeforeHome() {
        prepare()
        instrumentation.runOnMainSync {
            kit.adClicked("debug-subscriber-click")
            kit.entitlementChanged(RetentionEntitlement.SUBSCRIBER)
        }
        background()
        SystemClock.sleep(3600)
        assertNull(active(NotificationCampaign.AD_RETURN))
    }
    @Test fun pendingEntryWaitsForSetupAndRealDestinationResumeThenRejectsReplay() {
        instrumentation.runOnMainSync { kit = ExampleQa.prepare(application, setup = false) }
        val entry = RetentionEntry(RetentionEntrySource.DAILY, "text_tools", "open_text",
            createdAtMillis = kit.runtime.clock.wallTimeMillis())
        val intent = checkNotNull(kit.runtime.createEntryIntent(entry))
        scenario = ActivityScenario.launch(intent)
        await { kit.runtime.entries.pending(entry.token) != null }
        assertNotNull(kit.runtime.entries.pending(entry.token))
        instrumentation.runOnMainSync { kit.setupCompleted() }
        scenario!!.recreate()
        await { kit.runtime.entries.pending(entry.token) == null }
        scenario!!.onActivity {
            assertNotNull(it.findViewById<EditText>(R.id.rk_text_input))
            it.findViewById<EditText>(R.id.rk_text_input).setText("one two three")
            it.findViewById<Button>(R.id.rk_text_analyze).performClick()
            assertTrue(it.findViewById<TextView>(R.id.rk_result).text.contains("3"))
        }
        assertTrue(kit.capture(intent) is RetentionEntryAcceptance.Rejected)
    }
    @Test fun realBusinessOutboxReplayDoesNotAddReviewSuccessTwice() {
        prepare(extra = mapOf("review.enabled" to "true", "review.success_threshold" to "1000"))
        val data = ExampleDataStore(application)
        val id = "instrumented-" + java.util.UUID.randomUUID()
        instrumentation.runOnMainSync { RetentionExample.flushSuccesses(application) }
        val before = kit.review!!.snapshot()!!.successesSinceAttempt
        data.record(id, "translate", "Xin chào")
        instrumentation.runOnMainSync {
            // Replay the same durable operation ID through the real review module.
            kit.businessSuccess("translate", id)
            kit.businessSuccess("translate", id)
            RetentionExample.flushSuccesses(application)
        }
        await { kit.review!!.snapshot()!!.successesSinceAttempt == before + 1 }
        assertFalse(data.pendingSuccesses().any { it.id == id })
        assertEquals(0L, kit.review!!.snapshot()!!.attempts)
        assertFalse(ExampleQa.events.any { it.name == "retention_review_requested" })
    }
    private fun cancelOwnedNotifications() {
        manager.activeNotifications.filter { it.tag == "io.retentionkit.notifications" }.forEach { manager.cancel(it.tag, it.id) }
    }
    private fun await(timeout: Long = 5000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for actual engine/Android state", predicate())
    }
}
