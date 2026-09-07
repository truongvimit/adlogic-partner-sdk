package com.itg.template.retention

import android.Manifest
import android.app.Application
import android.app.Activity
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.main.MainActivity
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import kotlinx.coroutines.flow.first

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
    private data class ActivityMoment(val activity: Activity, val phase: String, val entry: RetentionEntry?, val elapsed: Long = SystemClock.elapsedRealtime())
    private val moments = CopyOnWriteArrayList<ActivityMoment>()
    private var lastAdGestureAt = 0L
    private var fixtureInitialTimeMillis = 0L
    private val activityObserver = object : Application.ActivityLifecycleCallbacks {
        private fun record(activity: Activity, phase: String) {
            val entry = (RetentionEntryCodec.read(activity.intent) as? RetentionEntryDecodeResult.Valid)?.entry
            moments.add(ActivityMoment(activity, phase, entry))
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = record(activity, "created")
        override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = record(activity, "destroyed")
    }
    @Before fun permissionPrecondition() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Grant POST_NOTIFICATIONS to the example package before this suite", PackageManager.PERMISSION_GRANTED,
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        cancelOwnedNotifications()
        application.registerActivityLifecycleCallbacks(activityObserver)
    }
    @After fun restoreNormalProfile() {
        try {
            feedbackCleanup?.invoke()
            instrumentation.runOnMainSync {
                moments.map { it.activity }.distinct().filter {
                    it is SplashActivity || it is MainActivity || it is RetentionPlaygroundActivity || it is RetentionFeedbackActivity
                }.forEach { if (!it.isDestroyed) it.finish() }
            }
            scenario?.close()
        } finally {
            application.unregisterActivityLifecycleCallbacks(activityObserver)
            moments.clear()
            scenario = null
            instrumentation.runOnMainSync { ExampleQa.restore(application) }
            cancelOwnedNotifications()
            if (Build.VERSION.SDK_INT >= 26) ownedChannels.forEach(manager::deleteNotificationChannel)
        }
    }
    private fun prepare(setup: Boolean = true, extra: Map<String, String> = emptyMap(), launch: Intent? = null,
        notifications: RetentionNotificationOptions = RetentionNotificationOptions(),
        initialTimeMillis: Long = System.currentTimeMillis()) {
        fixtureInitialTimeMillis = initialTimeMillis
        instrumentation.runOnMainSync { kit = ExampleQa.prepare(application, setup, extra, notifications = notifications, initialTimeMillis = initialTimeMillis) }
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
        await(8000, diagnostic = { notificationDiagnostic(campaign) }) { active(campaign) != null }
        val notification = active(campaign)!!.notification
        assertNull("No full-screen marketing", notification.fullScreenIntent)
        assertEquals(if (campaign == NotificationCampaign.LOCKSCREEN) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertFalse(notification.extras.getString("io.retentionkit.notifications.occurrence.v1").isNullOrEmpty())
        await { ExampleQa.events.any { it.name == "retention_noti_post_submitted" && it.attributes["campaign"] == campaign.key } }
    }
    private fun notificationDiagnostic(campaign: NotificationCampaign) =
        "campaign=${campaign.key}; now=${kit.runtime.clock.wallTimeMillis()}; setupAt=${kit.runtime.userState.setupCompletedAtMillis}; foreground=${kit.runtime.isForeground}; revision=${kit.runtime.config.revision}; active=${active(campaign) != null}; recentEvents=${ExampleQa.events.takeLast(8)}"

    private fun dailyCatchUpTime(): Long = Calendar.getInstance().apply {
        // Always exercise due < fixtureNow < expiry at 08:56 local, even after the real clock
        // passes 09:00. Tomorrow keeps real AlarmManager delivery outside this bounded test.
        add(Calendar.DATE, 1)
        set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 56); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun assertDailyCatchUp(raw: String) {
        val alarm = JSONObject(raw)
        val now = kit.runtime.clock.wallTimeMillis()
        assertEquals("Fixture starts at 08:56 against the real saved 08:00 DAILY envelope", 56 * 60_000L,
            fixtureInitialTimeMillis - alarm.getLong("due"))
        assertEquals(4 * 60_000L, alarm.getLong("expires") - fixtureInitialTimeMillis)
        assertTrue("Live QA clock must remain inside this saved catch-up interval", now in fixtureInitialTimeMillis until alarm.getLong("expires"))
        assertTrue("Setup is recorded after fixture activation without rewinding", checkNotNull(kit.runtime.userState.setupCompletedAtMillis) in fixtureInitialTimeMillis..now)
    }
    private fun calendar(campaign: NotificationCampaign) {
        prepare(initialTimeMillis = if (campaign == NotificationCampaign.DAILY) dailyCatchUpTime() else System.currentTimeMillis())
        val raw = ExampleQa.savedAlarm(campaign)
        if (campaign == NotificationCampaign.DAILY) assertDailyCatchUp(raw)
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
    @Test fun lockscreenActualSavedEnvelopePostsWithoutFullScreenIntent() = calendar(NotificationCampaign.LOCKSCREEN)
    private fun completedOnboardState(): Boolean = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5000) { io.onboardkit.OnboardingSdk.state.first().isFlowCompleted }
    }
    @Test fun completedRealOnboardStateSynchronizesAndSuppressesUnfinishedAbandonment() {
        // This installation has completed the real first-open flow; never erase it for a test.
        assertTrue("Complete the actual first-open flow before this established-installation suite", completedOnboardState())
        assertFalse(io.onboardkit.OnboardingSdk.isFlowActive.value)
        prepare(setup = false)
        await { kit.runtime.userState.setupCompleted && !kit.runtime.userState.onboardingActive }
        background()
        SystemClock.sleep(3600)
        assertNull(active(NotificationCampaign.ONBOARDING))
        assertFalse(ExampleQa.events.any { it.name == "retention_noti_post_submitted" && it.attributes["campaign"] == "onboarding" })
        assertTrue(completedOnboardState())
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
        assertTrue(notification.actions.isNotEmpty())
        notification.actions.first().actionIntent.send() // Actual Later action; dismiss has separate coverage.
        await { active(NotificationCampaign.REMINDER) == null }
    }
    @Test fun pinnedHasFourDistinctActionsAndEveryActionCrossesActualSplashAndMain() {
        prepare(extra = mapOf("notifications.pinned.enabled" to "true"))
        assertSubmitted(NotificationCampaign.PINNED)
        val actions = active(NotificationCampaign.PINNED)!!.notification.actions.toList()
        assertEquals(4, actions.size)
        assertEquals(4, actions.map { it.actionIntent }.toSet().size)
        val acknowledged = mutableSetOf<String>()
        for ((index, action) in actions.withIndex()) {
            val offset = moments.size
            action.actionIntent.send()
            val entry = awaitFeatureRoute(RetentionEntrySource.PINNED, kit.runtime.features()[index].id, offset)
            assertTrue("Each action materializes a distinct ONCE token", acknowledged.add(entry.token))
        }
        assertEquals(4, acknowledged.size)
    }

    /** Budget covers the real Splash remote/ad barriers. No timing or ad result is injected here. */
    private fun awaitFeatureRoute(source: RetentionEntrySource, destination: String, offset: Int): RetentionEntry {
        var selected: RetentionEntry? = null
        await(45_000, diagnostic = { "Expected $source/$destination; lifecycle=" + moments.drop(offset).map {
            "${it.activity.javaClass.simpleName}:${it.phase}:${it.entry?.token}:${it.entry?.destination}"
        } + "; native=" + ExampleQa.nativeEvents.takeLast(8) }) {
            dismissVisibleEntryTestAd()
            var ready = false
            instrumentation.runOnMainSync {
                val feature = kit.runtime.activities.current() as? RetentionPlaygroundActivity
                val entry = feature?.let { (RetentionEntryCodec.read(it.intent) as? RetentionEntryDecodeResult.Valid)?.entry }
                if (entry != null && entry.source == source && entry.destination == destination &&
                    moments.drop(offset).any { it.activity === feature && it.phase == "resumed" && it.entry?.token == entry.token } &&
                    kit.runtime.store.snapshot("core.entries").string("consumed:${entry.token}") != null) {
                    val title = feature.findViewById<TextView>(R.id.rk_feature_title)?.text?.toString()
                    ready = title == kit.runtime.features().single { it.id == destination }.label
                    if (ready) selected = entry
                }
            }
            ready
        }
        val entry = checkNotNull(selected)
        val observed = moments.drop(offset)
        val splashResumed = observed.indexOfFirst { it.activity is SplashActivity && it.phase == "resumed" && it.entry == entry }
        assertTrue("Actual Splash must resume with the complete materialized entry: $observed", splashResumed >= 0)
        val splash = observed[splashResumed].activity
        val splashCreated = observed.indexOfFirst { it.activity === splash && it.phase == "created" }
        assertTrue("The same actual Splash instance must have been created: $observed", splashCreated >= 0)
        val createdEntry = checkNotNull(observed[splashCreated].entry)
        if (createdEntry.mode == RetentionEntryMode.REUSABLE) {
            assertNotEquals("Capture materializes a fresh token", createdEntry.token, entry.token)
            assertTrue(entry.createdAtMillis >= createdEntry.createdAtMillis)
            assertEquals("Materialization changes only token, creation time and mode; payload is exact",
                createdEntry, entry.copy(token = createdEntry.token, createdAtMillis = createdEntry.createdAtMillis, mode = RetentionEntryMode.REUSABLE))
        } else assertEquals("An ONCE envelope stays exact from creation", createdEntry, entry)
        val mainResumed = observed.indexOfFirst { it.activity is MainActivity && it.phase == "resumed" && it.entry == entry }
        val featureResumed = observed.indexOfFirst { it.activity is RetentionPlaygroundActivity && it.phase == "resumed" && it.entry == entry }
        assertTrue("Actual Splash created/resumed → Main resumed → feature resumed required: $observed",
            splashCreated >= 0 && splashResumed >= splashCreated && mainResumed > splashResumed && featureResumed > mainResumed)
        android.util.Log.i("RetentionRouteEvidence", "verified token=${entry.token} source=$source destination=$destination " +
            observed.filter { it.entry?.token == entry.token }.map { "${it.activity.javaClass.simpleName}:${it.phase}" })
        val expectedInter = when (source) {
            RetentionEntrySource.WIDGET, RetentionEntrySource.SHORTCUT -> "inter_widget"
            RetentionEntrySource.FEEDBACK -> "inter_uninstall"
            else -> "inter_noti"
        }
        assertTrue("Actual Splash resolution must select configured entry placement $expectedInter", ExampleQa.entryAdSelections.any { it.token == entry.token && it.key == expectedInter })
        val terminal = ExampleQa.adEvents.filter { it.elapsed >= observed[splashCreated].elapsed && it.placement == "splash_inter" }
        assertTrue("Actual ad terminal callback or legitimate skip required; observed=$terminal", terminal.any { it.name in setOf("ad_closed", "ad_skipped", "ad_show_failed") })
        assertEquals(RetentionEntryMode.ONCE, entry.mode)
        assertNull(kit.runtime.entries.pending(entry.token))
        assertTrue(kit.capture(Intent((kit.runtime.activities.current() as RetentionPlaygroundActivity).intent)) is RetentionEntryAcceptance.Rejected)
        val placement = ExampleEntryNative.placement(source)
        await { ExampleQa.nativeEvents.any { it.placement == placement && it.phase == "request_called" } }
        // request_called is only the real helper callsite. Impression/loaded remain separate facts.
        return entry
    }

    @Test fun widgetAndFeatureShortcutUseTheSameSplashRouteForEveryDestination() {
        prepare()
        for (source in listOf(RetentionEntrySource.WIDGET, RetentionEntrySource.SHORTCUT)) {
            for (feature in kit.runtime.features()) {
                val offset = moments.size
                val entry = RetentionEntry(source, feature.id, "open_${feature.id}", createdAtMillis = kit.runtime.clock.wallTimeMillis())
                instrumentation.runOnMainSync { application.startActivity(checkNotNull(kit.runtime.createEntryIntent(entry))) }
                assertEquals(entry.token, awaitFeatureRoute(source, feature.id, offset).token)
            }
        }
    }

    @Test fun rapidEntriesFinishAtLatestExplicitDestinationThroughSplash() {
        prepare()
        val older = RetentionEntry(RetentionEntrySource.DAILY, "saved_items", "open_saved", createdAtMillis = kit.runtime.clock.wallTimeMillis())
        val latest = RetentionEntry(RetentionEntrySource.WIDGET, "guide", "open_guide", createdAtMillis = kit.runtime.clock.wallTimeMillis())
        val offset = moments.size
        instrumentation.runOnMainSync {
            application.startActivity(checkNotNull(kit.runtime.createEntryIntent(older)))
            application.startActivity(checkNotNull(kit.runtime.createEntryIntent(latest)))
        }
        assertEquals(latest.token, awaitFeatureRoute(RetentionEntrySource.WIDGET, "guide", offset).token)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val current = kit.runtime.activities.current() as RetentionPlaygroundActivity
            assertEquals("guide", (RetentionEntryCodec.read(current.intent) as RetentionEntryDecodeResult.Valid).entry.destination)
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
        prepare(notifications = RetentionNotificationOptions(channelIds = mapOf(NotificationCampaign.DAILY to channel)),
            initialTimeMillis = dailyCatchUpTime())
        val raw = ExampleQa.savedAlarm(NotificationCampaign.DAILY)
        assertDailyCatchUp(raw)
        background()
        instrumentation.runOnMainSync { ExampleQa.deliverSavedAlarm(application, raw) }
        await(diagnostic = { notificationDiagnostic(NotificationCampaign.DAILY) }) {
            ExampleQa.events.any { it.name == "retention_noti_skipped" && it.attributes["campaign"] == "daily" && it.attributes["reason"] == "channel_blocked" }
        }
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
    @Test fun coldEntryRunsStandardSplashThenUsableDestinationAndRejectsReplay() {
        instrumentation.runOnMainSync { kit = ExampleQa.prepare(application) }
        val entry = RetentionEntry(RetentionEntrySource.DAILY, "text_tools", "open_text",
            createdAtMillis = kit.runtime.clock.wallTimeMillis())
        val intent = checkNotNull(kit.runtime.createEntryIntent(entry))
        val offset = moments.size
        instrumentation.runOnMainSync { application.startActivity(intent) }
        assertEquals(entry.token, awaitFeatureRoute(RetentionEntrySource.DAILY, "text_tools", offset).token)
        instrumentation.runOnMainSync {
            val feature = kit.runtime.activities.current() as RetentionPlaygroundActivity
            feature.findViewById<EditText>(R.id.rk_text_input).setText("one two three")
            feature.findViewById<Button>(R.id.rk_text_analyze).performClick()
            assertTrue(feature.findViewById<TextView>(R.id.rk_result).text.contains("3"))
        }
        assertTrue(kit.capture(intent) is RetentionEntryAcceptance.Rejected)
    }
    @Test fun realBusinessOutboxReplayDoesNotAddReviewSuccessTwice() {
        prepare(extra = mapOf("review.enabled" to "true", "review.success_threshold" to "1000"))
        val data = ExampleDataStore(application)
        val id = "instrumented-" + java.util.UUID.randomUUID()
        instrumentation.runOnMainSync { RetentionExample.flushSuccesses(application) }
        val before = kit.review!!.snapshot()!!.successesSinceAttempt
        data.record(id, "notes", "A useful note")
        instrumentation.runOnMainSync {
            // Replay the same durable operation ID through the real review module.
            kit.businessSuccess("notes", id)
            kit.businessSuccess("notes", id)
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
                if (view is Button && view.contentDescription == "feature:notes") return view
                if (view is android.view.ViewGroup) repeat(view.childCount) { index ->
                    find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            checkNotNull(find(activity.findViewById(android.R.id.content))).performClick()
            activity.findViewById<EditText>(R.id.rk_note_input).setText("A useful note " + java.util.UUID.randomUUID())
            activity.findViewById<Button>(R.id.rk_note_save).performClick()
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
        installFeedbackCleanup()
        openActualFeedback()
        clickFeedback("rk_feedback_keep")
        await { kit.runtime.activities.current() is MainActivity }
        assertTrue(ExampleQa.events.any { it.name == "retention_feedback_kept" })
        assertFalse(ExampleQa.events.any { it.name == "retention_feedback_reason" })

        openActualFeedback()
        val rescueOffset = moments.size
        clickFeedback("rk_feedback_feature_text_tools")
        awaitFeatureRoute(RetentionEntrySource.FEEDBACK, "text_tools", rescueOffset)
        instrumentation.runOnMainSync {
            val current = kit.runtime.activities.current() as RetentionPlaygroundActivity
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

    @Test fun actualFeedbackContinueWithoutReasonOpensOwnUninstallConfirmationAndCancelReturns() {
        prepare()
        installFeedbackCleanup()
        openActualFeedback()
        val expectedSettings = application.packageManager.resolveActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE,
            android.net.Uri.fromParts("package", application.packageName, null)), PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("An actual uninstall confirmation must resolve", expectedSettings)
        clickFeedback("rk_feedback_continue")
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Uninstall confirmation must actually open", device.wait(Until.hasObject(By.pkg(expectedSettings!!.activityInfo.packageName)), 5000))
        val appLabel = application.applicationInfo.loadLabel(application.packageManager).toString()
        assertTrue("Uninstall confirmation must identify this application", device.wait(Until.hasObject(By.textContains(appLabel)), 5000))
        assertTrue(ExampleQa.events.any { it.name == "retention_feedback_system_handoff" })
        assertFalse(ExampleQa.events.any { it.name == "retention_feedback_reason" })
        assertTrue(device.pressBack())
        await(8000) { kit.runtime.activities.current() is MainActivity }
        // Confirmation is cancelled, never approved; package remains installed.
        assertNotNull(application.packageManager.getApplicationInfo(application.packageName, 0))
    }

    private fun openActualFeedback() {
        // Keep returns to the real Main back stack. Use its real navigation control to reopen tools.
        instrumentation.runOnMainSync {
            (kit.runtime.activities.current() as? MainActivity)?.findViewById<Button>(R.id.btn_retention_playground)?.performClick()
        }
        await { kit.runtime.activities.current() is RetentionPlaygroundActivity }
        val before = ExampleQa.events.count { it.name == "retention_feedback_shown" }
        val offset = moments.size
        instrumentation.runOnMainSync {
            val host = kit.runtime.activities.current() as RetentionPlaygroundActivity
            host.findViewById<Button>(R.id.rk_open_feedback).performClick()
        }
        await(45_000) {
            dismissVisibleEntryTestAd()
            kit.runtime.activities.current() is RetentionFeedbackActivity &&
                ExampleQa.events.count { it.name == "retention_feedback_shown" } > before
        }
        val observed = moments.drop(offset)
        val main = observed.indexOfLast { it.activity is MainActivity && it.phase == "resumed" && it.entry?.destination == "retention.feedback" }
        assertTrue("Feedback must pass actual Main resume", main >= 0)
        val token = checkNotNull(observed[main].entry).token
        val splash = observed.indexOfFirst { it.activity is SplashActivity && it.phase == "resumed" && it.entry?.token == token }
        val feedback = observed.indexOfLast { it.activity is RetentionFeedbackActivity && it.phase == "resumed" }
        assertTrue("Feedback requires Splash → Main → SDK screen: $observed", splash >= 0 && main > splash && feedback > main)
        assertNotNull(kit.runtime.store.snapshot("core.entries").string("consumed:$token"))
        await { ExampleQa.nativeEvents.any { it.placement == "native_uninstall" && it.phase == "request_called" } }
    }
    /** Actual device gesture on the actual test-ad Activity only. No synthetic ad callbacks. */
    private fun dismissVisibleEntryTestAd() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdGestureAt < 1000) return
        val device = UiDevice.getInstance(instrumentation)
        if (!focusedTestAd() || device.currentPackageName != application.packageName) return
        val closeLabel = java.util.regex.Pattern.compile(
            "^(?:close(?: ad)?|interstitial close button|đóng(?: quảng cáo)?)$",
            java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
        )
        val closeResource = java.util.regex.Pattern.compile(
            ".*:id/(?:close|close_button|close_btn|interstitial_close|interstitial_close_button)$",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )
        // Only a semantically identified, currently exposed close control can be clicked.
        // Unlabelled image buttons and ad content are never guessed from their position.
        val close = device.findObject(By.desc(closeLabel).pkg(application.packageName))
            ?: device.findObject(By.text(closeLabel).pkg(application.packageName))
            ?: device.findObject(By.res(closeResource).pkg(application.packageName))
        if (close != null) {
            val clicked = runCatching {
                if (!close.isEnabled || !focusedTestAd() || device.currentPackageName != application.packageName) return
                val observed = "class=${close.className} resource=${close.resourceName} description=${close.contentDescription} text=${close.text} bounds=${close.visibleBounds}"
                if (!focusedTestAd() || device.currentPackageName != application.packageName) return
                lastAdGestureAt = now
                close.click()
                android.util.Log.i("RetentionAdEvidence", "actual gesture=semantic_close $observed elapsed=$now")
            }.onFailure {
                android.util.Log.i("RetentionAdEvidence", "close_control_changed=${it.javaClass.simpleName} elapsed=$now")
            }.isSuccess
            if (clicked) return
        }
        // Back is a real fallback gesture, not proof that the ad accepted or completed it.
        if (!focusedTestAd() || device.currentPackageName != application.packageName) return
        lastAdGestureAt = now
        val sent = device.pressBack()
        android.util.Log.i("RetentionAdEvidence", "actual gesture=Back activity=com.google.android.gms.ads.AdActivity sent=$sent elapsed=$now")
    }

    private fun focusedTestAd(): Boolean {
        var visibleAd = false
        instrumentation.runOnMainSync {
            val current = kit.runtime.activities.current()
            visibleAd = current?.javaClass?.name == "com.google.android.gms.ads.AdActivity" &&
                !current.isFinishing && !current.isDestroyed && current.hasWindowFocus()
        }
        return visibleAd
    }

    private fun clickFeedback(tag: String) {
        instrumentation.runOnMainSync {
            val feedback = kit.runtime.activities.current() as RetentionFeedbackActivity
            val button = feedback.findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<Button>(tag)
            assertNotNull("Actual standard feedback action must exist: $tag", button)
            button.performClick()
        }
    }
    private fun installFeedbackCleanup() {
        feedbackCleanup = {
            val device = UiDevice.getInstance(instrumentation)
            if (device.currentPackageName != application.packageName) device.pressBack()
        }
    }

    private fun cancelOwnedNotifications() {
        manager.activeNotifications.filter { it.tag == "io.retentionkit.notifications" }.forEach { manager.cancel(it.tag, it.id) }
    }
    private fun await(timeout: Long = 5000, diagnostic: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        val passed = predicate()
        assertTrue("Timed out waiting for actual engine/Android state. ${diagnostic()}", passed)
    }
}
