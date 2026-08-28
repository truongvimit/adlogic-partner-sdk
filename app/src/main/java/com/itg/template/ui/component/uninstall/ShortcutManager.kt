package com.itg.template.ui.component.uninstall

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.itg.template.R
import com.itg.template.ui.bases.ext.getSystemLocaleString
import com.itg.template.ui.component.splash.SplashActivity
import io.onboardkit.ui.splash.SplashEntry

object ShortcutManager {
    fun initShortCut(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = context.getSystemService(ShortcutManager::class.java)
            try {
                manager.removeAllDynamicShortcuts()
                val uninstallShortCut = ShortcutInfo.Builder(context, "ACTION_OPEN_UNINSTALL")
                    .setShortLabel(context.getSystemLocaleString(R.string.txt_uninstall))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_uninstall))
                    // Through the splash, not straight to the screen: the entry tag is what makes
                    // this tap spend inter_uninstall and lets the listener route it afterwards.
                    // The action is only there because shortcuts refuse an intent without one.
                    .setIntent(
                        SplashEntry.UNINSTALL.intent(context, SplashActivity::class.java)
                            .setAction(Intent.ACTION_VIEW),
                    )
                    .setRank(1)
                    .build()
                manager.dynamicShortcuts = listOf(uninstallShortCut)
            } catch (_: Exception) {
            }
        }
    }
}
