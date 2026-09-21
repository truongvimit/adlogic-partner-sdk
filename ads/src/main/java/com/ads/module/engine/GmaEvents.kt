package com.ads.module.engine

import android.content.Context
import com.ads.module.admob.Admob
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.settings.AdBehavior
import com.ads.module.event.ERainLogEventManager
import com.ads.module.funtion.AdType
import com.google.android.gms.ads.AdValue

// Does not notify the AdCallback: each format keeps its own order around this call.
internal fun onGmaClick(context: Context?, adUnitId: String?) {
    val skipResume = Admob.getInstance().isDisableAdResumeWhenClickAds
    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", skipResume)) {
        AppOpenManager.getInstance().disableAdResumeByClickAction()
    }
    ERainLogEventManager.logClickAdsEvent(context, adUnitId)
}

internal fun onGmaPaid(
    context: Context?,
    adValue: AdValue,
    adUnitId: String?,
    adapterClassName: String?,
    type: AdType,
) {
    ERainLogEventManager.logPaidAdImpression(context, adValue, adUnitId, adapterClassName, type)
    ERainLogEventManager.logPaidAdjustWithToken(adValue, adUnitId)
}
