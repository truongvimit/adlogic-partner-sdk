package com.ads.module.admob;

import android.content.Context;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.appopen.AppOpenAd;

/** Vendor request boundary; the manager keeps ownership of every returned callback. */
interface AppResumeAdLoader {
    void load(Context context, String unitId, AdRequest request,
              AppOpenAd.AppOpenAdLoadCallback callback);
}
