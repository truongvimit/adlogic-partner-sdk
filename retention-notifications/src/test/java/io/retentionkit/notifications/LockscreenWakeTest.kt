package io.retentionkit.notifications

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
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

    private fun install(power: NotificationWakePlatform? = null, entitlement: RetentionEntitlement = RetentionEntitlement.NON_SUBSCRIBER) {
        module = RetentionNotifications(RetentionNotificationOptions(), platform, delays, power)
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), clock = clock, store = store,
            initialUserState = RetentionUserState(true, false, entitlement,
                clock.now, clock.now, clock.now),
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification)) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity")) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
    }

    private fun send(): ScheduledNotification {
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        assertTrue(module.receiveAlarm(alarm) is NotificationOutcome.PostSubmitted)
        return alarm
    }

    @Test fun defaultLockscreenPostRequestsBoundedWakeWithoutRequiringForegroundActivity() {
        install()
        val alarm = platform.scheduled.values.first { it.campaign == NotificationCampaign.LOCKSCREEN }
        clock.advance(alarm.due - clock.now + 1)
        assertTrue(module.receiveAlarm(alarm) is NotificationOutcome.PostSubmitted)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val lease = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("A default eligible screen-off lockscreen post must actually request wake", lease)
        assertTrue(lease.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertFalse("SDK lease must be released; this is not an assertion of physical screen-off", lease.isHeld)
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
        assertEquals(2, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
        val second = power.leases.last()
        repeat(3) {
            clock.advance(31_000)
            power.screen(false)
            module.wakeCheckpoint(alarm.occurrence, clock.now - 31_000)
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
        assertTrue(module.deliver(NotificationCampaign.LOCKSCREEN, "replacement", runtime.config.revision,
            clock.now, clock.now + HOUR) is NotificationOutcome.PostSubmitted)
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
        install(power); send()
        assertTrue(platform.active(NotificationCampaign.LOCKSCREEN))
        assertTrue(power.leases.isEmpty())
        power.screen(false)
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
            module.wakeCheckpoint(alarm.occurrence, at)
        }
        assertTrue(power.checkpoints.isEmpty())
        assertEquals(1, power.leases.size)
        assertTrue(power.leases.none { it.held })
        runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.NON_SUBSCRIBER))
        assertEquals(2, power.leases.size)
        assertEquals(1, platform.posts.count { it.first == NotificationCampaign.LOCKSCREEN })
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
