package io.adlogic.sample.utils

import android.app.Activity
import android.content.Intent
import io.adlogic.sample.app.AppConstants
import io.adlogic.sample.ui.component.main.MainActivity
import io.adlogic.sample.ui.component.setting.SettingActivity
import io.adlogic.sample.ui.component.splash.SplashActivity
import io.adlogic.sample.ui.component.uninstall.SurveyActivity
import kotlin.jvm.java

object Routes {
    fun startMainActivity(fromActivity: Activity) =
        Intent(fromActivity, MainActivity::class.java).apply {
            putExtra(AppConstants.KEY_TRACKING_SCREEN_FROM, fromActivity::class.java.simpleName)
            fromActivity.startActivity(this)
        }
    fun startSplashActivity(fromActivity: Activity) =
        Intent(fromActivity, SplashActivity::class.java).apply {
            putExtra(AppConstants.KEY_TRACKING_SCREEN_FROM, fromActivity::class.java.simpleName)
            fromActivity.startActivity(this)
        }

    fun startSurveyActivity(fromActivity: Activity) =
        Intent(fromActivity, SurveyActivity::class.java).apply {
            putExtra(AppConstants.KEY_TRACKING_SCREEN_FROM, fromActivity::class.java.simpleName)
            fromActivity.startActivity(this)
        }

    fun startSettingActivity(fromActivity: Activity) =
        Intent(fromActivity, SettingActivity::class.java).apply {
            putExtra(AppConstants.KEY_TRACKING_SCREEN_FROM, fromActivity::class.java.simpleName)
            fromActivity.startActivity(this)
        }

    fun addTrackingMoveScreen(fromActivity: String, toActivity: String) {
        SampleTrackingHelper.fromScreenToScreen(fromActivity, toActivity)
    }

}