package io.adlogic.sample.ui.component.uninstall

import android.content.Intent
import android.provider.Settings
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.ads.module.helper.adnative.NativeAdHelper
import io.adlogic.sample.R
import io.adlogic.sample.ads.AppAdPlacement
import io.adlogic.sample.databinding.ActivitySurveyBinding
import io.adlogic.sample.ui.bases.BaseActivity
import io.adlogic.sample.ui.bases.ext.click
import io.adlogic.sample.utils.Routes
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SurveyActivity : BaseActivity<ActivitySurveyBinding>() {

    override fun getLayoutActivity() = R.layout.activity_survey

    override fun initViews() {
        super.initViews()
        setupNativeAd()
    }

    // The helper owns the ad from here: gate, load, bind, and hiding the slot on skip/fail.
    // Loading skeleton is auto-derived from the ad layout (config.autoShimmer)
    private fun setupNativeAd() {
        val frAds = mBinding.root.findViewById<FrameLayout>(R.id.fr_ads) ?: return
        NativeAdHelper.forPlacement(
            this, this, AppAdPlacement.NATIVE_SURVEY, frAds, R.layout.layout_native_ad_medium,
        )
    }

    override fun onClickViews() {
        super.onClickViews()

        mBinding.btnCancel.click {
            whenBack()
        }

        mBinding.imgBack.click {
            whenBack()
        }

        mBinding.btnUninstall.click {
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
            }
            finish()
        }
    }

    private fun whenBack() {
        Routes.startMainActivity(this)
        finish()
    }
}
