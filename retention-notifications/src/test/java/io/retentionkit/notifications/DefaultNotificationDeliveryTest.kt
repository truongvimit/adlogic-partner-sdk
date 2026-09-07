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
}
