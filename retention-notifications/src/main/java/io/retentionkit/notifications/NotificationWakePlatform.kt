package io.retentionkit.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

internal const val WAKE_ACTION = "io.retentionkit.notifications.WAKE_CHECKPOINT"
internal const val MAX_WAKE_MILLIS = 20_000L

/** Android boundary: acquiring a lock does not certify a physical display transition. */
internal interface NotificationWakePlatform {
    fun interactive(): Boolean
    fun blocked(): String?
    fun acquire(durationMillis: Long): AutoCloseable
    fun observe(callback: (Boolean) -> Unit): AutoCloseable
    fun schedule(occurrence: String, atMillis: Long)
    fun cancel(occurrence: String)
}

@Suppress("DEPRECATION")
internal class AndroidNotificationWakePlatform(context: Context) : NotificationWakePlatform {
    private val context = context.applicationContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun interactive(): Boolean = power.isInteractive
    override fun blocked(): String? = when {
        context.checkSelfPermission(Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED -> "wake_permission_denied"
        !power.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK) -> "wake_level_unsupported"
        else -> null // TURN_SCREEN_ON absence alone does not reject the supported legacy compat path.
    }

    override fun acquire(durationMillis: Long): AutoCloseable {
        require(durationMillis in 1..MAX_WAKE_MILLIS)
        val wake = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RetentionKit:lockscreen") // No ON_AFTER_RELEASE: do not extend user-activity timeout.
        wake.setReferenceCounted(false)
        wake.acquire(durationMillis)
        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true) && wake.isHeld) wake.release() }
    }

    override fun observe(callback: (Boolean) -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_SCREEN_OFF)
                    callback(power.isInteractive)
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) context.unregisterReceiver(receiver) }
    }

    override fun schedule(occurrence: String, atMillis: Long) {
        // Inexact and quota-controlled, including Doze. This is a bounded fallback, not a timing SLA.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(occurrence, atMillis))
    }
    override fun cancel(occurrence: String) { alarms.cancel(pending(occurrence, 0)) }
    private fun pending(occurrence: String, atMillis: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, NotificationWakeReceiver::class.java).setAction(WAKE_ACTION)
            .setData(Uri.Builder().scheme("retentionkit-notification").authority("wake").appendPath(occurrence).build())
            .putExtra(OCCURRENCE_EXTRA, occurrence).putExtra("checkpoint_at", atMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Only the module's explicit immutable PendingIntent invokes this non-exported receiver. */
class NotificationWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WAKE_ACTION) return
        val occurrence = intent.getStringExtra(OCCURRENCE_EXTRA) ?: return
        if (!idPattern.matches(occurrence)) return
        RetentionNotifications.active?.wakeCheckpoint(occurrence, intent.getLongExtra("checkpoint_at", 0))
    }
}
