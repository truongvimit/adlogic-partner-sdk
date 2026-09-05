package com.ads.module.helper.reward;

import android.app.Activity;

import com.ads.module.ads.ERainAd;
import com.ads.module.consent.ConsentCenter;
import com.ads.module.funtion.RewardCallback;
import com.ads.module.helper.Entitlement;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;

/** An existing Java implementation need not implement any of the additive default methods. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RewardCallbackJavaTest {
    @Test
    public void oldJavaConsumerReceivesOneFailureWhenAnotherRewardOwnsFullscreen() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ConsentCenter.setHostConsent(true, false);
        Entitlement.install(context -> false);
        RewardedAd first = Mockito.mock(RewardedAd.class);
        RewardedAd second = Mockito.mock(RewardedAd.class);
        Mockito.when(first.getAdUnitId()).thenReturn("java-first-unit");
        Mockito.when(second.getAdUnitId()).thenReturn("java-second-unit");
        ArgumentCaptor<FullScreenContentCallback> content = ArgumentCaptor.forClass(FullScreenContentCallback.class);
        LegacyCallback caller = new LegacyCallback();
        ERainAd.getInstance().showRewardAds(activity, first, new LegacyCallback());
        Mockito.verify(first).setFullScreenContentCallback(content.capture());
        try {
            ERainAd.getInstance().showRewardAds(activity, second, caller);

            assertEquals(1, caller.failures);
            assertEquals(0, caller.earned);
            assertEquals(0, caller.closed);
            Mockito.verify(second, Mockito.never()).show(Mockito.eq(activity), Mockito.any(OnUserEarnedRewardListener.class));
        } finally {
            content.getValue().onAdDismissedFullScreenContent();
            activity.finish();
        }
    }

    private static final class LegacyCallback implements RewardCallback {
        int failures;
        int earned;
        int closed;

        @Override public void onUserEarnedReward(RewardItem item) { earned++; }
        @Override public void onRewardedAdClosed() { closed++; }
        @Override public void onRewardedAdFailedToShow(int codeError) { failures++; }
        @Override public void onAdClicked() {}
    }
}
