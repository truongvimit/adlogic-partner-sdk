package com.itg.template.ui.component.uninstall

import android.content.Context
import android.os.Build

/** Remove only our retired dynamic ID. Existing pinned copies still enter the compatibility route. */
object ShortcutManager {
    fun initShortCut(context: Context) {
        if (Build.VERSION.SDK_INT >= 25) runCatching {
            context.getSystemService(android.content.pm.ShortcutManager::class.java)
                ?.removeDynamicShortcuts(listOf("ACTION_OPEN_UNINSTALL"))
        }
        io.retentionkit.RetentionKit.get()?.runtime?.reconcile("legacy_shortcut_migration")
    }
}
