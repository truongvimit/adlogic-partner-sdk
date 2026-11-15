package com.ads.module.ads.wrapper;

import com.ads.module.config.settings.AdBehavior;
import android.view.View;

import com.google.android.gms.ads.nativead.NativeAd;

public class NativeAdWrapper extends AdWrapperBase {
    private final long loadedAtMs = android.os.SystemClock.elapsedRealtime();
    private boolean destroyed;
    private volatile boolean impressed;
    private volatile boolean clicked;

    /** The vendor reported an impression for this ad, on whichever view showed it. */
    public boolean wasImpressed() {
        return impressed;
    }

    /** The vendor reported a click or an open for this ad. */
    public boolean wasClicked() {
        return clicked;
    }

    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP)
    public void markImpressed() {
        impressed = true;
    }

    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP)
    public void markClicked() {
        clicked = true;
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
        nativeView = null;
        if (ad != null) ad.destroy();
    }

    private int layoutCustomNative;
    private View nativeView;
    private NativeAd admobNativeAd;

    public NativeAdWrapper(StatusAd status) {
        super(status);
    }

    public NativeAdWrapper(int layoutCustomNative, View nativeView) {
        this.layoutCustomNative = layoutCustomNative;
        this.nativeView = nativeView;
        status = StatusAd.AD_LOADED;
    }

    public NativeAdWrapper(int layoutCustomNative, NativeAd admobNativeAd) {
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

    public NativeAdWrapper() {
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
