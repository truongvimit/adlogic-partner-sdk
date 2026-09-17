package com.ads.module.ump

import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.AppUpdateType

/** Play update callbacks. Consent now belongs to ConsentCenter, independently of app updates. */
interface IUpdateInstanceCallback {
    /** Return IMMEDIATE, FLEXIBLE, or -1 to skip this update. */
    fun updateAvailableListener(updateAvailability: AppUpdateInfo): Int = AppUpdateType.FLEXIBLE
    fun onUpdateUnavailable() {}
    fun onUpdateStarted() {}
    fun onUpdateFailed(error: Exception) {}
    fun onUpdateResult(resultCode: Int) {}
}
