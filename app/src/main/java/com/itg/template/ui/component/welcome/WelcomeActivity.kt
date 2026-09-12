package com.itg.template.ui.component.welcome

import com.ads.module.helper.adnative.NativeAdHelper
import com.ads.module.helper.AdSkipReason
import com.ads.module.helper.interstitial.InterNextAction
import com.ads.module.helper.interstitial.InterShowCallback
import com.ads.module.helper.interstitial.InterstitialAdManager
import com.itg.template.R
import com.itg.template.ads.AppAdPlacement
import com.itg.template.databinding.ActivityWelcomeBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {
    override fun getLayoutActivity(): Int {
        return R.layout.activity_welcome
    }

    override fun initViews() {
        super.initViews()
        // The helper owns the native from here: gate, load, bind, hide on skip/fail/offline.
        // Loading skeleton is auto-derived from the ad layout (config.autoShimmer)
        NativeAdHelper.forPlacement(
            this, this, AppAdPlacement.NATIVE_WELCOME, mBinding.frAds, R.layout.layout_native_welcome,
        )
        InterstitialAdManager.load(this, AppAdPlacement.INTER_WELCOME)
    }

    override fun onClickViews() {
        super.onClickViews()
        mBinding.btnStart.click {
            // AfterDismiss because the only thing this screen does afterwards is finish itself:
            // under the default UnderAd timing that finish would land while the ad is still on
            // screen, tearing the host out from under it.
            InterstitialAdManager.show(
                this,
                AppAdPlacement.INTER_WELCOME,
                object : InterShowCallback() {
                    override fun onSkipped(reason: AdSkipReason) {
                        if (reason == AdSkipReason.FAILED_TO_SHOW) {
                            Timber.w("Interstitial show failed")
                        }
                    }

                    override fun onComplete() = finish()
                },
                nextAction = InterNextAction.AfterDismiss,
            )
        }
    }
}
