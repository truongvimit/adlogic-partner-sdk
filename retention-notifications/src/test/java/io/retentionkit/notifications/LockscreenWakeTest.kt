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

    private fun install() {
        module = RetentionNotifications(RetentionNotificationOptions(), platform, FakeDelays())
        val result = RetentionRuntime.install(app, RetentionOptions(modules = listOf(module), clock = clock, store = store,
            initialUserState = RetentionUserState(true, false, RetentionEntitlement.NON_SUBSCRIBER,
                clock.now, clock.now, clock.now),
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification)) },
            router = RetentionRouter { _, _ -> Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity")) }))
        assertTrue(result.toString(), result is RetentionInstallResult.Installed)
        runtime = (result as RetentionInstallResult.Installed).runtime
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
}
