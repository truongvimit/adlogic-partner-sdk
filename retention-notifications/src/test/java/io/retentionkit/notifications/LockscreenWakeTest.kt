package io.retentionkit.notifications

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.UUID

/** Saved calendar envelope and runtime gates; Android PowerManager is observed via Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockscreenWakeTest {
    private lateinit var app: Application
    private lateinit var clock: TestClock
    private lateinit var store: RetentionStore
    private lateinit var platform: FakePlatform
    private lateinit var runtime: RetentionRuntime
    private lateinit var module: RetentionNotifications
    private val delays = FakeDelays()
    private val finishedReceipts = mutableListOf<java.util.concurrent.atomic.AtomicBoolean>()

    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext()
        clock = TestClock()
        store = SharedPreferencesRetentionStore(app, "wake_${UUID.randomUUID()}")
        platform = FakePlatform()
        shadowOf(app).grantPermissions(Manifest.permission.WAKE_LOCK, Manifest.permission.POST_NOTIFICATIONS)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        power.setIsWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, true)
        ShadowPowerManager.clearWakeLocks()
    }
    @After fun after() { RetentionRuntime.uninstallForTests() }

    private fun install(power: NotificationWakePlatform? = null, entitlement: RetentionEntitlement = RetentionEntitlement.NON_SUBSCRIBER,
                        notifier: NotificationPlatform = platform) {
        module = RetentionNotifications(RetentionNotificationOptions(), notifier, delays, power)
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), clock = clock, store = store,
            initialUserState = RetentionUserState(true, false, entitlement,
                clock.now, clock.now, clock.now),
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification)) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity")) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
    }

    private fun <T> inAlarm(work: (NotificationAlarmExecution) -> T): T {
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        finishedReceipts += finished
        val owner = NotificationAlarmExecution({ finished.set(true) }, elapsed = clock::elapsedRealtimeMillis)
        try { return work(owner) } finally { owner.finishIfUnused() }
    }
    private fun ownedCheckpoint(occurrence: String, at: Long) = inAlarm { module.wakeCheckpoint(occurrence, at, it) }
    private fun dispatchWake(power: FakeWakePlatform, occurrence: String) {
        val at = power.checkpoints.getValue(occurrence)
        clock.advance((at - clock.now).coerceAtLeast(0))
        ownedCheckpoint(occurrence, at)
    }

    private fun send(): ScheduledNotification {
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        assertTrue(inAlarm { module.receiveAlarm(alarm, it) } is NotificationOutcome.PostSubmitted)
        return alarm
    }

    @Test fun defaultLockscreenPostRequestsBoundedWakeWithoutRequiringForegroundActivity() {
        install()
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        assertTrue(inAlarm { module.receiveAlarm(alarm, it) } is NotificationOutcome.PostSubmitted)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val lease = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("A default eligible screen-off lockscreen post must actually request wake", lease)
        assertTrue(lease.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertFalse("SDK lease must be released; this is not an assertion of physical screen-off", lease.isHeld)
    }

    @Test fun actualAlarmReceiptRemainsOwnedUntilRawScreenLeaseIsReleased() {
        install()
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        val receiver = NotificationAlarmReceiver()
        val result = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType!!, android.app.Activity.RESULT_OK),
            ClassParameter.from(String::class.java, null), ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        ReflectionHelpers.callInstanceMethod<Void>(receiver, "setPendingResult", ClassParameter.from(BroadcastReceiver.PendingResult::class.java, result))
        val completed = shadowOf(result).future
        var heldWhenFinished: Boolean? = null
        completed.addListener({ heldWhenFinished = ShadowPowerManager.getLatestWakeLock()?.isHeld }, java.util.concurrent.Executor { it.run() })
        // Robolectric's framework delivery finishes a synchronous receipt as onReceive returns.
        shadowOf(receiver).onReceive(app, Intent(app, NotificationAlarmReceiver::class.java)
            .setAction(ALARM_ACTION).putExtra(ALARM_EXTRA, alarm.encode()), java.util.concurrent.atomic.AtomicBoolean())
        val raw = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("Eligible actual alarm must acquire its screen lease", raw)
        assertTrue(raw.isHeld)
        assertFalse("Alarm framework receipt cannot finish while its raw screen lock remains owned", completed.isDone)
        RetentionRuntime.uninstallForTests()
        assertFalse(raw.isHeld)
        assertTrue(completed.isDone)
        assertEquals("Raw lock release must precede framework receipt completion", false, heldWhenFinished)
    }

    @Test fun actualRawLeaseDeadlineClosesReceiptWhileMainAndModuleMonitorCannotRunCleanup() {
        install()
        assertTrue(runtime.updateConfig(mapOf("notifications.lockscreen.wake_duration_ms" to "250")) is RetentionConfigResult.Applied)
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        val receiver = NotificationAlarmReceiver()
        val result = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType!!, android.app.Activity.RESULT_OK),
            ClassParameter.from(String::class.java, null), ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        ReflectionHelpers.callInstanceMethod<Void>(receiver, "setPendingResult", ClassParameter.from(BroadcastReceiver.PendingResult::class.java, result))
        val completed = shadowOf(result).future
        shadowOf(receiver).onReceive(app, Intent(app, NotificationAlarmReceiver::class.java)
            .setAction(ALARM_ACTION).putExtra(ALARM_EXTRA, alarm.encode()), java.util.concurrent.atomic.AtomicBoolean())
        val raw = ShadowPowerManager.getLatestWakeLock()
        assertTrue(raw.isHeld)
        assertFalse(completed.isDone)
        val monitor = ReflectionHelpers.getField<Any>(module, "lock")
        synchronized(monitor) {
            // No main-looper advancement; the controller cannot take its monitor either.
            completed.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse("An independent deadline must remove the raw lock before finishing the actual receipt", raw.isHeld)
        }
    }

    @Test fun confirmationTimeoutThenLateCallbackRequiresANewAlarmReceipt() {
        val power = FakeWakePlatform()
        var visible = false
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? =
                if (visible) platform.activeOccurrence(campaign) else null
        }
        install(power, notifier = notifier)
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        val deadlines = ManualNotificationDeadlines()
        var finished = false
        val owner = NotificationAlarmExecution({ finished = true }, deadlines, { deadlines.now })
        assertTrue(module.receiveAlarm(alarm, owner) is NotificationOutcome.PostSubmitted)
        owner.finishIfUnused()
        assertFalse(finished)
        deadlines.advance(2000)
        assertTrue(finished)
        visible = true
        delays.fire(evenCancelled = true)
        assertTrue("A late main callback cannot acquire after its execution receipt expired", power.leases.isEmpty())
        dispatchWake(power, alarm.occurrence)
        assertEquals(1, power.leases.size)
    }

    @Test fun processDeathDuringUnconfirmedPostPreservesOneBoundedAlarmDecision() {
        val power = FakeWakePlatform()
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? = null
        }
        install(power, notifier = notifier)
        val alarm = send()
        assertTrue(power.leases.isEmpty())
        RetentionRuntime.uninstallForTests() // Confirmation never ran in the old process.
        install(power, RetentionEntitlement.UNKNOWN) // Android now reports the exact submitted post.
        assertTrue("The pending post-confirmation decision must have a durable checkpoint before process death",
            power.checkpoints.containsKey(alarm.occurrence))
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        dispatchWake(power, alarm.occurrence)
        assertEquals(1, power.leases.size)
        delays.fire(evenCancelled = true)
        assertEquals("Old process confirmation callbacks cannot claim again", 1, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
    }

    @Test fun repeatedlyExpiredUnobservedConfirmationCannotCreateUnboundedAlarmPolling() {
        val power = FakeWakePlatform()
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? = null
        }
        install(power, notifier = notifier)
        val alarm = send()
        repeat(6) {
            val at = power.checkpoints.getValue(alarm.occurrence)
            clock.advance((at - clock.now).coerceAtLeast(0))
            val deadlines = ManualNotificationDeadlines()
            val owner = NotificationAlarmExecution({}, deadlines, { deadlines.now })
            module.wakeCheckpoint(alarm.occurrence, at, owner)
            owner.finishIfUnused()
            deadlines.advance(2000) // Main remains unable to execute the three post checks.
            runtime.reconcile("expired_confirmation")
        }
        assertTrue("Unobserved confirmation recovery must stop at its six-checkpoint ceiling", power.checkpoints.isEmpty())
        assertTrue(power.leases.isEmpty())
        delays.fire(evenCancelled = true)
        runtime.reconcile("late_callback")
        assertTrue(power.checkpoints.isEmpty())
    }

    @Test fun staleConfirmationFromSameOccurrenceCannotCancelItsNewAlarmReceipt() {
        val power = FakeWakePlatform()
        var visible = false
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? =
                if (visible) platform.activeOccurrence(campaign) else null
        }
        install(power, notifier = notifier)
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        val deadlines = ManualNotificationDeadlines()
        val firstOwner = NotificationAlarmExecution({}, deadlines, { deadlines.now })
        assertTrue(module.receiveAlarm(alarm, firstOwner) is NotificationOutcome.PostSubmitted)
        delays.fire(); delays.fire() // The old final active-post check is already queued.
        deadlines.advance(2000)
        runtime.reconcile("owner_expired")
        dispatchWake(power, alarm.occurrence)
        assertFalse(finishedReceipts.last().get())
        delays.fire(evenCancelled = true)
        assertFalse("An old final confirmation must not cancel the new receipt for the same occurrence", finishedReceipts.last().get())
        visible = true
        delays.fire()
        assertEquals(1, power.leases.size)
    }

    @Test fun foregroundAlarmDeliveryAndDirectReadinessNeverAcquireWithoutBackgroundAlarmOwnership() {
        val power = FakeWakePlatform()
        install(power)
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        NotificationAlarmReceiver().onReceive(app, Intent(app, NotificationAlarmReceiver::class.java)
            .setAction(ALARM_ACTION).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(ALARM_EXTRA, alarm.encode()))
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.leases.isEmpty())
        runtime.reconcile("foreign_callback")
        assertTrue(power.leases.isEmpty())
        dispatchWake(power, alarm.occurrence)
        assertEquals(1, power.leases.size)
    }

    @Test fun twoAttemptsSurviveColdUnknownThenFreeWithoutRepostingTheMessage() {
        val power = FakeWakePlatform()
        install(power)
        val alarm = send()
        assertEquals(1, power.leases.size)
        RetentionRuntime.uninstallForTests()
        clock.advance(31_000)
        power.interactiveState = false
        install(power, RetentionEntitlement.UNKNOWN)
        assertEquals(alarm.occurrence, platform.activeOccurrence(NotificationCampaign.LOCKSCREEN))
        assertEquals(1, power.leases.size)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals("Readiness enqueues a real alarm instead of acquiring in a foreign callback", 1, power.leases.size)
        dispatchWake(power, alarm.occurrence)
        assertEquals(2, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
        val second = power.leases.last()
        repeat(3) {
            clock.advance(31_000)
            power.screen(false)
            ownedCheckpoint(alarm.occurrence, clock.now - 31_000)
            runtime.reconcile("duplicate")
        }
        assertEquals(2, power.leases.size)
        RetentionRuntime.uninstallForTests()
        assertFalse(second.held)
        install(power)
        assertEquals(2, power.leases.size)
    }

    @Test fun coldUnknownToSubscriberCancelsPendingNotificationAndWake() {
        val power = FakeWakePlatform()
        install(power); send()
        RetentionRuntime.uninstallForTests()
        install(power, RetentionEntitlement.UNKNOWN)
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertFalse(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.checkpoints.isEmpty())
        assertTrue(power.leases.none { it.held })
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals(1, power.leases.size)
    }

    @Test fun channelDisabledBeforeRewakeReleasesAndCancelsOnlyOwnedResources() {
        val power = FakeWakePlatform()
        install(power); send()
        platform.block = "channel_blocked"
        runtime.reconcile("system_channel_changed")
        assertTrue(power.leases.none { it.held })
        assertTrue(power.checkpoints.isEmpty())
        clock.advance(31_000); power.screen(false)
        assertEquals(1, power.leases.size)
    }

    @Test fun foregroundAndSwipeBothReleaseWithoutResurrectingThePost() {
        val power = FakeWakePlatform()
        install(power)
        val alarm = send()
        platform.cancel(NotificationCampaign.LOCKSCREEN) // Android removes first, then deleteIntent arrives.
        module.dismiss(NotificationCampaign.LOCKSCREEN, alarm.occurrence)
        assertTrue(power.leases.none { it.held })
        assertTrue(power.checkpoints.isEmpty())
        runtime.signal(RetentionSignal.ProcessForeground)
        clock.advance(31_000); power.screen(false)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
        assertEquals(1, power.leases.size)
    }

    @Test fun acquireReentrancyCannotRetainALeaseAfterSubscriberRevokesIt() {
        val power = FakeWakePlatform()
        install(power)
        power.onAcquire = { runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER)) }
        send()
        assertEquals(1, power.leases.size)
        assertTrue(power.leases.none { it.held })
        assertFalse(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.checkpoints.isEmpty())
    }

    @Test fun staleScreenObserverAfterReplacementCannotSpendNewMessagesWake() {
        val power = FakeWakePlatform()
        install(power)
        val first = send()
        val stale = power.callbacks.single()
        runtime.updateConfig(mapOf("notifications.lockscreen.replace" to "true", "notifications.lockscreen.cooldown_ms" to "0", "notifications.guard_window_ms" to "0"))
        power.interactiveState = false
        assertTrue(inAlarm { module.deliver(NotificationCampaign.LOCKSCREEN, "replacement", runtime.config.revision,
            clock.now, clock.now + HOUR, wakeExecution = it) } is NotificationOutcome.PostSubmitted)
        assertEquals(2, power.leases.size)
        val replacement = power.leases.last()
        module.dismiss(NotificationCampaign.LOCKSCREEN, first.occurrence)
        assertTrue(replacement.held)
        clock.advance(31_000)
        power.interactiveState = false
        stale(false) // Delivery queued before old receiver was unregistered.
        assertEquals("An old observer must not spend the replacement's second attempt", 2, power.leases.size)
    }

    @Test fun failingWakeStatePersistenceNeverAcquiresAnUnbudgetedLease() {
        val delegate = store
        store = object : RetentionStore by delegate {
            override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
                if (namespace == "notifications.wake.v1") throw RetentionStorageException("Wake store unavailable")
                return delegate.transaction(namespace, block)
            }
        }
        val power = FakeWakePlatform()
        install(power); send()
        assertTrue(power.leases.isEmpty())
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
    }

    @Test fun legacyWakeSecondsAreMappedAndInvalidRemoteCannotWeakenTheHardDurationLimit() {
        install(FakeWakePlatform())
        assertTrue("noti_lockscreen_wake_seconds" in NotificationLegacyConfig.keys)
        val patch = NotificationLegacyConfig.map(mapOf("noti_lockscreen_wake_seconds" to "20")).overrides
        assertEquals("20000", patch["notifications.lockscreen.wake_duration_ms"])
        assertTrue(runtime.updateConfig(patch) is RetentionConfigResult.Applied)
        for (bad in listOf("0", "21", "-1", "NaN", "9223372036854775807")) {
            assertTrue(runtime.updateConfig(NotificationLegacyConfig.map(mapOf("noti_lockscreen_wake_seconds" to bad)).overrides) is RetentionConfigResult.Rejected)
        }
        assertEquals(20_000L, NotificationProfile.read(runtime.config).wakeDurationMillis)
    }

    @Test fun wakeDisabledOrUnsupportedDoesNotTurnPostSubmissionIntoFakeWakeSuccess() {
        val power = FakeWakePlatform()
        power.blockReason = "wake_level_unsupported"
        install(power); send()
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.leases.isEmpty())
        assertTrue(power.checkpoints.isEmpty())
        power.blockReason = null
        runtime.updateConfig(mapOf("notifications.lockscreen.wake.enabled" to "false"))
        power.screen(false)
        assertTrue(power.leases.isEmpty())
    }

    @Test fun screenAlreadyOnPostsNormallyAndWaitsForScreenOffBeforeFirstWake() {
        val power = FakeWakePlatform()
        power.interactiveState = true
        install(power); val alarm = send()
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.leases.isEmpty())
        power.screen(false)
        assertTrue("SCREEN_OFF returns without holding its foreground broadcast", power.leases.isEmpty())
        dispatchWake(power, alarm.occurrence)
        assertEquals(1, power.leases.size)
        assertEquals(20_000L, power.leases.single().duration)
    }

    @Test fun unknownCheckpointDeferralsAreBoundedAndLaterVerifiedFreeKeepsTheSameBudget() {
        val power = FakeWakePlatform()
        install(power)
        val alarm = send()
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.UNKNOWN))
        power.interactiveState = false
        repeat(7) {
            val at = power.checkpoints.getValue(alarm.occurrence)
            clock.advance((at - clock.now).coerceAtLeast(0))
            ownedCheckpoint(alarm.occurrence, at)
        }
        assertTrue(power.checkpoints.isEmpty())
        assertEquals(1, power.leases.size)
        assertTrue(power.leases.none { it.held })
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        dispatchWake(power, alarm.occurrence)
        assertEquals(2, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
    }

    @Test fun asynchronousPostConfirmationKeepsAlarmReceiptUntilMatchingActiveNotification() {
        val power = FakeWakePlatform()
        var visible = false
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? =
                if (visible) platform.activeOccurrence(campaign) else null
        }
        install(power, notifier = notifier)
        send()
        assertTrue("No screen wake before the matching active notification is observed", power.leases.isEmpty())
        assertFalse("The alarm receipt retains framework CPU/process coverage during confirmation", finishedReceipts.single().get())
        visible = true
        delays.fire()
        assertFalse(finishedReceipts.single().get())
        assertEquals(1, power.leases.size)
    }

    @Test fun unknownDuringActivePostConfirmationRecoversFirstWakeWhenVerifiedFreeWhileScreenStaysOff() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertTrue(power.leases.isEmpty())
        dispatchWake(power, alarm.occurrence)
        assertEquals("Owned alarm recovers the confirmed first wake without another SCREEN_OFF", 1, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
        runtime.reconcile("duplicate_readiness")
        assertEquals(1, power.leases.size)
    }

    /** Real module post/confirmation under UNKNOWN, not a manually manufactured wake ledger. */
    private fun pendingFirstWake(power: FakeWakePlatform): ScheduledNotification {
        var visible = false
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? =
                if (visible) platform.activeOccurrence(campaign) else null
        }
        install(power, notifier = notifier)
        val alarm = send()
        assertFalse(finishedReceipts.last().get())
        visible = true
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.UNKNOWN))
        assertTrue("UNKNOWN promptly finishes the alarm receipt", finishedReceipts.last().get())
        delays.fire(evenCancelled = true) // An already queued confirmation cannot acquire after revocation.
        assertEquals(alarm.occurrence, notifier.activeOccurrence(NotificationCampaign.LOCKSCREEN))
        assertTrue(finishedReceipts.all { it.get() })
        assertTrue(power.leases.isEmpty())
        assertFalse(power.interactive())
        return alarm
    }

    @Test fun confirmedPendingFirstWakeSurvivesColdUnknownAndBoundedCheckpointsWithSameTwoAttemptBudget() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        val initialCheckpoint = power.checkpoints.getValue(alarm.occurrence)
        assertTrue(initialCheckpoint > clock.now)
        RetentionRuntime.uninstallForTests()
        assertTrue(power.checkpoints.isEmpty())
        install(power, RetentionEntitlement.UNKNOWN)
        assertEquals(initialCheckpoint, power.checkpoints.getValue(alarm.occurrence))
        assertTrue(power.leases.isEmpty())
        repeat(7) {
            val at = power.checkpoints.getValue(alarm.occurrence)
            runtime.reconcile("duplicate_unknown")
            power.screen(false)
            assertEquals("Duplicate signals cannot postpone the same checkpoint", at, power.checkpoints.getValue(alarm.occurrence))
            clock.advance((at - clock.now).coerceAtLeast(0))
            ownedCheckpoint(alarm.occurrence, at)
        }
        assertTrue("Readiness alarms must stop after their bounded budget", power.checkpoints.isEmpty())
        assertTrue(power.leases.isEmpty())
        repeat(3) { runtime.reconcile("still_unknown"); power.screen(false) }
        assertTrue(power.checkpoints.isEmpty())
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        dispatchWake(power, alarm.occurrence)
        assertEquals(1, power.leases.size)
        clock.advance(31_000); power.screen(false)
        dispatchWake(power, alarm.occurrence)
        assertEquals(2, power.leases.size)
        RetentionRuntime.uninstallForTests()
        power.interactiveState = false
        install(power)
        power.screen(false)
        assertEquals(2, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
    }

    @Test fun coldPendingFirstWakeCannotUseAMissingOrDifferentActiveOccurrence() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        val at = power.checkpoints.getValue(alarm.occurrence)
        RetentionRuntime.uninstallForTests()
        // Android no longer reports our occurrence, even if an unrelated active post exists.
        val notifier = object : NotificationPlatform by platform {
            override fun activeOccurrence(campaign: NotificationCampaign): String? = "unrelated_occurrence"
        }
        install(power, RetentionEntitlement.UNKNOWN, notifier)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        clock.advance(at - clock.now)
        ownedCheckpoint(alarm.occurrence, at)
        power.screen(false)
        assertTrue(power.leases.isEmpty())
        assertTrue(power.checkpoints.isEmpty())
    }

    @Test fun pendingFirstWakeRechecksBlockedChannelAndNeverRevivesAfterReenable() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        val at = power.checkpoints.getValue(alarm.occurrence)
        platform.block = "channel_blocked"
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertTrue(power.leases.isEmpty())
        assertTrue(power.checkpoints.isEmpty())
        platform.block = null
        clock.advance(at - clock.now)
        ownedCheckpoint(alarm.occurrence, at)
        runtime.reconcile("channel_reenabled")
        power.screen(false)
        assertTrue(power.leases.isEmpty())
    }

    @Test fun subscriberCancelsUnspentFirstWakeBeforeAnyCheckpointOrReverification() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        val at = power.checkpoints.getValue(alarm.occurrence)
        RetentionRuntime.uninstallForTests()
        install(power, RetentionEntitlement.UNKNOWN)
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER))
        assertFalse(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.checkpoints.isEmpty())
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        clock.advance(at - clock.now)
        ownedCheckpoint(alarm.occurrence, at)
        power.screen(false)
        assertTrue(power.leases.isEmpty())
    }

    @Test fun dismissBeforeFirstWakeClosesOwnedWorkAndIgnoresAlreadyQueuedCheckpoint() {
        val power = FakeWakePlatform()
        val alarm = pendingFirstWake(power)
        val at = power.checkpoints.getValue(alarm.occurrence)
        platform.cancel(NotificationCampaign.LOCKSCREEN)
        module.dismiss(NotificationCampaign.LOCKSCREEN, alarm.occurrence)
        assertTrue(power.checkpoints.isEmpty())
        assertTrue(finishedReceipts.all { it.get() })
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        clock.advance(at - clock.now)
        ownedCheckpoint(alarm.occurrence, at)
        power.screen(false)
        assertTrue(power.leases.isEmpty())
    }

    @Test fun coldRestartWithoutAnExplicitlyDeferredFirstWakeCannotInventAWakeDecision() {
        val power = FakeWakePlatform()
        power.interactiveState = true
        install(power); send()
        assertTrue(power.leases.isEmpty())
        assertTrue(power.checkpoints.isEmpty())
        RetentionRuntime.uninstallForTests()
        power.interactiveState = false
        install(power)
        runtime.reconcile("ordinary_process_restore")
        assertTrue(power.leases.isEmpty())
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
    }
}

