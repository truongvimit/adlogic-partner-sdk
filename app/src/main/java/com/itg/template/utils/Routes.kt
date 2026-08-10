package com.itg.template.utils

import android.app.Activity
import android.content.Intent
import com.itg.template.app.AppConstants
import com.itg.template.ui.component.main.MainActivity
import com.itg.template.ui.component.setting.SettingActivity
import com.itg.template.ui.component.splash.SplashActivity
import com.itg.template.ui.component.uninstall.SurveyActivity
import com.itg.template.ui.component.welcome.WelcomeActivity
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


    fun startWelcomeActivity(fromActivity: Activity) =
        Intent(fromActivity, WelcomeActivity::class.java).apply {
            putExtra(AppConstants.KEY_TRACKING_SCREEN_FROM, fromActivity::class.java.simpleName)
            fromActivity.startActivity(this)
        }

    fun addTrackingMoveScreen(fromActivity: String, toActivity: String) {
        ITGTrackingHelper.fromScreenToScreen(fromActivity, toActivity)
    }

}