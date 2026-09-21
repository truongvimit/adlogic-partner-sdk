package com.ads.module.ads.wrapper;

import com.ads.module.config.settings.AdBehavior;

import com.google.android.gms.ads.nativead.NativeAd;

public class ApNativeAd {
    private final long loadedAtMs = android.os.SystemClock.elapsedRealtime();
    private boolean destroyed;

    private int layoutCustomNative;
    private NativeAd admobNativeAd;

    public ApNativeAd(int layoutCustomNative, NativeAd admobNativeAd) {
        this.layoutCustomNative = layoutCustomNative;
        this.admobNativeAd = admobNativeAd;
    }

    public boolean isReady() {
        return admobNativeAd != null;
    }

    public boolean isUsable() {
        return !destroyed && isReady()
                && android.os.SystemClock.elapsedRealtime() - loadedAtMs < AdBehavior.number("native.cache.max_age_ms");
    }

    /** Releases a consumed or expired ad exactly once. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        NativeAd ad = admobNativeAd;
        admobNativeAd = null;
        if (ad != null) ad.destroy();
    }

    public NativeAd getAdmobNativeAd() {
        return admobNativeAd;
    }

    public int getLayoutCustomNative() {
        return layoutCustomNative;
    }

    public void setLayoutCustomNative(int layoutCustomNative) {
        this.layoutCustomNative = layoutCustomNative;
    }
}
