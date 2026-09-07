package io.retentionkit.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.retentionkit.core.RetentionDiagnosticLevel
import io.retentionkit.core.RetentionRuntime

internal const val NOTIFICATION_TAG = "io.retentionkit.notifications"
internal const val ALARM_ACTION = "io.retentionkit.notifications.ALARM"
internal const val DISMISS_ACTION = "io.retentionkit.notifications.DISMISS"
internal const val OCCURRENCE_EXTRA = "io.retentionkit.notifications.occurrence.v1"
internal const val ALARM_EXTRA = "io.retentionkit.notifications.alarm.v1"

internal interface NotificationPlatform {
    fun createChannels(options: RetentionNotificationOptions)
    fun blocked(channel: String): String?
    fun activeOccurrence(campaign: NotificationCampaign): String?
    fun active(campaign: NotificationCampaign): Boolean
    fun post(campaign: NotificationCampaign, notification: Notification)
    fun cancel(campaign: NotificationCampaign)
    fun schedule(alarm: ScheduledNotification, triggerAtMillis: Long = alarm.due)
    fun cancelAlarm(alarm: ScheduledNotification)
}
internal fun RetentionNotificationOptions.channel(campaign: NotificationCampaign): String = channelIds[campaign] ?: when {
    preset == NotificationPreset.LEGACY_SDK -> "rk_retention_${campaign.key}"
    campaign.updates -> "updates_news"
    campaign == NotificationCampaign.LOCKSCREEN -> "lock_screen_alerts"
    campaign == NotificationCampaign.DAILY -> "daily_tips"
    campaign == NotificationCampaign.REMINDER -> "reminders"
    else -> "rk_retention_${campaign.key}"
}

internal class AndroidNotificationPlatform(private val context: Context) : NotificationPlatform {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    override fun createChannels(options: RetentionNotificationOptions) {
        if (Build.VERSION.SDK_INT < 26) return
        NotificationCampaign.entries.forEach { campaign ->
            if (manager.getNotificationChannel(options.channel(campaign)) != null) return@forEach
            val label = if (options.preset == NotificationPreset.COMMON_PLAN && campaign.updates && options.channel(campaign) == "updates_news") R.string.rk_n_channel_updates else when (campaign) {
                NotificationCampaign.DAILY -> R.string.rk_n_channel_daily
                NotificationCampaign.WINBACK -> R.string.rk_n_channel_winback
                NotificationCampaign.ONBOARDING -> R.string.rk_n_channel_onboarding
                NotificationCampaign.AD_RETURN, NotificationCampaign.APP_EXIT -> R.string.rk_n_channel_ad_return
                NotificationCampaign.REMINDER -> R.string.rk_n_channel_reminder
                NotificationCampaign.PINNED -> R.string.rk_n_channel_pinned
                NotificationCampaign.LOCKSCREEN -> R.string.rk_n_channel_lockscreen
            }
            val channel = NotificationChannel(options.channel(campaign), context.getString(label),
                when {
                    campaign.foreground -> NotificationManager.IMPORTANCE_LOW
                    options.preset == NotificationPreset.COMMON_PLAN && (campaign.updates || campaign == NotificationCampaign.LOCKSCREEN) -> NotificationManager.IMPORTANCE_HIGH
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                })
            if (campaign.foreground || options.preset == NotificationPreset.COMMON_PLAN && campaign == NotificationCampaign.DAILY) { channel.setSound(null, null); channel.enableVibration(false) }
            channel.lockscreenVisibility = if (campaign == NotificationCampaign.LOCKSCREEN && options.preset == NotificationPreset.COMMON_PLAN) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
            // Android preserves existing importance/sound and user overrides. Never delete/recreate.
            manager.createNotificationChannel(channel)
        }
    }
    override fun blocked(channel: String): String? {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "permission_denied"
        if (!manager.areNotificationsEnabled()) return "notifications_disabled"
        if (Build.VERSION.SDK_INT >= 26) {
            val current = manager.getNotificationChannel(channel) ?: return "channel_missing"
            if (current.importance == NotificationManager.IMPORTANCE_NONE) return "channel_blocked"
            if (Build.VERSION.SDK_INT >= 28 && current.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true) return "channel_group_blocked"
        }
        return null
    }
    override fun activeOccurrence(campaign: NotificationCampaign): String? = manager.activeNotifications.firstOrNull { it.tag == NOTIFICATION_TAG && it.id == campaign.notificationId }?.notification?.extras?.getString(OCCURRENCE_EXTRA)
    override fun active(campaign: NotificationCampaign): Boolean = manager.activeNotifications.any { it.tag == NOTIFICATION_TAG && it.id == campaign.notificationId }
    override fun post(campaign: NotificationCampaign, notification: Notification) = manager.notify(NOTIFICATION_TAG, campaign.notificationId, notification)
    override fun cancel(campaign: NotificationCampaign) = manager.cancel(NOTIFICATION_TAG, campaign.notificationId)
    override fun schedule(alarm: ScheduledNotification, triggerAtMillis: Long) {
        // RTC_WAKEUP is 0 (wall-clock CPU wake); it does not wake the screen. Inexact only.
        alarms.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, 10 * MINUTE, alarmIntent(alarm))
    }
    override fun cancelAlarm(alarm: ScheduledNotification) { alarms.cancel(alarmIntent(alarm)) }
    private fun alarmIntent(alarm: ScheduledNotification): PendingIntent {
        val intent = Intent(context, NotificationAlarmReceiver::class.java).setAction(ALARM_ACTION)
            .setData(Uri.Builder().scheme("retentionkit-notification").authority("alarm").appendPath(alarm.campaign.key).appendPath(alarm.slot).build())
            .putExtra(ALARM_EXTRA, alarm.encode())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

internal interface NotificationDelays {
    fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable
}
internal class MainNotificationDelays : NotificationDelays {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable {
        val runnable = Runnable(callback)
        handler.postDelayed(runnable, delayMillis)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }
}

/** Application.onCreate must install the module before a cold receiver starts. */
class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ALARM_ACTION) return
        receiveSafely("alarm") {
            val raw = intent.getStringExtra(ALARM_EXTRA) ?: return@receiveSafely
            RetentionNotifications.active?.receiveAlarm(ScheduledNotification.decode(raw))
        }
    }
}
class NotificationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        receiveSafely("restore") { RetentionNotifications.active?.reconcile(intent.action.orEmpty()) }
    }
}
/** Dismiss/Later only cancel this module's matching occurrence. No Activity launch. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DISMISS_ACTION) return
        receiveSafely("dismiss") {
            val campaign = NotificationCampaign.valueOf(intent.getStringExtra("campaign") ?: return@receiveSafely)
            val occurrence = intent.getStringExtra("occurrence") ?: return@receiveSafely
            if (occurrence.length <= 128) RetentionNotifications.active?.dismiss(campaign, occurrence)
        }
    }
    internal companion object {
        fun pending(context: Context, campaign: NotificationCampaign, occurrence: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationDismissReceiver::class.java).setAction(DISMISS_ACTION)
                .setData(Uri.Builder().scheme("retentionkit-notification").authority("dismiss").appendPath(campaign.key).appendPath(occurrence).build())
                .putExtra("campaign", campaign.name).putExtra("occurrence", occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
private inline fun receiveSafely(component: String, work: () -> Unit) {
    try { work() } catch (error: Exception) {
        RetentionRuntime.get()?.diagnostics?.record("notifications.$component", "Receiver failed", RetentionDiagnosticLevel.ERROR, error)
    }
}
