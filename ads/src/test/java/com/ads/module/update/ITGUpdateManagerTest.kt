package com.ads.module.update

import android.app.Activity
import android.os.Looper
import com.ads.module.ump.ITGUpdateManager
import com.ads.module.ump.IUpdateInstanceCallback
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ITGUpdateManagerTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val play = mock(AppUpdateManager::class.java)
    private val info = mock(AppUpdateInfo::class.java)
    private var unavailable = 0
    private var failures = 0
    private var started = 0
    private val callback = object : IUpdateInstanceCallback {
        override fun updateAvailableListener(updateAvailability: AppUpdateInfo) = AppUpdateType.IMMEDIATE
        override fun onUpdateUnavailable() { unavailable++ }
        override fun onUpdateFailed(error: Exception) { failures++ }
        override fun onUpdateStarted() { started++ }
    }
    private fun manager(availability: Int, allowed: Boolean = true): ITGUpdateManager {
        `when`(info.updateAvailability()).thenReturn(availability)
        `when`(info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)).thenReturn(allowed)
        `when`(play.appUpdateInfo).thenReturn(Tasks.forResult(info))
        `when`(play.startUpdateFlowForResult(eq(info), eq(activity), any(AppUpdateOptions::class.java), eq(123)))
            .thenReturn(true)
        return ITGUpdateManager(activity, 123, callback, play)
    }
    @Test fun `available and allowed starts Play flow`() {
        val updates = manager(UpdateAvailability.UPDATE_AVAILABLE)
        updates.checkUpdateAvailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, started)
        assertEquals(0, unavailable)
        updates.dispose()
    }
    @Test fun `unavailable or disallowed gives host fallback`() {
        val updates = manager(UpdateAvailability.UPDATE_AVAILABLE, allowed = false)
        updates.checkUpdateAvailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, unavailable)
        assertEquals(0, started)
        `when`(info.updateAvailability()).thenReturn(UpdateAvailability.UPDATE_NOT_AVAILABLE)
        updates.checkUpdateAvailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, unavailable)
        updates.dispose()
    }
    @Test fun `resume continues immediate update without asking policy again`() {
        val updates = manager(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
        updates.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, started)
        updates.dispose()
    }
    @Test fun `resume completes a downloaded flexible update`() {
        val updates = manager(UpdateAvailability.UPDATE_AVAILABLE)
        `when`(info.installStatus()).thenReturn(InstallStatus.DOWNLOADED)
        `when`(play.completeUpdate()).thenReturn(Tasks.forResult(null))
        updates.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        verify(play).completeUpdate()
        assertEquals(0, started)
        updates.dispose()
    }
    @Test fun `failed check reports failure and disposed manager ignores late result`() {
        val updates = manager(UpdateAvailability.UPDATE_AVAILABLE)
        `when`(play.appUpdateInfo).thenReturn(Tasks.forException(IllegalStateException("offline")))
        updates.checkUpdateAvailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures)
        val pending = TaskCompletionSource<AppUpdateInfo>()
        `when`(play.appUpdateInfo).thenReturn(pending.task)
        updates.checkUpdateAvailable()
        updates.dispose()
        updates.dispose()
        pending.setResult(info)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, started)
        verify(play, times(1)).registerListener(any(InstallStateUpdatedListener::class.java))
        verify(play, times(1)).unregisterListener(any(InstallStateUpdatedListener::class.java))
    }
}
