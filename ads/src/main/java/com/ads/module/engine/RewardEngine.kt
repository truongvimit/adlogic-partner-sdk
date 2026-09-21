package com.ads.module.engine

import android.app.Activity
import android.content.Context
import com.ads.module.admob.AppOpenManager
import com.ads.module.config.settings.AdBehavior
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdType
import com.ads.module.funtion.RewardCallback
import com.ads.module.helper.AdGate
import com.ads.module.tracking.TrackingAdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import io.trackkit.AdFormat
import java.util.concurrent.atomic.AtomicBoolean

internal object RewardEngine {

    fun load(context: Context, adUnitId: String, callback: AdCallback?) {
        val tracked = TrackingAdCallback.attach(adUnitId, AdFormat.REWARDED, callback)
        // Silent on purpose: AdWaterfall.canContinue already failed the caller in this tick.
        if (AdGate.engineBlocked(context)) return
        RewardedAd.load(
            context, adUnitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    rewardedAd.setOnPaidEventListener { adValue ->
                        onGmaPaid(
                            context, adValue, rewardedAd.adUnitId,
                            rewardedAd.responseInfo.mediationAdapterClassName, AdType.REWARDED,
                        )
                    }
                    tracked.onRewardAdLoaded(rewardedAd)
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    tracked.onAdFailedToLoad(loadAdError)
                }
            },
        )
    }

    fun show(activity: Activity, rewardedAd: RewardedAd?, callback: RewardCallback) {
        if (!AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(activity)) {
            callback.onUserEarnedReward(null)
            return
        }
        if (rewardedAd == null) {
            callback.onRewardedAdFailedToShow(0)
            return
        }
        val settled = AtomicBoolean(false)
        val presentationTracking =
            TrackingAdCallback.attach(rewardedAd.adUnitId, AdFormat.REWARDED, null)
        rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (!settled.compareAndSet(false, true)) return
                AppOpenManager.getInstance().setInterstitialShowing(false)
                callback.onRewardedAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                if (!settled.compareAndSet(false, true)) return
                AppOpenManager.getInstance().setInterstitialShowing(false)
                presentationTracking.onAdFailedToShow(adError)
                callback.onRewardedAdFailedToShow(adError.code)
            }

            override fun onAdShowedFullScreenContent() {
                if (settled.get()) return
                AppOpenManager.getInstance().setInterstitialShowing(true)
                presentationTracking.onAdImpression()
                callback.onRewardedAdShown()
            }

            override fun onAdImpression() {
                if (settled.get()) return
                callback.onAdImpression()
            }

            override fun onAdClicked() {
                if (settled.get()) return
                callback.onAdClicked()
                onGmaClick(activity, rewardedAd.adUnitId)
            }
        }
        rewardedAd.show(activity) { rewardItem -> callback.onUserEarnedReward(rewardItem) }
    }
}
