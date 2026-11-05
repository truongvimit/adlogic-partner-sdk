package com.ads.module.ads.wrapper;

public abstract class AdWrapperBase {
    protected StatusAd status = StatusAd.AD_INIT;

    public AdWrapperBase(StatusAd status) {
        this.status = status;
    }

    public AdWrapperBase() {
    }

    public StatusAd getStatus() {
        return status;
    }

    public void setStatus(StatusAd status) {
        this.status = status;
    }


    abstract boolean isReady();

    public boolean isNotReady() {
        return !isReady();
    }

    public boolean isLoading() {
        return status == StatusAd.AD_LOADING;
    }
}
