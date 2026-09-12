package com.ads.module.funtion;

import com.google.android.gms.ads.rewarded.RewardItem;

public interface RewardCallback {
    /** The vendor reported that the rewarded ad reached the screen. */
    default void onRewardedAdShown() {}

    /** The vendor reported an impression for the rewarded ad. */
    default void onAdImpression() {}

    void onUserEarnedReward(RewardItem var1);

    void onRewardedAdClosed();

    void onRewardedAdFailedToShow(int codeError);

    void onAdClicked();
}
