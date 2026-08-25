package com.ads.module.application;

import android.app.Application;

import com.ads.module.config.ERainAdConfig;
import com.ads.module.util.SharePreferenceUtils;

import java.util.ArrayList;
import java.util.List;

// Name kept for source compatibility. minSdk 24 has native multidex, so the androidx
// MultiDexApplication superclass was a no-op.
public abstract class AdsMultiDexApplication extends Application {

    protected ERainAdConfig mERainAdConfig;
    protected List<String> listTestDevice;

    @Override
    public void onCreate() {
        super.onCreate();
        listTestDevice = new ArrayList<String>();
        mERainAdConfig = new ERainAdConfig(this);
        if (SharePreferenceUtils.getInstallTime(this) == 0) {
            SharePreferenceUtils.setInstallTime(this);
        }
    }


}
