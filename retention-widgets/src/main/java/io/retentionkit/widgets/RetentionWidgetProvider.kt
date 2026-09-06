package io.retentionkit.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Subclass and register your provider + metadata to customize size/preview. Configure providerClass. */
open class RetentionWidgetProvider : AppWidgetProvider() {
    private fun module(context: Context): RetentionWidgets? = RetentionWidgets.get()?.takeIf {
        it.provider == ComponentName(context, javaClass)
    }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        module(context)?.update(appWidgetIds)
    }
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        module(context)?.update(intArrayOf(appWidgetId))
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { module(context)?.delete(appWidgetIds) }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        module(context)?.restore(oldWidgetIds, newWidgetIds)
    }
}

/** Only reachable by explicit PendingIntent capability; the manifest receiver is not exported. */
class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetPinCoordinator.CALLBACK_ACTION) return
        val data = intent.data ?: return
        if (data.scheme != "retentionkit" || data.host != "widget-pin" || data.pathSegments.size != 1) return
        val token = data.lastPathSegment ?: return
        if (!token.matches(Regex("[a-fA-F0-9-]{36}"))) return
        RetentionWidgets.get()?.receivePin(token, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
    }
}

class WidgetLocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) RetentionWidgets.get()?.refresh()
    }
}
