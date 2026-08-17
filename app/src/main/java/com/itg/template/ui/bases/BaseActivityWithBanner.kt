package com.itg.template.ui.bases

import android.os.Bundle
import android.widget.FrameLayout
import androidx.databinding.ViewDataBinding
import com.ads.module.helper.AdGate
import com.ads.module.helper.banner.BannerAdConfig
import com.ads.module.helper.banner.BannerAdHelper
import com.ads.module.helper.banner.BannerAdParam
import com.ads.module.helper.banner.BannerType
import com.itg.template.R
import com.itg.template.ads.AdUnitConfig
import com.itg.template.ui.bases.ext.goneView
import com.itg.template.ui.bases.ext.visibleView

data class BannerConfig(
    val adUnitConfig: AdUnitConfig = AdUnitConfig(
        id = "",
        isEnable = false,
        reloadIntervalSeconds = 0
    ),
    val isCollapse: Boolean = false
)

/**
 * Screens with a banner slot declare a [bannerConfig] and inherit the whole banner
 * lifecycle: [BannerAdHelper] owns load, waterfall fallback, reload-on-resume, the
 * auto-reload timer, and teardown.
 */
abstract class BaseActivityWithBanner<VB : ViewDataBinding> : BaseActivity<VB>() {

    abstract val bannerConfig: BannerConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBanner()
    }

    private fun setupBanner() {
        val frAds = findViewById<FrameLayout>(R.id.fr_banner) ?: return
        val unit = bannerConfig.adUnitConfig
        if (!unit.isEnable || AdGate.isPurchased(this)) {
            frAds.goneView()
            return
        }
        frAds.visibleView()
        val reloadSeconds = unit.reloadIntervalSeconds ?: 0
        val config = BannerAdConfig(
            unit.waterfallIds,
            canShowAds = unit.isEnable,
            canReloadAds = reloadSeconds > 0,
            bannerType = if (bannerConfig.isCollapse) BannerType.Collapsible() else BannerType.Normal,
        ).also {
            if (reloadSeconds > 0) {
                it.enableAutoReload = true
                it.autoReloadTime = reloadSeconds * 1000L
            }
        }
        BannerAdHelper(this, this, config)
            .attachInto(frAds)
            .also { it.placement = "banner_home" }
            .requestAds(BannerAdParam.Request)
    }
}
