package com.itg.template.ui.bases

import android.os.Bundle
import android.widget.FrameLayout
import androidx.databinding.ViewDataBinding
import com.ads.module.helper.AdGate
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.banner.BannerType
import com.itg.template.R
import com.itg.template.ads.AppAdPlacement
import com.itg.template.ui.bases.ext.goneView
import com.itg.template.ui.bases.ext.visibleView

data class BannerConfig(
    val placement: String = AppAdPlacement.BANNER_HOME,
    val bannerType: BannerType = BannerType.Normal,
)

/**
 * Screens with a banner slot declare a [bannerConfig] and inherit the whole banner
 * lifecycle: [BannerAdHelper] owns config resolution, load, waterfall fallback and teardown.
 * This sample uses AdMob console refresh, so it leaves the SDK timer off.
 */
abstract class BaseActivityWithBanner<VB : ViewDataBinding> : BaseActivity<VB>() {

    abstract val bannerConfig: BannerConfig

    private var bannerAdHelper: BannerAdHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBanner(bannerConfig.bannerType, bannerConfig.placement)
    }

    /** Rebuilds the slot with [type] — a helper's [BannerType] is fixed, so a switch needs a new one. */
    protected fun reloadBanner(
        type: BannerType,
        placement: String = bannerConfig.placement,
    ) {
        // A dead target must not cost the live banner — validate before retiring
        if (!AdGate.placementEnabled(placement) || AdGate.isPurchased(this)) return
        bannerAdHelper?.let {
            // cancel() alone is not final: an auto-reload config resurrects on the next resume
            it.flagUserEnableReload = false
            it.cancel()
        }
        setupBanner(type, placement)
    }

    private fun setupBanner(type: BannerType, placement: String) {
        val frAds = findViewById<FrameLayout>(R.id.fr_banner) ?: return
        if (!AdGate.placementEnabled(placement) || AdGate.isPurchased(this)) {
            frAds.goneView()
            return
        }
        frAds.visibleView()
        bannerAdHelper = BannerAdHelper.forPlacement(this, this, placement, frAds, type)
    }
}
