package com.ads.module.ads.wrapper;

import android.view.View;

import com.google.android.gms.ads.nativead.NativeAd;

public class ApNativeAd extends ApAdBase {
    private final long loadedAtMs = android.os.SystemClock.elapsedRealtime();
    private boolean destroyed;

    public boolean isUsable() {
        return !destroyed && isReady()
                && android.os.SystemClock.elapsedRealtime() - loadedAtMs < 60 * 60 * 1000L;
    }

    /** Releases a consumed or expired ad exactly once. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        NativeAd ad = admobNativeAd;
        admobNativeAd = null;
        nativeView = null;
        if (ad != null) ad.destroy();
    }

    private int layoutCustomNative;
    private View nativeView;
    private NativeAd admobNativeAd;

    public ApNativeAd(StatusAd status) {
        super(status);
    }

    public ApNativeAd(int layoutCustomNative, View nativeView) {
        this.layoutCustomNative = layoutCustomNative;
        this.nativeView = nativeView;
        status = StatusAd.AD_LOADED;
    }

    public ApNativeAd(int layoutCustomNative, NativeAd admobNativeAd) {
        this.layoutCustomNative = layoutCustomNative;
        this.admobNativeAd = admobNativeAd;
        status = StatusAd.AD_LOADED;
    }

    public NativeAd getAdmobNativeAd() {
        return admobNativeAd;
    }

    public void setAdmobNativeAd(NativeAd admobNativeAd) {
        this.admobNativeAd = admobNativeAd;
        if (admobNativeAd != null)
            status = StatusAd.AD_LOADED;
    }

    public ApNativeAd() {
    }


    @Override
    boolean isReady() {
        return nativeView != null || admobNativeAd != null;
    }


    public int getLayoutCustomNative() {
        return layoutCustomNative;
    }

    public void setLayoutCustomNative(int layoutCustomNative) {
        this.layoutCustomNative = layoutCustomNative;
    }

    public View getNativeView() {
        return nativeView;
    }

    public void setNativeView(View nativeView) {
        this.nativeView = nativeView;
    }

    public String toString() {
        return "Status:" + status + " == nativeView:" + nativeView + " == admobNativeAd:" + admobNativeAd;
    }

}
