package com.ads.module.engine

import android.content.Context
import com.ads.module.ads.ERainAd
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.settings.AdBehavior
import com.ads.module.event.ERainLogEventManager
import com.ads.module.funtion.AdType
import com.google.android.gms.ads.AdValue

// Run before the host callback: it may clear or replace this one-shot resume skip.
internal fun suppressResumeAfterAdClick() {
    val skipResume = ERainAd.skipResumeAfterAdClick
    if (AdBehavior.bool("app_open.presentation.skip_after_ad_click", skipResume)) {
        AppOpenManager.getInstance().disableAdResumeByClickAction()
    }
}

// Each format keeps its existing logging order relative to the host callback.
internal fun logGmaClick(context: Context?, adUnitId: String?) {
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
