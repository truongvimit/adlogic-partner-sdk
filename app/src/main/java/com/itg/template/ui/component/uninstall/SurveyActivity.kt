package com.itg.template.ui.component.uninstall

import android.content.Intent
import android.provider.Settings
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.ads.module.helper.adnative.NativeAdParam
import com.facebook.shimmer.ShimmerFrameLayout
import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdsManager
import com.itg.template.ads.native_survey
import com.itg.template.databinding.ActivitySurveyBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import com.itg.template.utils.Routes
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SurveyActivity : BaseActivity<ActivitySurveyBinding>() {

    override fun getLayoutActivity() = R.layout.activity_survey

    override fun initViews() {
        super.initViews()
        setupNativeAd()
    }

    // The helper owns the ad from here: gate, load, bind, and hiding the slot on skip/fail
    private fun setupNativeAd() {
        val frAds = mBinding.root.findViewById<FrameLayout>(R.id.fr_ads) ?: return
        val helper = AdsManager.nativeHelper(
            this, this, "native_survey", AdRemoteConfig.native_survey,
            R.layout.layout_native_ad_medium,
        ).setNativeContentView(frAds)
        mBinding.root.findViewById<ShimmerFrameLayout>(R.id.shimmer_ads)
            ?.let { helper.setShimmerLayoutView(it) }
        helper.requestAds(NativeAdParam.Request)
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
