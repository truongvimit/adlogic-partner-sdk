package io.retentionkit.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/** Internal platform seam permits tests to exercise real coordinators without a launcher. */
internal interface WidgetPlatform {
    fun pinSupported(): Boolean
    fun ids(provider: ComponentName): IntArray
    fun providerFor(id: Int): ComponentName?
    fun options(id: Int): Bundle
    fun update(id: Int, views: RemoteViews)
    fun requestPin(provider: ComponentName, callback: PendingIntent): Boolean
}

internal class AndroidWidgetPlatform(context: Context) : WidgetPlatform {
    private val manager = AppWidgetManager.getInstance(context.applicationContext)
    override fun pinSupported(): Boolean = Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported
    override fun ids(provider: ComponentName): IntArray = manager.getAppWidgetIds(provider)
    override fun providerFor(id: Int): ComponentName? = manager.getAppWidgetInfo(id)?.provider
    override fun options(id: Int): Bundle = manager.getAppWidgetOptions(id)
    override fun update(id: Int, views: RemoteViews) = manager.updateAppWidget(id, views)
    override fun requestPin(provider: ComponentName, callback: PendingIntent): Boolean =
        Build.VERSION.SDK_INT >= 26 && manager.requestPinAppWidget(provider, null, callback)
}
