package com.ads.module.funtion

import com.google.android.gms.ads.rewarded.RewardItem

interface RewardCallback {
    /** The vendor reported that the rewarded ad reached the screen. */
    fun onRewardedAdShown() {}

    /** The vendor reported an impression for the rewarded ad. */
    fun onAdImpression() {}

    fun onUserEarnedReward(var1: RewardItem?)

    fun onRewardedAdClosed()

    fun onRewardedAdFailedToShow(codeError: Int)

    fun onAdClicked()
}
