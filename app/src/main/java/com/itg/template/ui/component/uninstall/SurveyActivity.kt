package com.itg.template.ui.component.uninstall

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import com.ads.module.ads.wrapper.ApNativeAd
import com.itg.template.R
import com.itg.template.ads.AdsManager
import com.itg.template.ads.populateNativeAdView
import com.itg.template.databinding.ActivitySurveyBinding
import com.itg.template.ui.bases.BaseActivity
import com.itg.template.ui.bases.ext.click
import com.itg.template.ui.bases.ext.goneView
import com.itg.template.ui.bases.ext.visibleView
import com.itg.template.utils.Routes
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SurveyActivity : BaseActivity<ActivitySurveyBinding>() {

    override fun getLayoutActivity() = R.layout.activity_survey

    override fun initViews() {
        super.initViews()
        AdsManager.loadNativeSurvey(this, R.layout.layout_native_ad_medium)
    }

    override fun observerData() {
        super.observerData()
        AdsManager.nativeSurveyAdLive.observe(this) { ad -> renderSurveyAd(ad) }
    }

    private fun renderSurveyAd(ad: ApNativeAd?) {
        val frAds = mBinding.root.findViewById<android.widget.FrameLayout>(R.id.fr_ads)
            ?: return
        if (ad == null) {
            frAds.goneView()
            return
        }
        frAds.visibleView()
        val shimmer = mBinding.root.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(R.id.shimmer_ads)
        if (shimmer != null) {
            populateNativeAdView(this, ad, frAds, shimmer)
        }
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
