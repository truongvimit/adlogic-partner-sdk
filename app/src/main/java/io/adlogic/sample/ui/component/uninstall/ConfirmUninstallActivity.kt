package io.adlogic.sample.ui.component.uninstall

import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdHelper
import io.adlogic.sample.R
import io.adlogic.sample.ads.AppAdPlacement
import io.adlogic.sample.databinding.ActivityConfirmUninstallBinding
import io.adlogic.sample.ui.bases.BaseActivity
import io.adlogic.sample.ui.bases.ext.click
import io.adlogic.sample.utils.Routes
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
        NativeAdHelper.forPlacement(
            this, this, AppAdPlacement.NATIVE_CONFIRM_UNINSTALL, frAds, R.layout.layout_native_ad_medium,
        )
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
