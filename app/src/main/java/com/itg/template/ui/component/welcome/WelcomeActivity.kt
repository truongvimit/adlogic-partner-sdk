package com.itg.template.ui.component.welcome

import com.ads.module.helper.adnative.NativeAdParam
import com.ads.module.helper.interstitial.InterNextAction
import com.itg.template.R
import com.ads.module.config.AdRemoteConfig
import com.itg.template.ads.AdsManager
import com.itg.template.ads.native_welcome
import com.itg.template.databinding.ActivityWelcomeBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {
    override fun getLayoutActivity(): Int {
        return R.layout.activity_welcome
    }

    override fun initViews() {
        super.initViews()
        // The helper owns the native from here: gate, load, bind, hide on skip/fail/offline.
        // Loading skeleton is auto-derived from the ad layout (config.autoShimmer)
        AdsManager.nativeHelper(
            this, this, "native_welcome", AdRemoteConfig.native_welcome,
            R.layout.layout_native_welcome,
        )
            .setNativeContentView(mBinding.frAds)
            .requestAds(NativeAdParam.Request)
        AdsManager.loadInterWelcome(this)
    }

    override fun onClickViews() {
        super.onClickViews()
        mBinding.btnStart.click {
            // AfterDismiss because the only thing this screen does afterwards is finish itself:
            // under the default UnderAd timing that finish would land while the ad is still on
            // screen, tearing the host out from under it.
            AdsManager.showInterWelcome(this, nextAction = InterNextAction.AfterDismiss) {
                finish()
            }
        }
    }
}
