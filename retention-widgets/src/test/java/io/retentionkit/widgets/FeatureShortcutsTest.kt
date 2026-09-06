package io.retentionkit.widgets

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureShortcutsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val launcher get() = ComponentName(app.packageName, "test.Launcher")
    private val manager get() = app.getSystemService(ShortcutManager::class.java)
    @After fun after() { RetentionRuntime.uninstallForTests() }

    private fun prepareLauncher() {
        shadowOf(app.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(app.packageManager).addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun shortcut(id: String): ShortcutInfo = ShortcutInfo.Builder(app, id).setActivity(launcher)
        .setShortLabel(id).setIntent(Intent(Intent.ACTION_VIEW).setComponent(launcher)).build()

    @Test fun quotaIncludesManifestAndForeignIdsAndKeepsReservedSlot() {
        prepareLauncher()
        shadowOf(manager).setMaxShortcutCountPerActivity(5)
        manager.addDynamicShortcuts(listOf(shortcut("partner_camera")))
        shadowOf(manager).setManifestShortcuts(listOf(shortcut("partner_static")))
        val f = Fixture(options = WidgetOptions())
        val ids = manager.dynamicShortcuts.map { it.id }
        assertTrue(ids.contains("partner_camera"))
        assertEquals(2, ids.count { it.startsWith(FeatureShortcuts.PREFIX) })
        assertEquals(listOf("partner_static"), manager.manifestShortcuts.map { it.id })
        assertEquals(5, manager.maxShortcutCountPerActivity)
        // A feedback module consumes the reserved slot without being removed by future refreshes.
        manager.addDynamicShortcuts(listOf(shortcut("io.retentionkit.feedback")))
        f.module.refresh()
        assertTrue(manager.dynamicShortcuts.any { it.id == "io.retentionkit.feedback" })
    }

    @Test fun disablingRemovesOnlyLedgerOwnedDynamicIdsAcrossProcessRestart() {
        prepareLauncher()
        manager.addDynamicShortcuts(listOf(shortcut("partner_camera")))
        val before = Fixture(options = WidgetOptions())
        assertTrue(manager.dynamicShortcuts.any { it.id.startsWith(FeatureShortcuts.PREFIX) })
        RetentionRuntime.uninstallForTests()
        val after = Fixture(store = before.store, options = WidgetOptions())
        after.runtime.updateConfig(mapOf("widgets.enabled" to "false"))
        assertEquals(listOf("partner_camera"), manager.dynamicShortcuts.map { it.id })
    }

    @Test fun occupiedQuotaAndReservedPrefixCollisionNeverOverwriteForeignShortcut() {
        prepareLauncher()
        shadowOf(manager).setMaxShortcutCountPerActivity(3)
        val collision = FeatureShortcuts.PREFIX + "translate"
        manager.addDynamicShortcuts(listOf(shortcut(collision), shortcut("host")))
        val f = Fixture(options = WidgetOptions())
        assertEquals(setOf(collision, "host"), manager.dynamicShortcuts.map { it.id }.toSet())
        f.runtime.updateConfig(mapOf("widgets.shortcuts.enabled" to "false"))
        assertEquals(setOf(collision, "host"), manager.dynamicShortcuts.map { it.id }.toSet())
    }

    @Test fun shortcutRouteHasReusableTypedEnvelopeAndUpdatesLocalizedLabelInPlace() {
        prepareLauncher()
        var label = "Translate"
        val f = Fixture(options = WidgetOptions(), featureProvider = RetentionFeatureProvider {
            Fixture.features.map { if (it.id == "translate") it.copy(label = label) else it }
        })
        val id = FeatureShortcuts.PREFIX + "translate"
        val initial = manager.dynamicShortcuts.first { it.id == id }
        val entry = (RetentionEntryCodec.read(initial.intent) as RetentionEntryDecodeResult.Valid).entry
        assertEquals(RetentionEntryMode.REUSABLE, entry.mode)
        assertEquals(RetentionEntrySource.SHORTCUT, entry.source)
        assertEquals("translate", entry.destination)
        label = "Dịch"
        f.module.refresh()
        assertEquals("Dịch", manager.dynamicShortcuts.first { it.id == id }.shortLabel.toString())
    }

    @Test @Config(sdk = [24]) fun api24SkipsShortcutsWithoutBreakingWidgetInitialization() {
        val f = Fixture(options = WidgetOptions())
        f.installWidget(10)
        f.module.refresh()
        assertTrue(f.platform.rendered.containsKey(10))
        assertTrue(AndroidWidgetPlatform(f.app).pinSupported().not())
    }

    @Test @Config(sdk = [25]) fun api25SupportsDynamicShortcutsButPinIsUnavailable() {
        prepareLauncher()
        Fixture(options = WidgetOptions())
        assertTrue(manager.dynamicShortcuts.isNotEmpty())
        assertFalse(AndroidWidgetPlatform(app).pinSupported())
    }
}
