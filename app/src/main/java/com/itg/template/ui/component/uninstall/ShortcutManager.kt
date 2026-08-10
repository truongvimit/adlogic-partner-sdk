package com.itg.template.ui.component.uninstall

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.itg.template.R
import com.itg.template.app.AppConstants
import com.itg.template.ui.bases.ext.getSystemLocaleString
import kotlin.apply
import kotlin.jvm.java

object ShortcutManager {
    fun initShortCut(context : Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = context.getSystemService(ShortcutManager::class.java)
            try {
                manager.removeAllDynamicShortcuts()
                val uninstallShortCut = ShortcutInfo.Builder(context, "ACTION_OPEN_UNINSTALL")
                    .setShortLabel(context.getSystemLocaleString(R.string.txt_uninstall))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_uninstall))
                    .setIntent(Intent(context, ConfirmUninstallActivity::class.java).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = "android.intent.action.SHORTCUT_UNINSTALL_APP"
                        putExtra(AppConstants.FROM_SHORTCUT, "ACTION_OPEN_UNINSTALL")
                    })
                    .setRank(1)
                    .build()
                manager.dynamicShortcuts = listOf(uninstallShortCut)
            } catch (_: Exception) {
            }
        }
    }
}
