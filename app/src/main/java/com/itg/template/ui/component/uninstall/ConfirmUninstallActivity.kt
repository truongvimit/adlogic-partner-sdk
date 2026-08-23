package com.itg.template.ui.component.uninstall

import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdParam
import com.itg.template.R
import com.itg.template.ads.AdRemoteConfig
import com.itg.template.ads.AdsManager
import com.itg.template.ads.native_confirm_uninstall
import com.itg.template.databinding.ActivityConfirmUninstallBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import com.itg.template.utils.Routes
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ConfirmUninstallActivity : BaseActivity<ActivityConfirmUninstallBinding>() {

    override fun getLayoutActivity() = R.layout.activity_confirm_uninstall

    override fun initViews() {
        super.initViews()
        setupNativeAd()
    }

    // The helper owns the ad from here: gate, load, bind, and hiding the slot on skip/fail.
    // Loading skeleton is auto-derived from the ad layout (config.autoShimmer)
    private fun setupNativeAd() {
        val frAds = mBinding.root.findViewById<FrameLayout>(R.id.fr_ads) ?: return
        AdsManager.nativeHelper(
            this, this, "native_confirm_uninstall", AdRemoteConfig.native_confirm_uninstall,
            R.layout.layout_native_ad_medium,
        )
            .setNativeContentView(frAds)
            .requestAds(NativeAdParam.Request)
    }

    override fun onClickViews() {
        super.onClickViews()

        mBinding.imgBack.click {
            whenBack()
        }

        mBinding.btnTryAgain.click {
            whenBack()
        }

        mBinding.btnStillUninstall.click {
            Routes.startSurveyActivity(this)
            finish()
        }

    }


    private fun whenBack() {
        Routes.startMainActivity(this)
        finish()
    }

    override fun onBackPressed() {
        Routes.startMainActivity(this)
    }
}
