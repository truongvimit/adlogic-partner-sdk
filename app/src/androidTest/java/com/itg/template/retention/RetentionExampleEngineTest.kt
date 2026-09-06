package com.itg.template.retention

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.retentionkit.feedback.RetentionFeedbackActivity
import com.itg.template.R
import io.retentionkit.RetentionKit
import io.retentionkit.core.*
import io.retentionkit.notifications.NotificationCampaign
import io.retentionkit.notifications.RetentionNotificationOptions
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
    private val ownedChannels = mutableListOf<String>()
    private var feedbackCleanup: (() -> Unit)? = null
    @Before fun permissionPrecondition() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Grant POST_NOTIFICATIONS to the example package before this suite", PackageManager.PERMISSION_GRANTED,
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        cancelOwnedNotifications()
    }
    @After fun restoreNormalProfile() {
        try { feedbackCleanup?.invoke(); scenario?.close() } finally {
            scenario = null
            instrumentation.runOnMainSync { ExampleQa.restore(application) }
            cancelOwnedNotifications()
            if (Build.VERSION.SDK_INT >= 26) ownedChannels.forEach(manager::deleteNotificationChannel)
        }
    }
    private fun prepare(setup: Boolean = true, extra: Map<String, String> = emptyMap(), launch: Intent? = null,
        notifications: RetentionNotificationOptions = RetentionNotificationOptions()) {
        instrumentation.runOnMainSync { kit = ExampleQa.prepare(application, setup, extra, notifications = notifications) }
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
    @Test fun realBlockedChannelSuppressesSavedAlarmWithoutChangingProductionChannels() {
        assertTrue("This Android channel test requires API26+", Build.VERSION.SDK_INT >= 26)
        val channel = "rk_example_qa_blocked_" + java.util.UUID.randomUUID()
        ownedChannels.add(channel)
        manager.createNotificationChannel(NotificationChannel(channel, "Blocked Retention QA", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(channel).importance)
        prepare(notifications = RetentionNotificationOptions(channelIds = mapOf(NotificationCampaign.DAILY to channel)))
        val raw = ExampleQa.savedAlarm(NotificationCampaign.DAILY)
        background()
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, raw) }
        await { ExampleQa.events.any { it.name == "retention_noti_skipped" && it.attributes["campaign"] == "daily" && it.attributes["reason"] == "channel_blocked" } }
        assertNull(active(NotificationCampaign.DAILY))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(channel).importance)
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
    @Test fun acceleratedEligibilityUsesRealPlayTransportAndReleasesLeaseAfterHonestTerminal() {
        prepare()
        // Drain older app work while review is disabled, then accelerate only this isolated case.
        instrumentation.runOnMainSync {
            RetentionExample.flushSuccesses(application)
            val applied = kit.runtime.updateConfig(mapOf(
                "review.enabled" to "true", "review.success_threshold" to "1",
                "review.request_timeout_ms" to "5000", "review.flow_timeout_ms" to "5000",
            ))
            assertTrue(applied is RetentionConfigResult.Applied)
        }
        scenario!!.onActivity { activity ->
            fun find(view: android.view.View): Button? {
                if (view is Button && view.contentDescription == "feature:translate") return view
                if (view is android.view.ViewGroup) repeat(view.childCount) { index ->
                    find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            checkNotNull(find(activity.findViewById(android.R.id.content))).performClick()
            activity.findViewById<Button>(R.id.rk_phrase_translate).performClick()
            assertTrue(activity.findViewById<TextView>(R.id.rk_result).text.isNotBlank())
        }
        await { ExampleQa.events.any { it.name == "retention_review_requested" } }
        val terminals = setOf("retention_review_failed", "retention_review_timeout", "retention_review_flow_unknown")
        await(15_000) { ExampleQa.events.any { it.name in terminals } }
        assertNull(kit.review!!.snapshot()!!.inFlightPhase)
        await { kit.runtime.ui.eligibility() is RetentionEligibility.Allowed }
        instrumentation.runOnMainSync {
            val acquired = kit.runtime.ui.acquire("test.review.released", 1000)
            assertTrue("Terminal must release the owned UI lease", acquired is RetentionUiLeaseResult.Acquired)
            (acquired as RetentionUiLeaseResult.Acquired).lease.close()
        }
        // No card, submitted rating, or review acceptance is inferred from Play's callback.
    }

    @Test fun actualFeedbackKeepAndRescueNeedNoReasonAndOpenUsableFeatureWithoutHome() {
        prepare()
        val host = installFeedbackCleanup()
        openActualFeedback()
        clickFeedback("rk_feedback_keep")
        await { kit.runtime.activities.current() === host && kit.runtime.ui.eligibility() is RetentionEligibility.Allowed }
        assertTrue(ExampleQa.events.any { it.name == "retention_feedback_kept" })
        assertFalse(ExampleQa.events.any { it.name == "retention_feedback_reason" })

        openActualFeedback()
        clickFeedback("rk_feedback_feature_text_tools")
        // No Home/restart workaround is allowed: this is the real internal handoff readiness boundary.
        await(8000) {
            var ready = false
            instrumentation.runOnMainSync {
                val current = kit.runtime.activities.current() as? RetentionPlaygroundActivity
                ready = current?.findViewById<EditText>(R.id.rk_text_input) != null
            }
            ready
        }
        instrumentation.runOnMainSync {
            val current = kit.runtime.activities.current() as RetentionPlaygroundActivity
            assertEquals("Feedback rescue must stay in the host task", host.taskId, current.taskId)
            val entry = (RetentionEntryCodec.read(current.intent) as RetentionEntryDecodeResult.Valid).entry
            assertEquals(RetentionEntrySource.FEEDBACK, entry.source)
            assertEquals("text_tools", entry.destination)
            assertNull(kit.runtime.entries.pending(entry.token))
            current.findViewById<EditText>(R.id.rk_text_input).setText("hello from feedback")
            current.findViewById<Button>(R.id.rk_text_analyze).performClick()
            assertTrue(current.findViewById<TextView>(R.id.rk_result).text.contains("3"))
            assertTrue(kit.capture(Intent(current.intent)) is RetentionEntryAcceptance.Rejected)
        }
        assertFalse(ExampleQa.events.any { it.name == "retention_feedback_reason" })
    }

    @Test fun actualFeedbackContinueWithoutReasonOpensOwnAppInfoAndBackReleasesUi() {
        prepare()
        val host = installFeedbackCleanup()
        openActualFeedback()
        val expectedSettings = application.packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", application.packageName, null)), PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("An actual App Info destination must resolve", expectedSettings)
        clickFeedback("rk_feedback_continue")
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("App Info must actually open", device.wait(Until.hasObject(By.pkg(expectedSettings!!.activityInfo.packageName)), 5000))
        val appLabel = application.applicationInfo.loadLabel(application.packageManager).toString()
        assertTrue("App Info must identify this application", device.wait(Until.hasObject(By.textContains(appLabel)), 5000))
        assertTrue(ExampleQa.events.any { it.name == "retention_feedback_system_handoff" })
        assertFalse(ExampleQa.events.any { it.name == "retention_feedback_reason" })
        assertTrue(device.pressBack())
        await(8000) { kit.runtime.activities.current() === host && kit.runtime.ui.eligibility() is RetentionEligibility.Allowed }
        instrumentation.runOnMainSync {
            val acquired = kit.runtime.ui.acquire("test.feedback.return", 1000)
            assertTrue(acquired is RetentionUiLeaseResult.Acquired)
            (acquired as RetentionUiLeaseResult.Acquired).lease.close()
        }
    }

    private fun openActualFeedback() {
        val before = ExampleQa.events.count { it.name == "retention_feedback_shown" }
        instrumentation.runOnMainSync {
            val host = kit.runtime.activities.current() as RetentionPlaygroundActivity
            host.findViewById<Button>(R.id.rk_open_feedback).performClick()
        }
        await { kit.runtime.activities.current() is RetentionFeedbackActivity &&
            ExampleQa.events.count { it.name == "retention_feedback_shown" } > before }
    }
    private fun clickFeedback(tag: String) {
        instrumentation.runOnMainSync {
            val feedback = kit.runtime.activities.current() as RetentionFeedbackActivity
            val button = feedback.findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<Button>(tag)
            assertNotNull("Actual standard feedback action must exist: $tag", button)
            button.performClick()
        }
    }
    private fun installFeedbackCleanup(): RetentionPlaygroundActivity {
        lateinit var original: RetentionPlaygroundActivity
        lateinit var launchIntent: Intent
        scenario!!.onActivity { original = it; launchIntent = Intent(it.intent) }
        feedbackCleanup = {
            // Cleanup only Activities created by this bounded flow; never clear app data or a task.
            val device = UiDevice.getInstance(instrumentation)
            if (device.currentPackageName == "com.android.settings") device.pressBack()
            instrumentation.runOnMainSync {
                val current = kit.runtime.activities.current()
                if (current !== original && (current is RetentionFeedbackActivity || current is RetentionPlaygroundActivity)) current.finish()
                original.intent = launchIntent
            }
            instrumentation.waitForIdleSync()
        }
        return original
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
