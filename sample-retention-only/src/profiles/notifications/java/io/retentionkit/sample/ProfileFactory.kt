package io.retentionkit.sample

import android.Manifest
import android.app.Activity
import android.os.Build
import io.retentionkit.core.RetentionEntry
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.notifications.RetentionNotificationOptions
import io.retentionkit.notifications.RetentionNotifications

internal object ProfileFactory {
    fun create(): ProofProfile = object : ProofProfile {
        private val notifications = RetentionNotifications(RetentionNotificationOptions())
        override fun modules() = listOf(notifications)
        override fun actions(activity: Activity, runtime: RetentionRuntime) = listOf(
            ProofAction("Allow notifications") {
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 710)
                    "Permission requested. The system owns the result."
                } else "This Android version uses system notification settings."
            },
            ProofAction("Refresh quiet notifications") { notifications.refreshForegroundNotifications().toString() },
        )
        override fun entryAccepted(entry: RetentionEntry) { notifications.recordOpened(entry) }
    }
}
