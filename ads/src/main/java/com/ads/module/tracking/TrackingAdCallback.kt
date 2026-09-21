package com.ads.module.tracking

import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.funtion.AdCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import io.trackkit.AdFormat
import io.trackkit.PlacementRegistry
import io.trackkit.Tracker
import io.trackkit.TrackkitEvents
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decorator that emits the ad lifecycle into Trackkit and always forwards to the wrapped callback.
 *
 * It also registers the ad unit against its placement and format, which is what lets the paid and
 * click bridges attribute an impression to a screen — AdMob's callbacks only know the ad unit.
 *
 * `onInterstitialShow` marks the navigation commitment before vendor show; it only forwards to the
 * delegate. Actual display is reported by `onAdImpression`, including the fullscreen vendor shown
 * callback routed through that hook.
 *
 * `ad_click` is deliberately not emitted here — `ERainLogEventManager.logClickAdsEvent` owns it,
 * from the vendor callback that every click path reaches.
 *
 * Applied by the ads SDK itself, never by the host app: the layer that creates the ad object
 * attaches the instrumentation, exactly as it attaches `OnPaidEventListener`.
 */
open class TrackingAdCallback(
    placement: String?,
    format: AdFormat?,
    private val adUnitId: String?,
    private val delegate: AdCallback?,
) : AdCallback() {

    private val placement: String = if (placement.isNullOrEmpty()) "unknown" else placement
    private val format: AdFormat = format ?: AdFormat.UNKNOWN
    private val requestedAtMs = System.currentTimeMillis()

    private val loadedReported = AtomicBoolean(false)
    private val loadFailedReported = AtomicBoolean(false)
    private val shownReported = AtomicBoolean(false)
    private val showFailedReported = AtomicBoolean(false)
    private val closedReported = AtomicBoolean(false)

    init {
        PlacementRegistry.register(adUnitId, this.placement)
        AdFormatRegistry.register(adUnitId, this.format)
    }

    override fun onAdLoaded() {
        reportLoaded()
        delegate?.onAdLoaded()
    }

    override fun onApInterstitialLoad(apInterstitialAd: ApInterstitialAd?) {
        reportLoaded()
        delegate?.onApInterstitialLoad(apInterstitialAd)
    }

    override fun onRewardAdLoaded(rewardedAd: RewardedAd?) {
        reportLoaded()
        delegate?.onRewardAdLoaded(rewardedAd)
    }

    override fun onNativeAdLoaded(nativeAd: ApNativeAd) {
        reportLoaded()
        delegate?.onNativeAdLoaded(nativeAd)
    }

    override fun onAdFailedToLoad(i: LoadAdError?) {
        reportLoadFailed(i?.code)
        delegate?.onAdFailedToLoad(i)
    }

    override fun onAdImpression() {
        reportShown()
        delegate?.onAdImpression()
    }

    override fun onInterstitialShow() {
        delegate?.onInterstitialShow()
    }

    override fun onInterstitialDisplayed() {
        delegate?.onInterstitialDisplayed()
    }

    override fun usesActualInterstitialImpression(): Boolean =
        delegate != null && delegate.usesActualInterstitialImpression()

    override fun canShowInterstitial(): Boolean = delegate == null || delegate.canShowInterstitial()

    override fun onAdFailedToShow(adError: AdError?) {
        reportShowFailed(adError)
        delegate?.onAdFailedToShow(adError)
    }

    override fun onAdClicked() {
        delegate?.onAdClicked()
    }

    override fun onAdClosed() {
        if (closedReported.compareAndSet(false, true) && telemetryEnabled()) {
            Tracker.track(TrackkitEvents.Ad.Closed(placement, format, adUnitId))
        }
        delegate?.onAdClosed()
    }

    override fun onAdOpened() {
        delegate?.onAdOpened()
    }

    override fun onNextAction() {
        delegate?.onNextAction()
    }

    private fun telemetryEnabled() = AdBehavior.bool("diagnostics.ads_telemetry_enabled")

    private fun reportLoaded() {
        if (loadedReported.compareAndSet(false, true) && telemetryEnabled()) {
            Tracker.track(
                TrackkitEvents.Ad.Loaded(
                    placement, format, adUnitId, System.currentTimeMillis() - requestedAtMs
                )
            )
        }
    }

    private fun reportLoadFailed(errorCode: Int?) {
        if (loadFailedReported.compareAndSet(false, true) && telemetryEnabled()) {
            Tracker.track(TrackkitEvents.Ad.LoadFailed(placement, format, adUnitId, errorCode))
        }
    }

    private fun reportShown() {
        if (shownReported.compareAndSet(false, true) && telemetryEnabled()) {
            Tracker.track(TrackkitEvents.Ad.Show(placement, format, adUnitId))
        }
    }

    private fun reportShowFailed(adError: AdError?) {
        if (showFailedReported.compareAndSet(false, true) && telemetryEnabled()) {
            Tracker.track(
                TrackkitEvents.Ad.ShowFailed(placement, format, adUnitId, adError?.code)
            )
        }
    }

    companion object {
        /** Wraps [delegate] once; a callback already wrapped is returned untouched. */
        @JvmStatic
        fun attach(adUnitId: String, format: AdFormat, delegate: AdCallback?): AdCallback =
            if (delegate is TrackingAdCallback) delegate
            else TrackingAdCallback(PlacementRegistry.placementOf(adUnitId), format, adUnitId, delegate)
    }
}
