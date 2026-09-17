package com.ads.module.ump

import android.app.Activity
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Restored Play update API. Own one instance per Activity; AndroidX owners clean up automatically.
 * Call onResume() for a plain Activity and forward onActivityResult() when result callbacks matter.
 * This class launches Play UI; use ForceUpdateGate to also block navigation after cancellation.
 */
@MainThread
class ITGUpdateManager internal constructor(
    val activity: Activity,
    val requestCode: Int,
    val iUpdateInstanceCallback: IUpdateInstanceCallback,
    private val manager: AppUpdateManager,
) : DefaultLifecycleObserver {
    constructor(activity: Activity, requestCode: Int, iUpdateInstanceCallback: IUpdateInstanceCallback) :
        this(activity, requestCode, iUpdateInstanceCallback, AppUpdateManagerFactory.create(activity))

    private var disposed = false
    private var checking = false
    private var fullCheckRequested = false
    private var completing = false
    private val listener = InstallStateUpdatedListener { state ->
        if (!disposed && state.installStatus() == InstallStatus.DOWNLOADED) completeUpdate()
    }

    init {
        manager.registerListener(listener)
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
    }

    fun checkUpdateAvailable(): ITGUpdateManager {
        check(resumeOnly = false)
        return this
    }

    fun onResume() { check(resumeOnly = true) }
    override fun onResume(owner: LifecycleOwner) = onResume()
    override fun onDestroy(owner: LifecycleOwner) = dispose()

    private fun check(resumeOnly: Boolean) {
        if (!isAlive()) return
        if (!resumeOnly) fullCheckRequested = true
        if (checking) return
        checking = true
        manager.appUpdateInfo.addOnSuccessListener { info ->
            checking = false
            if (!isAlive()) return@addOnSuccessListener
            val shouldOffer = fullCheckRequested
            fullCheckRequested = false
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> completeUpdate()
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    start(info, AppUpdateType.IMMEDIATE)
                shouldOffer && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE -> {
                    val type = iUpdateInstanceCallback.updateAvailableListener(info)
                    if (type == AppUpdateType.IMMEDIATE || type == AppUpdateType.FLEXIBLE) start(info, type)
                    else iUpdateInstanceCallback.onUpdateUnavailable()
                }
                shouldOffer -> iUpdateInstanceCallback.onUpdateUnavailable()
            }
        }.addOnFailureListener { error ->
            checking = false
            val shouldReport = fullCheckRequested
            fullCheckRequested = false
            if (isAlive() && shouldReport) iUpdateInstanceCallback.onUpdateFailed(error)
        }
    }

    private fun start(info: AppUpdateInfo, type: Int) {
        if (!info.isUpdateTypeAllowed(type)) {
            iUpdateInstanceCallback.onUpdateUnavailable()
            return
        }
        try {
            if (manager.startUpdateFlowForResult(
                    info, activity, AppUpdateOptions.newBuilder(type).build(), requestCode,
                )) iUpdateInstanceCallback.onUpdateStarted()
            else iUpdateInstanceCallback.onUpdateUnavailable()
        } catch (error: Exception) {
            iUpdateInstanceCallback.onUpdateFailed(error)
        }
    }

    private fun completeUpdate() {
        if (completing || !isAlive()) return
        completing = true
        manager.completeUpdate().addOnFailureListener { error ->
            completing = false
            if (isAlive()) iUpdateInstanceCallback.onUpdateFailed(error)
        }
    }

    /** Returns whether this result belongs to the manager; cancellation never grants app access. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != this.requestCode || disposed) return false
        iUpdateInstanceCallback.onUpdateResult(resultCode)
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        manager.unregisterListener(listener)
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
    }

    private fun isAlive() = !disposed && !activity.isFinishing && !activity.isDestroyed
}
