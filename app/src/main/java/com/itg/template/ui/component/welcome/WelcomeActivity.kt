package com.itg.template.ui.component.welcome

import com.ads.module.ads.wrapper.ApNativeAd
import com.itg.template.R
import com.itg.template.ads.AdsManager
import com.itg.template.ads.populateNativeAdView
import com.itg.template.databinding.ActivityWelcomeBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import com.itg.template.ui.bases.ext.goneView
import com.itg.template.ui.bases.ext.isNetwork
import com.itg.template.ui.bases.ext.visibleView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {
    override fun getLayoutActivity(): Int {
        return R.layout.activity_welcome
    }

    override fun initViews() {
        super.initViews()
        AdsManager.loadNativeWelcome(this, R.layout.layout_native_welcome)
        AdsManager.loadInterWelcome(this)
    }

    override fun observerData() {
        super.observerData()
        AdsManager.nativeWelcomeAdLive.observe(this) { ad ->
            renderWelcomeAd(ad)
        }
    }

    override fun onClickViews() {
        super.onClickViews()
        mBinding.btnStart.click {
            AdsManager.showInterWelcome(this) {
                finish()
            }
        }
    }

    private fun renderWelcomeAd(ad: ApNativeAd?) {
        if (ad == null || !isNetwork()) {
            mBinding.frAds.goneView()
            return
        }
        mBinding.frAds.visibleView()
        populateNativeAdView(
            this,
            ad,
            mBinding.frAds,
            mBinding.shimmerAds.shimmerNativeLarge
        )
    }
}
