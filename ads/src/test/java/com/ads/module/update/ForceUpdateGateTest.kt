package com.ads.module.update

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ForceUpdateGateTest {
    @Test fun `mandatory gate stays closed after back and store return until scope dies`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var proceeded = false
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            ForceUpdateGate.await(activity, ForceUpdateConfig(enabled = true, minVersionCode = Long.MAX_VALUE,
                force = true, storeLink = "https://play.google.com/store/apps/details?id=test"))
            proceeded = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(com.ads.module.helper.AdGate.areRequestsHeld())
        assertFalse(proceeded)
        dialog.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertTrue(com.ads.module.helper.AdGate.areRequestsHeld())
        assertFalse(proceeded)
        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertFalse(com.ads.module.helper.AdGate.areRequestsHeld())
        assertFalse(proceeded)
    }
    @Test fun `optional gate lets later continue`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var proceeded = false
        CoroutineScope(Dispatchers.Main.immediate).launch {
            ForceUpdateGate.await(activity, ForceUpdateConfig(enabled = true, minVersionCode = Long.MAX_VALUE))
            proceeded = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(proceeded)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(proceeded)
        assertFalse(dialog.isShowing)
    }
    @Test fun `force with disabled threshold never suspends navigation`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var proceeded = false
        CoroutineScope(Dispatchers.Main.immediate).launch {
            ForceUpdateGate.await(activity, ForceUpdateConfig(enabled = true, force = true))
            proceeded = true
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(proceeded)
    }
}
