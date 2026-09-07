package io.retentionkit.notifications

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** Rendered PendingIntents cross real core capture/setup/UI/consumption; only time/OS delivery vary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRouteLifetimeTest {
    class DestinationActivity : Activity()
    private lateinit var app: Application
    private lateinit var runtime: RetentionRuntime
    private lateinit var module: RetentionNotifications
    private lateinit var platform: FakePlatform
    private val clock = TestClock()

    @Before fun before() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext()
        platform = FakePlatform()
        module = RetentionNotifications(RetentionNotificationOptions(), platform, FakeDelays())
        runtime = (RetentionRuntime.install(app, RetentionOptions(
            modules = listOf(module), clock = clock,
            store = SharedPreferencesRetentionStore(app, "route_lifetime_${UUID.randomUUID()}"),
            initialUserState = RetentionUserState(false, true, RetentionEntitlement.NON_SUBSCRIBER,
                clock.now - 2 * DAY, clock.now - DAY, 0),
            featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", R.drawable.rk_ic_notification)) },
            router = RetentionSplashRouter(DestinationActivity::class.java),
        )) as RetentionInstallResult.Installed).runtime
    }
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun acceptedOnboardingBodyAndCtaSurviveDisplayExpiryUntilSetupCompletes() {
        val postedAt = clock.now
        val expires = postedAt + 5 * MINUTE
        assertTrue(module.deliver(NotificationCampaign.ONBOARDING, "onboarding:long_setup", runtime.config.revision,
            postedAt, expires) is NotificationOutcome.PostSubmitted)
        val notification = platform.posts.single().second
        assertEquals(5 * MINUTE, notification.timeoutAfter)
        clock.advance(MINUTE) // User taps while the notification is still within its display TTL.
        val body = runtime.entries.capture(Intent(shadowOf(notification.contentIntent).savedIntent)) as RetentionEntryAcceptance.Accepted
        val cta = runtime.entries.capture(Intent(shadowOf(notification.actions.single().actionIntent).savedIntent)) as RetentionEntryAcceptance.Accepted
        clock.advance(10 * MINUTE) // Real setup may outlive the source notification's display.
        runtime.signal(RetentionSignal.SetupCompleted)
        runtime.signal(RetentionSignal.ProcessForeground)
        val host = Robolectric.buildActivity(DestinationActivity::class.java).setup().visible()
        try {
            assertTrue(runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Allowed)
            assertEquals(body.entry, runtime.entries.pending(body.entry.token))
            assertEquals(cta.entry, runtime.entries.pending(cta.entry.token))
            assertTrue("A ready host can claim the originally accepted destination", runtime.entries.consume(body.entry.token))
            assertFalse(runtime.entries.consume(body.entry.token))
            clock.advance(7 * DAY)
            assertNull("The unconsumed route still has core's bounded retention", runtime.entries.pending(cta.entry.token))
        } finally { host.pause().stop().destroy() }
    }

    @Test fun pinnedTemplateMaterializesFreshRouteAfterItsOldDisplayDeadline() {
        runtime.signal(RetentionSignal.SetupCompleted)
        clock.advance(DAY)
        runtime.signal(RetentionSignal.ProcessForeground)
        val notification = platform.posts.single { it.first == NotificationCampaign.PINNED }.second
        val template = Intent(shadowOf(notification.actions.single().actionIntent).savedIntent)
        clock.advance(8 * DAY) // A reusable template cannot inherit an old one-shot route deadline.
        val first = runtime.entries.capture(Intent(template))
        assertTrue(first.toString(), first is RetentionEntryAcceptance.Accepted)
        val accepted = (first as RetentionEntryAcceptance.Accepted).entry
        assertEquals(RetentionEntryMode.ONCE, accepted.mode)
        assertTrue(runtime.entries.consume(accepted.token))
        val second = runtime.entries.capture(Intent(template)) as RetentionEntryAcceptance.Accepted
        assertNotEquals(accepted.token, second.entry.token)
        assertTrue(runtime.entries.consume(second.entry.token))
    }

    @Test fun elapsedDeliveryDeadlineStillRejectsPostingWithoutCreatingAnEntry() {
        val due = clock.now
        clock.advance(5 * MINUTE)
        assertEquals(NotificationOutcome.Skipped("expired"), module.deliver(NotificationCampaign.ONBOARDING,
            "onboarding:expired", runtime.config.revision, due, due + 5 * MINUTE))
        assertTrue(platform.posts.isEmpty())
        assertTrue(runtime.entries.pending().isEmpty())
    }
}
