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
import com.ads.module.config.AdUnitConfig
import com.itg.template.ui.bases.ext.goneView
import com.itg.template.ui.bases.ext.visibleView

data class BannerConfig(
    val adUnitConfig: AdUnitConfig = AdUnitConfig(
        id = "",
        isEnable = false,
        reloadIntervalSeconds = 0
    ),
    val bannerType: BannerType = BannerType.Normal
)

/**
 * Screens with a banner slot declare a [bannerConfig] and inherit the whole banner
 * lifecycle: [BannerAdHelper] owns load, waterfall fallback, reload-on-resume, the
 * auto-reload timer, and teardown.
 */
abstract class BaseActivityWithBanner<VB : ViewDataBinding> : BaseActivity<VB>() {

    abstract val bannerConfig: BannerConfig

    private var bannerAdHelper: BannerAdHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBanner(bannerConfig.bannerType, bannerConfig.adUnitConfig, DEFAULT_PLACEMENT)
    }

    /** Rebuilds the slot with [type] — a helper's [BannerType] is fixed, so a switch needs a new one. */
    protected fun reloadBanner(
        type: BannerType,
        adUnitConfig: AdUnitConfig = bannerConfig.adUnitConfig,
        placement: String = DEFAULT_PLACEMENT,
    ) {
        // A dead target must not cost the live banner — validate before retiring
        if (!adUnitConfig.isEnable || AdGate.isPurchased(this)) return
        bannerAdHelper?.let {
            // cancel() alone is not final: an auto-reload config resurrects on the next resume
            it.flagUserEnableReload = false
            it.cancel()
        }
        setupBanner(type, adUnitConfig, placement)
    }

    private fun setupBanner(type: BannerType, unit: AdUnitConfig, placement: String) {
        val frAds = findViewById<FrameLayout>(R.id.fr_banner) ?: return
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
            bannerType = type,
        ).also {
            if (reloadSeconds > 0) {
                it.enableAutoReload = true
                it.autoReloadTime = reloadSeconds * 1000L
            }
        }
        bannerAdHelper = BannerAdHelper(this, this, config)
            .attachInto(frAds)
            .also {
                it.placement = placement
                it.requestAds(BannerAdParam.Request)
            }
    }

    companion object {
        private const val DEFAULT_PLACEMENT = "banner_home"
    }
}
