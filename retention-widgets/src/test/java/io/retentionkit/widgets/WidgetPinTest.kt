package io.retentionkit.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPinTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun requestUsesLeaseThenHandsOffAndDeduplicatesWithoutInventingSuccess() {
        val f = Fixture()
        f.foreground()
        var observedTransition = false
        f.runtime.subscribe("test") { if (it is RetentionSignal.ExternalTransitionStarted) {
            observedTransition = true
            assertTrue(f.runtime.ui.eligibility() is RetentionEligibility.Blocked)
        } }
        val request = f.module.requestPin() as WidgetPinResult.Requested
        assertTrue(observedTransition)
        assertEquals(1, f.platform.requests)
        assertEquals(WidgetPinResult.Duplicate(request.token), f.module.requestPin())
        assertEquals(0, f.events.count { it.name == "retention_widget_pin_confirmed" })
        assertEquals(1, f.events.count { it.name == "retention_widget_pin_requested" })
    }

    @Test fun mutableExplicitCallbackCarriesLauncherIdAndConfirmsOnlyOnce() {
        val f = Fixture()
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        val pending = requireNotNull(f.platform.callback)
        val base = shadowOf(pending).savedIntent
        assertEquals(ComponentName(f.app, WidgetPinReceiver::class.java), base.component)
        assertEquals(WidgetPinCoordinator.CALLBACK_ACTION, base.action)
        assertFalse(pending.isImmutable)
        f.installWidget(77)
        pending.send(f.app, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 77))
        shadowOf(Looper.getMainLooper()).idle()
        val delivered = shadowOf(f.app).broadcastIntents.last { it.action == WidgetPinCoordinator.CALLBACK_ACTION }
        assertEquals(77, delivered.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        // Exercise the real manifest receiver using exactly the delivered fill-in intent.
        WidgetPinReceiver().onReceive(f.app, delivered)
        WidgetPinReceiver().onReceive(f.app, delivered)
        assertEquals(WidgetPinResult.Confirmed(request.token, 77), f.module.pinStatus(request.token))
        assertEquals(1, f.events.count { it.name == "retention_widget_pin_confirmed" })
        assertTrue(f.platform.rendered.containsKey(77))
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
    }

    @Test fun callbackSurvivesProcessReinstallWithNoRetainedActivityOrCallback() {
        val before = Fixture()
        before.foreground()
        val request = before.module.requestPin() as WidgetPinResult.Requested
        val callback = Intent(shadowOf(requireNotNull(before.platform.callback)).savedIntent)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 81)
        RetentionRuntime.uninstallForTests()
        val after = Fixture(clock = before.clock, platform = before.platform, store = before.store)
        after.installWidget(81)
        WidgetPinReceiver().onReceive(after.app, callback)
        assertEquals(WidgetPinResult.Confirmed(request.token, 81), after.module.pinStatus(request.token))
        assertNull(after.runtime.activities.current())
        assertEquals(1, after.events.count { it.name == "retention_widget_pin_confirmed" })
    }

    @Test fun callbackRejectsUnknownTokenMissingIdOtherProviderAndExistingInstance() {
        val f = Fixture()
        f.installWidget(12)
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        val callback = Intent(shadowOf(requireNotNull(f.platform.callback)).savedIntent)
        WidgetPinReceiver().onReceive(f.app, callback)
        WidgetPinReceiver().onReceive(f.app, Intent(callback).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 12))
        f.platform.providers[22] = ComponentName(f.app.packageName, "OtherWidget")
        WidgetPinReceiver().onReceive(f.app, Intent(callback).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 22))
        f.module.receivePin("unknown", 12)
        assertEquals(WidgetPinResult.Requested(request.token), f.module.pinStatus(request.token))
        assertTrue(f.events.none { it.name == "retention_widget_pin_confirmed" })
    }

    @Test fun timeoutIsUnknownAndLateVerifiedCallbackMayUpgradeWhileOtherSuppressionSurvives() {
        val f = Fixture(initialOverrides = mapOf("widgets.pin.timeout_ms" to "1000"))
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        f.runtime.signal(RetentionSignal.HostUiChanged("paywall", true, 10_000))
        f.clock.advance(1001)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        assertEquals(WidgetPinResult.Unknown(request.token, "timeout"), f.module.pinStatus(request.token))
        assertEquals(RetentionSuppressionReason.HOST_UI, (f.runtime.ui.eligibility() as RetentionEligibility.Blocked).reason)
        f.installWidget(33)
        f.module.receivePin(request.token, 33)
        assertEquals(WidgetPinResult.Confirmed(request.token, 33), f.module.pinStatus(request.token))
        assertEquals(RetentionSuppressionReason.HOST_UI, (f.runtime.ui.eligibility() as RetentionEligibility.Blocked).reason)
    }

    @Test fun processReturnWithoutCallbackIsUnknownNotCancelledAndReleasesOnlyOwnToken() {
        val f = Fixture()
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        f.runtime.signal(RetentionSignal.ProcessBackground)
        f.runtime.signal(RetentionSignal.ExternalTransitionStarted("billing", "billing", 10_000))
        f.runtime.signal(RetentionSignal.ProcessForeground)
        assertEquals(WidgetPinResult.Unknown(request.token, "returned_without_callback"), f.module.pinStatus(request.token))
        assertTrue(f.runtime.ui.eligibility() is RetentionEligibility.Blocked)
        f.runtime.signal(RetentionSignal.ExternalTransitionFinished("billing"))
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
    }

    @Test fun unsupportedAndThrownSystemCallDoNotLeaveUiLeaseOrPretendRequested() {
        val f = Fixture()
        f.foreground()
        f.platform.supported = false
        assertTrue(f.module.requestPin() is WidgetPinResult.Unavailable)
        assertEquals(0, f.platform.requests)
        f.platform.supported = true
        f.platform.accept = false
        assertTrue(f.module.requestPin() is WidgetPinResult.Unavailable)
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
        f.platform.accept = true
        f.platform.fail = true
        assertTrue(f.module.requestPin() is WidgetPinResult.Failed)
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
        assertTrue(f.events.none { it.name == "retention_widget_pin_requested" })
    }

    @Test fun setupUiBackgroundAndStaleConfigPreventSystemCall() {
        val f = Fixture()
        assertTrue(f.module.requestPin() is WidgetPinResult.Blocked)
        f.foreground()
        f.runtime.signal(RetentionSignal.OnboardingChanged(true))
        assertEquals(WidgetPinResult.Blocked(RetentionSuppressionReason.HOST_UI), f.module.requestPin())
        f.runtime.signal(RetentionSignal.OnboardingChanged(false))
        f.runtime.subscribe("disable_on_handoff") {
            if (it is RetentionSignal.ExternalTransitionStarted) f.runtime.updateConfig(mapOf("widgets.enabled" to "false"))
        }
        assertTrue(f.module.requestPin() is WidgetPinResult.Failed)
        assertEquals(0, f.platform.requests)
    }

    @Test fun pinCallbackCannotBeReattributedToDifferentNewWidgetAfterConfirmation() {
        val f = Fixture()
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        f.installWidget(1); f.installWidget(2)
        f.module.receivePin(request.token, 1)
        f.module.receivePin(request.token, 2)
        assertEquals(WidgetPinResult.Confirmed(request.token, 1), f.module.pinStatus(request.token))
    }

    @Test @Config(sdk = [26]) fun api26CallbackUsesLegacyMutationAndStillCarriesVerifiedWidgetId() {
        val f = Fixture()
        f.foreground()
        val request = f.module.requestPin() as WidgetPinResult.Requested
        f.installWidget(26)
        requireNotNull(f.platform.callback).send(f.app, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 26))
        shadowOf(Looper.getMainLooper()).idle()
        val delivered = shadowOf(f.app).broadcastIntents.last { it.action == WidgetPinCoordinator.CALLBACK_ACTION }
        assertEquals(26, delivered.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        WidgetPinReceiver().onReceive(f.app, delivered)
        assertEquals(WidgetPinResult.Confirmed(request.token, 26), f.module.pinStatus(request.token))
    }
}
