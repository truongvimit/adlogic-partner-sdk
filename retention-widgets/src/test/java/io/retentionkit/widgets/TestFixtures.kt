package io.retentionkit.widgets

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import java.util.UUID

internal class TestClock(var now: Long = 1_800_000_000_000, var elapsed: Long = 1000) : RetentionClock {
    override fun wallTimeMillis() = now
    override fun elapsedRealtimeMillis() = elapsed
    fun advance(millis: Long) { now += millis; elapsed += millis }
}

internal class FakeWidgetPlatform : WidgetPlatform {
    var supported = true
    var accept = true
    var fail = false
    var requests = 0
    var callback: PendingIntent? = null
    var onRequest: (() -> Unit)? = null
    val providers = mutableMapOf<Int, ComponentName>()
    val sizes = mutableMapOf<Int, Bundle>()
    val rendered = mutableMapOf<Int, RemoteViews>()
    override fun pinSupported() = supported
    override fun ids(provider: ComponentName) = providers.filterValues { it == provider }.keys.toIntArray()
    override fun providerFor(id: Int) = providers[id]
    override fun options(id: Int) = sizes[id] ?: Bundle()
    override fun update(id: Int, views: RemoteViews) { rendered[id] = views }
    override fun requestPin(provider: ComponentName, callback: PendingIntent): Boolean {
        requests++
        this.callback = callback
        onRequest?.invoke()
        if (fail) throw IllegalStateException("launcher unavailable")
        return accept
    }
}

internal class Fixture(
    val clock: TestClock = TestClock(),
    val platform: FakeWidgetPlatform = FakeWidgetPlatform(),
    val store: RetentionStore = SharedPreferencesRetentionStore(ApplicationProvider.getApplicationContext(), "widgets_test_${UUID.randomUUID()}"),
    options: WidgetOptions = WidgetOptions(shortcutsEnabled = false),
    featureProvider: RetentionFeatureProvider = RetentionFeatureProvider { features },
    localeProvider: RetentionLocaleProvider = RetentionLocaleProvider.SYSTEM,
    initialOverrides: Map<String, String> = emptyMap(),
) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val events = mutableListOf<RetentionEvent>()
    val module = RetentionWidgets(options).also { it.platform = platform }
    val runtime = (RetentionRuntime.install(app, RetentionOptions(
        modules = listOf(module), clock = clock, store = store,
        featureProvider = featureProvider, localeProvider = localeProvider,
        router = RetentionRouter { context, _ -> Intent(context, Activity::class.java).setAction(Intent.ACTION_VIEW) },
        eventSink = RetentionEventSink { events.add(it) },
        initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER),
        initialOverrides = initialOverrides,
    )) as RetentionInstallResult.Installed).runtime
    fun foreground(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup().also {
        runtime.signal(RetentionSignal.ProcessForeground)
    }
    fun installWidget(id: Int) { platform.providers[id] = module.provider }
    companion object {
        val features = listOf("notes", "saved_items", "text_tools", "guide").map {
            RetentionFeature(it, it.replaceFirstChar(Char::uppercase), android.R.drawable.ic_menu_search)
        }
    }
}
