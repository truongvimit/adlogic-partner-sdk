package io.retentionkit.widgets

import android.content.DialogInterface
import android.os.Looper
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetInvitationTest {
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun negativeDismissIsKnownAndDoesNotStartSystemPin() {
        val f = Fixture()
        f.foreground()
        assertEquals(WidgetInvitationResult.Shown, f.module.showPinInvitation())
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, f.platform.requests)
        assertEquals(1, f.events.count { it.name == "retention_widget_invitation_dismissed" })
        assertEquals(RetentionEligibility.Allowed, f.runtime.ui.eligibility())
    }

    @Test fun positiveUsesExistingLeaseAndRevocationClosesDialogBeforeLaterClick() {
        val f = Fixture()
        f.foreground()
        f.module.showPinInvitation()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, f.platform.requests)
        f.runtime.signal(RetentionSignal.ProcessBackground)
        f.runtime.signal(RetentionSignal.ProcessForeground)
        f.module.showPinInvitation()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        f.runtime.signal(RetentionSignal.HostUiChanged("billing", true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251))
        assertFalse(dialog.isShowing)
        assertEquals(1, f.platform.requests)
    }

    @Test fun activityPauseClosesInvitationAndExpiredLeaseCannotOpenSystemUi() {
        val f = Fixture()
        val controller = f.foreground()
        f.module.showPinInvitation()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        controller.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251))
        assertFalse(dialog.isShowing)
        assertEquals(0, f.platform.requests)
        controller.stop().destroy()
    }
    @Test fun disablingOnlyInvitationClosesVisiblePromptButPreservesExistingWidgetAndDirectPin() {
        val f = Fixture()
        f.foreground()
        f.installWidget(17)
        f.module.update(intArrayOf(17))
        assertEquals(WidgetInvitationResult.Shown, f.module.showPinInvitation())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        f.runtime.updateConfig(mapOf("widgets.invitation.enabled" to "false"))
        assertFalse(dialog.isShowing)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertEquals(0, f.platform.requests)
        assertEquals(WidgetInvitationResult.Unavailable("invitation_disabled"), f.module.showPinInvitation())
        assertEquals(listOf(17), f.module.instances().map { it.appWidgetId })
        assertEquals(RetentionCapability.Available, f.module.pinCapability())
        assertTrue(f.module.requestPin() is WidgetPinResult.Requested)
        assertEquals(1, f.platform.requests)
    }

}
