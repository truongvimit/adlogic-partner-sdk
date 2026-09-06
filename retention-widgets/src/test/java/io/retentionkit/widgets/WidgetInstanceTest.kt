package io.retentionkit.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetInstanceTest {
    class CustomProvider : RetentionWidgetProvider()
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun directImmutableActionIdentityIsIndependentAndStableAcrossUpdates() {
        val captured = mutableMapOf<Int, List<WidgetAction>>()
        val f = Fixture(options = WidgetOptions(shortcutsEnabled = false, renderer = RetentionWidgetRenderer { context, instance, actions ->
            captured[instance.appWidgetId] = actions
            StandardWidgetRenderer().render(context, instance, actions)
        }))
        f.installWidget(10); f.installWidget(11)
        f.module.refresh()
        val first = captured.getValue(10)[0].pendingIntent
        val otherInstance = captured.getValue(11)[0].pendingIntent
        val otherAction = captured.getValue(10)[1].pendingIntent
        assertNotEquals(first, otherInstance)
        assertNotEquals(first, otherAction)
        assertTrue(first.isImmutable)
        assertTrue(shadowOf(first).isActivityIntent)
        f.module.refresh()
        assertEquals(first, captured.getValue(10)[0].pendingIntent)
        val intent = Intent(shadowOf(first).savedIntent)
        val accepted = f.runtime.entries.capture(intent) as RetentionEntryAcceptance.Accepted
        assertEquals("10", accepted.entry.instanceId)
        assertEquals("translate", accepted.entry.destination)
        assertTrue(f.runtime.entries.consume(accepted.entry.token))
        assertFalse(f.runtime.entries.consume(accepted.entry.token))
        val again = f.runtime.entries.capture(Intent(shadowOf(first).savedIntent)) as RetentionEntryAcceptance.Accepted
        assertNotEquals(accepted.entry.token, again.entry.token)
    }

    @Test fun configureResizeDeleteAndRestoreKeepInstancesIndependentAcrossRestart() {
        val f = Fixture()
        f.installWidget(10); f.installWidget(11)
        f.module.refresh()
        assertTrue(f.module.configureInstance(10, listOf("history", "camera", "translate")))
        f.platform.sizes[10] = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        }
        RetentionWidgetProvider().onAppWidgetOptionsChanged(f.app, AppWidgetManager.getInstance(f.app), 10, f.platform.sizes.getValue(10))
        assertEquals(300, f.module.instances().first { it.appWidgetId == 10 }.minWidthDp)
        assertEquals(emptyList<String>(), f.module.instances().first { it.appWidgetId == 11 }.featureIds)
        RetentionRuntime.uninstallForTests()
        f.platform.providers.remove(10)
        f.platform.providers[20] = f.module.provider
        val restored = Fixture(store = f.store, platform = f.platform)
        // Startup refresh must not discard old saved configuration before the restore broadcast.
        RetentionWidgetProvider().onRestored(restored.app, intArrayOf(10), intArrayOf(20))
        assertEquals(listOf("history", "camera", "translate"), restored.module.instances().first { it.appWidgetId == 20 }.featureIds)
        assertNull(restored.module.state.instance(10))
        RetentionWidgetProvider().onDeleted(restored.app, intArrayOf(11))
        assertNull(restored.module.state.instance(11))
        assertNotNull(restored.module.state.instance(20))
        assertFalse(restored.module.configureInstance(999, listOf("translate")))
    }

    @Test fun standardLayoutsActuallyInflateWithThreeAndFourLocalizedActionsAfterLocaleChange() {
        var language = "en"
        val f = Fixture(localeProvider = RetentionLocaleProvider { app ->
            val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            app.createConfigurationContext(configuration)
        }, featureProvider = RetentionFeatureProvider { context ->
            val vietnamese = context.resources.configuration.locales[0].language == "vi"
            Fixture.features.mapIndexed { i, feature -> feature.copy(label = if (vietnamese) "Tính năng $i" else feature.label) }
        })
        f.installWidget(5)
        f.module.refresh()
        var views = f.platform.rendered.getValue(5).apply(f.app, FrameLayout(f.app))
        assertEquals("Quick actions", views.findViewById<TextView>(R.id.rk_widget_title).text.toString())
        assertEquals(View.VISIBLE, views.findViewById<View>(R.id.rk_action_4).visibility)
        language = "vi"
        f.module.configureInstance(5, listOf("translate", "camera", "history"))
        f.platform.sizes[5] = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        }
        WidgetLocaleReceiver().onReceive(f.app, Intent(Intent.ACTION_LOCALE_CHANGED))
        views = f.platform.rendered.getValue(5).apply(f.app, FrameLayout(f.app))
        assertEquals(R.layout.rk_widget_row, f.platform.rendered.getValue(5).layoutId)
        assertEquals("Truy cập nhanh", views.findViewById<TextView>(R.id.rk_widget_title).text.toString())
        assertEquals("Tính năng 0", views.findViewById<TextView>(R.id.rk_label_1).text.toString())
        assertEquals(View.GONE, views.findViewById<View>(R.id.rk_action_4).visibility)
    }

    @Test fun customRendererFailureFallsBackAndDisabledModuleReplacesOldActionsWithoutDeletingWidgets() {
        val f = Fixture(options = WidgetOptions(shortcutsEnabled = false, renderer = RetentionWidgetRenderer { _, _, _ ->
            throw IllegalStateException("bad custom renderer")
        }))
        f.installWidget(1); f.installWidget(2)
        f.module.refresh()
        assertTrue(f.runtime.diagnostics.snapshot().any { it.component == "widgets.custom_renderer" })
        assertEquals(2, f.platform.rendered.size)
        f.runtime.updateConfig(mapOf("widgets.enabled" to "false"))
        assertEquals(2, f.module.instances().size)
        val views = f.platform.rendered.getValue(1).apply(f.app, FrameLayout(f.app))
        assertEquals(View.GONE, views.findViewById<View>(R.id.rk_action_1).visibility)
        assertEquals(View.VISIBLE, views.findViewById<View>(R.id.rk_widget_empty).visibility)
    }

    @Test fun rendererConfigMutationCannotPublishStaleActions() {
        lateinit var f: Fixture
        var mutate = false
        f = Fixture(options = WidgetOptions(shortcutsEnabled = false, renderer = RetentionWidgetRenderer { context, instance, actions ->
            if (mutate) {
                mutate = false
                f.runtime.updateConfig(mapOf("widgets.enabled" to "false"))
            }
            StandardWidgetRenderer().render(context, instance, actions)
        }))
        f.installWidget(3)
        mutate = true
        f.module.refresh()
        val views = f.platform.rendered.getValue(3).apply(f.app, FrameLayout(f.app))
        assertEquals(View.GONE, views.findViewById<View>(R.id.rk_action_1).visibility)
    }

    @Test fun invalidFeatureConfigIsRejectedAndForeignProviderIsNeverRendered() {
        val f = Fixture()
        f.installWidget(10)
        f.module.refresh()
        assertFalse(f.module.configureInstance(10, listOf("missing")))
        assertFalse(f.module.configureInstance(10, listOf("camera", "camera")))
        f.platform.providers[88] = android.content.ComponentName(f.app.packageName, "OtherWidget")
        f.module.update(intArrayOf(88))
        assertFalse(f.platform.rendered.containsKey(88))
        assertTrue(f.runtime.updateConfig(mapOf("widgets.pin.timeout_ms" to "0")) is RetentionConfigResult.Rejected)
        assertTrue(f.runtime.updateConfig(mapOf("widgets.enabled" to "yes")) is RetentionConfigResult.Rejected)
    }

    @Test fun customProviderReceivesOnlyItsOwnUpdatesAndCreatesTypedWidgetActions() {
        val f = Fixture(options = WidgetOptions(providerClass = CustomProvider::class.java, shortcutsEnabled = false))
        f.installWidget(19)
        RetentionWidgetProvider().onUpdate(f.app, AppWidgetManager.getInstance(f.app), intArrayOf(19))
        assertFalse(f.platform.rendered.containsKey(19))
        CustomProvider().onUpdate(f.app, AppWidgetManager.getInstance(f.app), intArrayOf(19))
        assertTrue(f.platform.rendered.containsKey(19))
        assertEquals(CustomProvider::class.java.name, f.module.provider.className)
    }
}
