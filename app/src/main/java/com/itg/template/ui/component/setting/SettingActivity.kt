package com.itg.template.ui.component.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.core.view.isVisible
import com.ads.module.admob.AppOpenManager
import com.itg.template.BuildConfig
import com.itg.template.R
import com.itg.template.app.AppConstants
import com.itg.template.databinding.ActivitySettingBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click

import com.itg.template.ui.bases.ext.showRateDialog
import com.itg.template.utils.Routes
import dagger.hilt.android.AndroidEntryPoint
import io.onboardkit.ui.language.LanguageScreenMode
import io.onboardkit.ui.language.ObLanguageActivity
import io.paykit.PayKit
import io.paykit.PaywallPlacement

@AndroidEntryPoint
class SettingActivity : BaseActivity<ActivitySettingBinding>() {
    override fun getLayoutActivity(): Int = R.layout.activity_setting

    // SETTINGS mode: no ads, real back; result carries the picked language code
    private val languagePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val code = result.data?.getStringExtra(ObLanguageActivity.RESULT_LANGUAGE_CODE)
        if (result.resultCode == RESULT_OK && code != null) {
            appSharedPref.languageCode = code
            Routes.startMainActivity(this)
            finish()
        }
    }

    override fun onClickViews() {
        super.onClickViews()
        mBinding.apply {
            imvBack.click { finish() }
            rltPremium.click {
                PayKit.launch(this@SettingActivity, PaywallPlacement.SETTING)
            }
            rltLanguage.click {
                languagePicker.launch(
                    ObLanguageActivity.intentFor(this@SettingActivity, LanguageScreenMode.SETTINGS),
                )
            }
            rltRate.click { initRate() }
            rltFeedback.click { sendFeedback(BuildConfig.email_feedback) }
            rltShare.click { shareApp(this@SettingActivity) }
            rltPolicy.click {
                openPrivacyPolicy()
            }
        }
    }

    private fun initRate() {
        val isRate = appSharedPref.isRate
        if (isRate) {
            Toast.makeText(
                this@SettingActivity,
                this@SettingActivity.getString(R.string.txt_thanks_you_for_rating),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            appSharedPref.isRate = true
            showRateDialog(this@SettingActivity, false)
        }
    }

    private fun shareApp(context: Context) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
            var shareMessage =
                "${context.getString(R.string.app_name)}\n${context.getString(R.string.let_me_recommend)}"
            shareMessage =
                "$shareMessage\nhttps://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage)
            Handler().postDelayed({
                context.startActivity(
                    Intent.createChooser(
                        shareIntent, context.getString(R.string.share_to)
                    )
                )
            }, 250)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openPrivacyPolicy() {
        val privacyPolicyUrl = AppConstants.LINK_PRIVACY_POLICY
        if (privacyPolicyUrl.isBlank()) {
            Toast.makeText(this, "Privacy Policy URL is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(privacyPolicyUrl)
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            if (browserIntent.resolveActivity(packageManager) != null) {
                startActivity(browserIntent)
                disableAdsResume()
            } else {
                Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SettingActivity", "Error opening privacy policy", e)
            Toast.makeText(this, "Unable to open Privacy Policy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFeedback(email: String) {
        val intentFeedBack = Intent(Intent.ACTION_SEND)
        intentFeedBack.setType("text/email")
        intentFeedBack.putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        intentFeedBack.putExtra(Intent.EXTRA_SUBJECT, "Feedback")
        intentFeedBack.putExtra(Intent.EXTRA_TEXT, "" + "")
        startActivity(Intent.createChooser(intentFeedBack, "Send Feedback:"))
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every resume: this screen stays alive under the paywall, so a purchase made
        // there has to remove the row on the way back. Remote config can retire it too.
        mBinding.rltPremium.isVisible =
            !PayKit.isPremium() && PayKit.isEnabled(PaywallPlacement.SETTING)

        enableAdsResume()
    }

    private fun disableAdsResume() {
        AppOpenManager.getInstance().disableAppResume()
        Log.d("hello", "Disable Ads Resume Setting")
    }

    private fun enableAdsResume() {
        AppOpenManager.getInstance().enableAppResume()
        Log.d("hello", "Enable Ads Resume Setting")
    }
}