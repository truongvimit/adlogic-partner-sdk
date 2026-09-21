package com.ads.module.engine

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.ads.module.admob.AppOpenManager
import com.ads.module.ads.wrapper.ApInterstitialAd
import com.ads.module.config.settings.AdBehavior
import com.ads.module.dialog.PrepareLoadingAdsDialog
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdType
import com.ads.module.funtion.AdmobHelper
import com.ads.module.helper.AdGate
import com.ads.module.tracking.TrackingAdCallback
import com.ads.module.util.SharePreferenceUtils
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import io.trackkit.AdFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object InterstitialEngine {
    private const val TAG = "ERainStudio"
    private const val MAX_CLICKS_PATH = "interstitial.frequency.max_clicks_per_24h"

    const val ERROR_CODE_SHOW_IN_BACKGROUND = 9001

    @Volatile
    private var maxClickAds = AdBehavior.defaultNumber(MAX_CLICKS_PATH).toInt()
    private var dialog: PrepareLoadingAdsDialog? = null

    @Volatile
    var openNextUnderAdDefault: Boolean = false
        get() = "UNDER_AD" == AdBehavior.text(
            "interstitial.presentation.next_screen_timing",
            if (field) "UNDER_AD" else "AFTER_AD",
        )

    fun isShowInBackgroundError(error: AdError?): Boolean =
        error != null && error.code == ERROR_CODE_SHOW_IN_BACKGROUND && TAG == error.domain

    fun setMaxClickAdsPerDay(maxClickAds: Int) {
        if (this.maxClickAds == maxClickAds) return
        this.maxClickAds = maxClickAds
        Log.i(
            TAG,
            if (maxClickAds > 0) {
                "Interstitial click cap enabled: $maxClickAds clicks/ad unit/day"
            } else {
                "Interstitial click cap disabled"
            },
        )
    }

    fun recordAdClick(context: Context?, adUnitId: String?) {
        if (effectiveMaxClicks() <= 0 || context == null || adUnitId.isNullOrEmpty()) return
        AdmobHelper.increaseNumClickAdsPerDay(context, adUnitId)
    }

    fun load(context: Context, adUnitId: String, callback: AdCallback?) {
        val tracked = TrackingAdCallback.attach(adUnitId, AdFormat.INTERSTITIAL, callback)
        if (AdGate.engineBlocked(context) || isClickCapReached(context, adUnitId)) {
            tracked.onApInterstitialLoad(null)
            return
        }
        InterstitialAd.load(
            context, adUnitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    tracked.onApInterstitialLoad(ApInterstitialAd(interstitialAd))
                    interstitialAd.setOnPaidEventListener { adValue ->
                        onGmaPaid(
                            context, adValue, interstitialAd.adUnitId,
                            interstitialAd.responseInfo.mediationAdapterClassName,
                            AdType.INTERSTITIAL,
                        )
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.i(TAG, loadAdError.message)
                    tracked.onAdFailedToLoad(loadAdError)
                }
            },
        )
    }

    fun show(
        context: Context,
        ad: ApInterstitialAd?,
        callback: AdCallback,
        openNextUnderAd: Boolean,
    ) {
        if (ad == null || !ad.isReady) {
            callback.onNextAction()
            return
        }
        val shownAd = ad.interstitialAd
        val adUnitId = shownAd?.adUnitId ?: ""
        val relay = object : AdCallback() {
            override fun onAdClosed() {
                callback.onAdClosed()
                ad.interstitialAd = null
            }

            override fun onNextAction() = callback.onNextAction()

            override fun onAdFailedToShow(adError: AdError?) {
                callback.onAdFailedToShow(adError)
                // A 9001 never reached GMA: keep the fill; the manager may restore it right here.
                if (!isShowInBackgroundError(adError)) ad.interstitialAd = null
            }

            override fun onAdClicked() = callback.onAdClicked()

            override fun onAdImpression() = callback.onAdImpression()

            override fun onInterstitialDisplayed() = callback.onInterstitialDisplayed()

            override fun usesActualInterstitialImpression() =
                callback.usesActualInterstitialImpression()

            override fun canShowInterstitial() = callback.canShowInterstitial()

            override fun onInterstitialShow() = callback.onInterstitialShow()
        }
        showCounted(
            context, shownAd, TrackingAdCallback.attach(adUnitId, AdFormat.INTERSTITIAL, relay),
            openNextUnderAd,
        )
    }

    private fun effectiveMaxClicks(): Int =
        AdBehavior.number(MAX_CLICKS_PATH, maxClickAds.toLong()).toInt()

    private fun isClickCapReached(context: Context?, adUnitId: String?): Boolean {
        if (effectiveMaxClicks() <= 0 || context == null || adUnitId.isNullOrEmpty()) return false
        val clicks = AdmobHelper.getNumClickAdsPerDay(context, adUnitId)
        if (clicks < effectiveMaxClicks()) return false
        Log.w(
            TAG,
            "Interstitial suppressed: ad unit hit the daily click cap (" + clicks + "/" +
                maxClickAds + "). Resets 24h after the window opened.",
        )
        return true
    }

    private fun showCounted(
        context: Context,
        interstitialAd: InterstitialAd?,
        callback: AdCallback,
        openNextUnderAd: Boolean,
    ) {
        if (!AdBehavior.bool("global.ads_enabled") || AdGate.isPurchased(context)) {
            callback.onNextAction()
            return
        }
        if (interstitialAd == null) {
            callback.onNextAction()
            return
        }

        interstitialAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                super.onAdDismissedFullScreenContent()
                AppOpenManager.getInstance().setInterstitialShowing(false)
                SharePreferenceUtils.setLastImpressionInterstitialTime(context)
                if (!openNextUnderAd) callback.onNextAction()
                callback.onAdClosed()
                dialog?.dismiss()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                super.onAdFailedToShowFullScreenContent(adError)
                AppOpenManager.getInstance().setInterstitialShowing(false)
                dialog?.dismiss()
                callback.onAdFailedToShow(adError)
                if (!openNextUnderAd) callback.onNextAction()
            }

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                AppOpenManager.getInstance().setInterstitialShowing(true)
                callback.onInterstitialDisplayed()
                if (!callback.usesActualInterstitialImpression()) callback.onAdImpression()
            }

            override fun onAdImpression() {
                if (callback.usesActualInterstitialImpression()) callback.onAdImpression()
            }

            override fun onAdClicked() {
                super.onAdClicked()
                callback.onAdClicked()
                onGmaClick(context, interstitialAd.adUnitId)
            }
        }

        if (!isClickCapReached(context, interstitialAd.adUnitId)) {
            showWithLoading(context, interstitialAd, callback, openNextUnderAd)
            return
        }
        callback.onNextAction()
    }

    private fun showWithLoading(
        context: Context,
        interstitialAd: InterstitialAd,
        callback: AdCallback,
        openNextUnderAd: Boolean,
    ) {
        val processState = ProcessLifecycleOwner.get().lifecycle.currentState
        if (!processState.isAtLeast(Lifecycle.State.RESUMED)) {
            notifyShowFailed(
                callback, ERROR_CODE_SHOW_IN_BACKGROUND, "Show fail: process is not resumed",
                openNextUnderAd,
            )
            return
        }
        if (context !is AppCompatActivity) {
            notifyShowFailed(
                callback, ERROR_CODE_SHOW_IN_BACKGROUND,
                "Show fail: context is not an AppCompatActivity", openNextUnderAd,
            )
            return
        }

        try {
            dialog?.let { if (it.isShowing) it.dismiss() }
            val loading = PrepareLoadingAdsDialog(context)
            dialog = loading
            loading.setCancelable(false)
            if (AdBehavior.bool("interstitial.presentation.loading_enabled")) loading.show()
            AppOpenManager.getInstance().setInterstitialShowing(true)
        } catch (e: Exception) {
            dialog = null
            Log.w(TAG, "showInterstitialAd: loading dialog unavailable, showing the ad anyway", e)
        }

        // Every path that reaches show() fires this: callers use it to read onNextAction's meaning.
        callback.onInterstitialShow()

        val preShowDelayMs = AdBehavior.number("interstitial.presentation.pre_show_delay_ms")
        adMainScope.launch {
            delay(preShowDelayMs)
            if (context.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (!callback.canShowInterstitial()) {
                    dialog?.dismiss()
                    notifyShowFailed(
                        callback, 0, "Interstitial policy changed before dispatch", openNextUnderAd,
                    )
                    return@launch
                }
                if (openNextUnderAd) {
                    // Same tick as show(): the next Activity must queue under the ad, not above it.
                    callback.onNextAction()
                    launch {
                        delay(1500)
                        dismissLoading(context)
                    }
                }
                interstitialAd.setImmersiveMode(true)
                interstitialAd.show(context)
            } else {
                dismissLoading(context)
                notifyShowFailed(
                    callback, ERROR_CODE_SHOW_IN_BACKGROUND,
                    "Show fail in background after show loading ad", openNextUnderAd,
                )
            }
        }
    }

    private fun dismissLoading(activity: AppCompatActivity) {
        val loading = dialog ?: return
        if (loading.isShowing && !activity.isDestroyed) loading.dismiss()
    }

    private fun notifyShowFailed(
        callback: AdCallback,
        code: Int,
        message: String,
        openNextUnderAd: Boolean,
    ) {
        AppOpenManager.getInstance().setInterstitialShowing(false)
        Log.e(TAG, "showInterstitialAd: $message")
        callback.onAdFailedToShow(AdError(code, message, TAG))
        if (!openNextUnderAd) callback.onNextAction()
    }
}
