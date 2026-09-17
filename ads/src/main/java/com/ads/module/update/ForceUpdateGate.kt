package com.ads.module.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import com.ads.module.R
import com.ads.module.admob.AppOpenManager
import com.ads.module.ump.ITGUpdateManager
import com.ads.module.ump.IUpdateInstanceCallback
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.AppUpdateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Startup navigation barrier. Await from lifecycleScope, before navigating or showing ads. */
object ForceUpdateGate {
    /** Reserved only while the gate owns this Activity. */
    const val REQUEST_CODE = 0xAD10

    @JvmStatic
    suspend fun await(activity: Activity, config: ForceUpdateConfig) = withContext(Dispatchers.Main.immediate) {
        if (!config.enabled || config.minVersionCode <= 0) return@withContext
        val installed = PackageInfoCompat.getLongVersionCode(
            activity.packageManager.getPackageInfo(activity.packageName, 0),
        )
        if (!config.needsUpdate(installed)) return@withContext
        if (activity.isFinishing || activity.isDestroyed) throw kotlinx.coroutines.CancellationException("Host destroyed")
        suspendCancellableCoroutine<Unit> { continuation ->
            val required = config.isRequired(installed)
            val dialog = AlertDialog.Builder(activity)
                .setTitle(config.title.ifBlank { activity.getString(R.string.adlogic_update_title) })
                .setMessage(config.description.ifBlank { activity.getString(R.string.adlogic_update_message) })
                .setCancelable(!required)
                .setPositiveButton(R.string.adlogic_update_now, null)
                .apply {
                    if (!required) setNegativeButton(R.string.adlogic_update_later) { _, _ -> }
                }
                .create()
            var updateManager: ITGUpdateManager? = null
            fun release() { updateManager?.dispose(); updateManager = null }
            fun openStore() {
                if (!continuation.isActive || activity.isFinishing || activity.isDestroyed) return
                val defaultUrl = "https://play.google.com/store/apps/details?id=${activity.packageName}"
                val configured = Uri.parse(config.storeLink)
                val link = config.storeLink.takeIf {
                    (configured.scheme == "https" && !configured.host.isNullOrBlank()) ||
                        (configured.scheme == "market" && configured.host == "details")
                } ?: defaultUrl
                AppOpenManager.getInstance().skipNextResume("app_update")
                val opened = runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }.isSuccess ||
                    (link != defaultUrl && runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(defaultUrl)))
                    }.isSuccess)
                if (!opened) Toast.makeText(activity, R.string.adlogic_update_store_unavailable, Toast.LENGTH_LONG).show()
                // Never dismiss a mandatory gate merely because an external Activity opened.
            }
            dialog.setOnDismissListener {
                release()
                if (!required && continuation.isActive) continuation.resume(Unit)
            }
            dialog.setCanceledOnTouchOutside(!required)
            dialog.show()
            if (config.storeLink.isBlank()) {
                updateManager = ITGUpdateManager(activity, REQUEST_CODE,
                    object : IUpdateInstanceCallback {
                        override fun updateAvailableListener(updateAvailability: AppUpdateInfo) = AppUpdateType.IMMEDIATE
                        override fun onUpdateUnavailable() = openStore()
                        override fun onUpdateFailed(error: Exception) = openStore()
                    })
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // A custom distribution link bypasses Play's in-app flow deliberately.
                if (config.storeLink.isNotBlank()) openStore()
                else {
                    AppOpenManager.getInstance().skipNextResume("app_update")
                    updateManager?.checkUpdateAvailable()
                }
            }
            continuation.invokeOnCancellation {
                // Cancellation can be delivered off-main by a parent scope.
                Handler(Looper.getMainLooper()).post { release(); dialog.dismiss() }
            }
        }
    }
}
