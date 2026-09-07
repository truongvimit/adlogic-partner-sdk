package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import io.retentionkit.feedback.RetentionFeedbackModule
import io.retentionkit.integration.TrackkitRetentionEventSink
import io.trackkit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionFacadeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @After fun after() { RetentionRuntime.uninstallForTests(); Tracker.resetForTesting() }
    private fun options() = RetentionKitOptions(
        featureProvider = RetentionFeatureProvider { listOf(RetentionFeature("notes", "Notes", android.R.drawable.ic_menu_search)) },
        router = RetentionRouter { context, _ -> Intent(context, Activity::class.java) },
        store = SharedPreferencesRetentionStore(app, "facade_${UUID.randomUUID()}"),
    )
    private fun install(options: RetentionKitOptions = options()): RetentionKit =
        (RetentionKit.install(app, options) as RetentionKitInstallResult.Installed).kit

    @Test fun minimalDefaultsInstallAllModulesAndRepeatedInstallReusesRuntime() {
        val kit = install()
        assertNotNull(kit.notifications); assertNotNull(kit.widgets); assertNotNull(kit.feedback); assertNotNull(kit.review)
        assertEquals(RetentionEntitlement.UNKNOWN, kit.runtime.userState.entitlement)
        val reused = RetentionKit.install(app, options()) as RetentionKitInstallResult.Installed
        assertTrue(reused.reused)
        assertSame(kit, reused.kit)
        assertSame(kit, RetentionKit.get())
    }

    @Test fun featureEntrySurvivesPassthroughAndConsumesOnceAcrossWarmCaptureAndProcessRestore() {
        val configured = options().copy(notifications = null, widgets = null, feedback = null, review = null)
        val kit = install(configured)
        val entry = RetentionEntry(RetentionEntrySource.WIDGET, "notes", "notes", instanceId = "5", mode = RetentionEntryMode.REUSABLE)
        val original = requireNotNull(kit.runtime.createEntryIntent(entry))
        val first = kit.capture(Intent(original)) as RetentionEntryAcceptance.Accepted
        // The actual host forwards the rewritten launch extras, not the reusable OS template.
        val launch = Intent(original)
        val captured = kit.capture(launch) as RetentionEntryAcceptance.Accepted
        val forwarded = Intent(app, Activity::class.java).putExtras(Bundle(launch.extras))
        assertEquals(captured.entry, (kit.capture(forwarded) as RetentionEntryAcceptance.Accepted).entry)
        RetentionRuntime.uninstallForTests()
        val restored = install(configured)
        restored.setupCompleted()
        Robolectric.buildActivity(Activity::class.java).setup()
        restored.runtime.signal(RetentionSignal.ProcessForeground)
        val route = restored.dispatchPending(captured.entry.token) as RetentionDispatchResult.Navigate
        assertEquals("notes", route.entry.destination)
        assertTrue(restored.dispatchPending(captured.entry.token) is RetentionDispatchResult.Unavailable)
        val warm = restored.capture(Intent(original)) as RetentionEntryAcceptance.Accepted
        assertNotEquals(first.entry.token, warm.entry.token)
        assertTrue(restored.dispatchPending(warm.entry.token) is RetentionDispatchResult.Navigate)
    }

    @Test fun internalFeedbackIsNeverConsumedAsHostFeatureAndBlockedRouteStaysPending() {
        val kit = install()
        val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, RetentionFeedbackModule.DESTINATION, "open")
        assertTrue(kit.runtime.entries.stage(entry) is RetentionEntryAcceptance.Accepted)
        assertNull(kit.consume(entry.token))
        assertTrue(kit.dispatchPending(entry.token) is RetentionDispatchResult.Unavailable)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(kit.runtime.entries.pending(entry.token)) // no resumed Activity, so SDK gate blocked
        assertTrue(kit.runtime.entries.pending().any { it.destination == RetentionFeedbackModule.DESTINATION })
    }

    @Test fun moduleTogglesAndInvalidConfigurationFailWithoutPartialInstall() {
        val kit = install(options().copy(notifications = null, widgets = null, feedback = null, review = null))
        assertNull(kit.notifications); assertNull(kit.widgets); assertNull(kit.feedback); assertNull(kit.review)
        RetentionRuntime.uninstallForTests()
        assertTrue(RetentionKit.install(app, options().copy(initialOverrides = mapOf("widgets.enabled" to "perhaps"))) is RetentionKitInstallResult.Failed)
        assertNull(RetentionKit.get())
        assertNull(RetentionRuntime.get())
    }

    @Test fun trackedEventsUseExistingTrackerAndSinkFailureCannotBreakCoreSignals() {
        val seen = mutableListOf<String>()
        Tracker.install(app, TrackerConfig(strictValidation = true))
        Tracker.addSink(object : TrackSink {
            override val id = "test"
            override fun onEvent(name: String, params: Map<String, Any?>) { seen.add(name) }
        })
        val kit = install(options().copy(eventSink = TrackkitRetentionEventSink()))
        kit.runtime.emit(RetentionEvent("retention_widget_pin_requested", mapOf("token" to "test")))
        assertTrue(seen.contains("retention_widget_pin_requested"))
        kit.runtime.emit(RetentionEvent("invalid event name"))
        assertTrue(kit.runtime.diagnostics.snapshot().any { it.component == "event_sink" })
        kit.setupCompleted()
        kit.entitlementChanged(RetentionEntitlement.NON_SUBSCRIBER)
        assertTrue(kit.runtime.userState.setupCompleted)
    }
    @Test fun finalFeatureDispatchUsesExplicitEntryPurposeEvenWhenAutomaticPromptsAreBlocked() {
        val kit = install(options().copy(notifications = null, widgets = null, feedback = null, review = null,
            uiHost = object : RetentionUiHost {
                override fun canPresent(activity: Activity) = false
                override fun canPresentEntry(activity: Activity) = true
            }))
        kit.setupCompleted()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        val entry = RetentionEntry(RetentionEntrySource.WIDGET, "notes", "open")
        kit.capture(RetentionEntryCodec.write(Intent(), entry))
        assertTrue(kit.runtime.ui.eligibility() is RetentionEligibility.Blocked)
        assertTrue(kit.dispatchPending(entry.token) is RetentionDispatchResult.Navigate)
        assertNull(kit.runtime.entries.pending(entry.token))
        activity.pause().stop().destroy()
    }

    @Test fun finalEntryGateRevocationKeepsSelectedTokenPending() {
        var reads = 0
        val kit = install(options().copy(notifications = null, widgets = null, feedback = null, review = null,
            uiHost = object : RetentionUiHost {
                override fun canPresent(activity: Activity) = true
                override fun canPresentEntry(activity: Activity) = ++reads == 1
            }))
        kit.setupCompleted()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        kit.runtime.signal(RetentionSignal.ProcessForeground)
        val entry = RetentionEntry(RetentionEntrySource.WIDGET, "notes", "open")
        kit.capture(RetentionEntryCodec.write(Intent(), entry))
        assertTrue(kit.dispatchPending(entry.token) is RetentionDispatchResult.Unavailable)
        assertEquals(2, reads)
        assertNotNull(kit.runtime.entries.pending(entry.token))
        activity.pause().stop().destroy()
    }

}
