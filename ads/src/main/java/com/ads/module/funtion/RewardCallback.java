package com.ads.module.funtion;

import androidx.annotation.Nullable;
import com.ads.module.helper.AdSkipReason;
import com.google.android.gms.ads.rewarded.RewardItem;

public interface RewardCallback {
    void onUserEarnedReward(RewardItem var1);

    void onRewardedAdClosed();

    void onRewardedAdFailedToShow(int codeError);

    void onAdClicked();

    /**
     * Declined before invoking the vendor; the ad has not been consumed.
     * Existing consumers receive their usual failure callback once, except a newly premium
     * user keeps the raw reward API's automatic earn without a close callback.
     */
    default void onAdShowRejected(AdSkipReason reason) {
        if (reason == AdSkipReason.PURCHASED) onUserEarnedReward(null);
        else onRewardedAdFailedToShow(0);
    }

    /** The vendor confirmed presentation. Earning remains a separate callback. */
    default void onAdPresented() {
    }

    /**
     * Current cache/session admission, checked after setup and immediately before vendor show.
     * The default {@code null} adds no denial. A non-null reason invokes
     * {@link #onAdShowRejected(AdSkipReason)} before consuming the ad; a thrown exception rejects
     * with {@link AdSkipReason#PREPARATION_FAILED} through the same callback.
     */
    @Nullable
    default AdSkipReason getAdShowSkipReason() {
        return null;
    }
}
