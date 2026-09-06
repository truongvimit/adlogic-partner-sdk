package io.retentionkit.widgets

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import io.retentionkit.core.*

internal class FeatureShortcuts(private val runtime: RetentionRuntime, private val options: WidgetOptions) {
    fun reconcile(widgetsEnabled: Boolean, revision: Long) {
        if (Build.VERSION.SDK_INT < 25) return
        val context = runtime.application
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val state = runtime.store.snapshot(NAMESPACE)
        val owned = state.entries().keys.filter { it.startsWith(PREFIX) }.toSet()
        val enabled = widgetsEnabled && runtime.config.boolean("widgets.shortcuts.enabled", options.shortcutsEnabled)
        if (!enabled) {
            if (runtime.config.revision != revision) return
            if (owned.isNotEmpty()) manager.removeDynamicShortcuts(owned.toList())
            runtime.store.transaction(NAMESPACE) { transaction -> owned.forEach(transaction::remove) }
            return
        }
        val launchActivity: ComponentName = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: run {
            runtime.diagnostics.record("widgets.shortcuts", "No launcher Activity; feature shortcuts deferred")
            return
        }
        val dynamic = manager.dynamicShortcuts
        val manifest = manager.manifestShortcuts
        val foreign = (dynamic + manifest).filter { it.activity == launchActivity && it.id !in owned }.map { it.id }.toSet()
        val budget = (manager.maxShortcutCountPerActivity - foreign.size - options.reservedShortcutSlots)
            .coerceAtLeast(0).coerceAtMost(options.maxShortcuts)
        val selected = options.featureIds
        val catalogue = runtime.features()
        val features = (if (selected.isEmpty()) catalogue else selected.mapNotNull { id -> catalogue.firstOrNull { it.id == id } }).take(budget)
        val planned = features.mapNotNull { feature ->
            val id = PREFIX + feature.id
            // A foreign item that happens to use our reserved ID is not adopted or overwritten.
            if ((dynamic + manifest).any { it.id == id } && id !in owned) return@mapNotNull null
            try {
                val entry = RetentionEntry(RetentionEntrySource.SHORTCUT, feature.id, feature.id,
                    mode = RetentionEntryMode.REUSABLE, createdAtMillis = runtime.clock.wallTimeMillis())
                val intent = runtime.createEntryIntent(entry) ?: return@mapNotNull null
                if (intent.action == null) intent.action = Intent.ACTION_VIEW
                ShortcutInfo.Builder(context, id).setActivity(launchActivity).setShortLabel(feature.label)
                    .setLongLabel(feature.description.ifBlank { feature.label }).setIcon(Icon.createWithResource(context, feature.iconRes))
                    .setIntent(intent).build()
            } catch (error: Exception) {
                runtime.diagnostics.record("widgets.shortcuts", "Feature shortcut skipped: ${feature.id}", RetentionDiagnosticLevel.ERROR, error)
                null
            }
        }
        // Host catalogue/router callbacks can update config. Recheck after them and before each effect.
        if (runtime.config.revision != revision || !runtime.config.boolean("widgets.enabled", true)) return
        val desired = planned.map { it.id }.toSet()
        val stale = owned - desired
        if (stale.isNotEmpty()) manager.removeDynamicShortcuts(stale.toList())
        if (runtime.config.revision != revision) return
        if (planned.isNotEmpty()) {
            // Reserve exact ownership before publishing; a process death cannot orphan published IDs.
            runtime.store.transaction(NAMESPACE) { transaction -> desired.forEach { transaction.put(it, true) } }
            if (!manager.addDynamicShortcuts(planned)) {
                runtime.diagnostics.record("widgets.shortcuts", "Launcher throttled shortcut update; retry on next reconcile")
                return
            }
        }
        runtime.store.transaction(NAMESPACE) { transaction -> stale.forEach(transaction::remove) }
    }
    companion object {
        const val PREFIX = "io.retentionkit.widgets.feature."
        private const val NAMESPACE = "widgets.shortcuts"
    }
}
