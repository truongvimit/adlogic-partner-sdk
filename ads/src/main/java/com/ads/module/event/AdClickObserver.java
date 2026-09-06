package com.ads.module.event;

/** Immediate vendor-click evidence, before analytics buffering. Never receives or retains an Activity. */
public interface AdClickObserver {
    void onAdClick(String clickId, String adUnitId);
}
