package com.ads.module;

import android.app.Application;

import com.ads.module.admob.AppOpenManager;
import com.ads.module.ads.ERainAd;
import com.ads.module.application.AdsMultiDexApplication;
import com.ads.module.config.ERainAdConfig;
import com.ads.module.funtion.AdCallback;
import com.ads.module.funtion.RewardCallback;
import com.google.android.gms.ads.rewarded.RewardItem;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Compiling is the assertion: every line below is a shape a Java host relies on. Nothing runs.
 */
public class JavaHostSurfaceTest {

    @Test
    public void javaHostSurfaceCompiles() {
    }

    @SuppressWarnings("unused")
    private void entryPoints(Application app) {
        ERainAd.getInstance().init(app, new ERainAdConfig(app, ERainAdConfig.ENVIRONMENT_DEVELOP));
        ERainAd.getInstance().setMaxClickAdsPerDay(3);
        Boolean organic = ERainAd.getInstance().getOrganic();
        AppOpenManager.getInstance().setResumeSkipPolicy(a -> null);
    }

    @SuppressWarnings("unused")
    private AdCallback anonymousAdCallback() {
        return new AdCallback() {
            @Override
            public void onAdLoaded() {
            }
        };
    }

    /** A host implements only the four abstract members; the two defaults stay untouched. */
    @SuppressWarnings("unused")
    private RewardCallback anonymousRewardCallback() {
        return new RewardCallback() {
            @Override
            public void onUserEarnedReward(RewardItem item) {
            }

            @Override
            public void onRewardedAdClosed() {
            }

            @Override
            public void onRewardedAdFailedToShow(int codeError) {
            }

            @Override
            public void onAdClicked() {
            }
        };
    }

    @SuppressWarnings("unused")
    private static class HostApplication extends AdsMultiDexApplication {
        void assignProtectedFields() {
            mERainAdConfig = null;
            listTestDevice = new ArrayList<>();
        }
    }
}