private class FakeWakePlatform : NotificationWakePlatform {
    class Lease(val duration: Long) : AutoCloseable {
        var held = true
        override fun close() { held = false }
    }
    var interactiveState = false
    var onAcquire: (() -> Unit)? = null
    var blockReason: String? = null
    val leases = mutableListOf<Lease>()
    val checkpoints = mutableMapOf<String, Long>()
    val callbacks = mutableListOf<(Boolean) -> Unit>()
    private var currentCallback: ((Boolean) -> Unit)? = null
    override fun interactive() = interactiveState
    override fun blocked(): String? = blockReason
    override fun acquire(durationMillis: Long): AutoCloseable {
        val lease = Lease(durationMillis); leases += lease
        interactiveState = true
        currentCallback?.invoke(true)
        onAcquire?.invoke()
        return lease
    }
    override fun observe(callback: (Boolean) -> Unit): AutoCloseable {
        currentCallback = callback; callbacks += callback
        return AutoCloseable { if (currentCallback === callback) currentCallback = null }
    }
    fun screen(interactive: Boolean) { interactiveState = interactive; currentCallback?.invoke(interactive) }
    override fun schedule(occurrence: String, atMillis: Long) { checkpoints[occurrence] = atMillis }
    override fun cancel(occurrence: String) { checkpoints.remove(occurrence) }
}
