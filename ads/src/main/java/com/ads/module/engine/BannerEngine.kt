package com.ads.module.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.ads.module.ads.ERainAd
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdType
import com.ads.module.helper.AdGate
import com.ads.module.helper.banner.BannerType
import com.ads.module.tracking.TrackingAdCallback
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import io.trackkit.AdFormat

internal object BannerEngine {
    private const val TAG = "ERainStudio"
    private const val MAX_SMALL_INLINE_BANNER_HEIGHT = 50

    @SuppressLint("VisibleForTests")
    fun load(
        activity: Activity,
        adUnitId: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        type: BannerType,
        callback: AdCallback?,
    ) {
        val format =
            if (type is BannerType.Collapsible) AdFormat.COLLAPSIBLE_BANNER else AdFormat.BANNER
        val tracked = TrackingAdCallback.attach(adUnitId, format, callback)
        when (type) {
            BannerType.Normal -> {
                val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                    activity, adWidthDp(activity),
                )
                loadSized(activity, adUnitId, container, shimmer, adSize, adSize.height, tracked)
            }

            BannerType.LargeAnchored -> {
                val adSize =
                    AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, adWidthDp(activity))
                loadSized(activity, adUnitId, container, shimmer, adSize, adSize.height, tracked)
            }

            is BannerType.Inline -> {
                val adSize = inlineAdSize(activity, type.style)
                val shimmerHeightDp =
                    if (BannerType.Inline.SMALL_STYLE.equals(type.style, ignoreCase = true)) {
                        MAX_SMALL_INLINE_BANNER_HEIGHT
                    } else {
                        adSize.height
                    }
                loadSized(activity, adUnitId, container, shimmer, adSize, shimmerHeightDp, tracked)
            }

            is BannerType.InlineMaxHeight -> {
                val adSize =
                    AdSize.getInlineAdaptiveBannerAdSize(adWidthDp(activity), type.maxHeightDp)
                loadSized(
                    activity, adUnitId, container, shimmer, adSize,
                    shimmerHeightDp = type.maxHeightDp, callback = tracked,
                )
            }

            is BannerType.Fixed -> {
                val adSize = type.size.adSize
                loadSized(activity, adUnitId, container, shimmer, adSize, adSize.height, tracked)
            }

            is BannerType.Collapsible ->
                loadCollapsible(activity, adUnitId, type.gravity, container, shimmer, tracked)
        }
    }

    private fun loadSized(
        activity: Activity,
        adUnitId: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        adSize: AdSize,
        shimmerHeightDp: Int,
        callback: AdCallback,
    ) {
        if (AdGate.engineBlocked(activity)) {
            endDeclined(container, shimmer, callback)
            return
        }

        shimmer.visibility = View.VISIBLE
        shimmer.startShimmer()
        var createdView: AdView? = null
        var callbackEntered = false
        var aborted = false
        try {
            val adView = AdView(activity)
            createdView = adView
            adView.adUnitId = adUnitId
            // Fixed sizes narrower than the window must not hug the start edge.
            container.addView(
                adView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL,
                ),
            )
            // Uncapped inline adaptive reports 0 until a creative arrives: keep the host's height.
            if (shimmerHeightDp > 0) reserveHeight(shimmer, shimmerHeightDp)
            adView.setAdSize(adSize)
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    if (aborted) return
                    hideSlot(container, shimmer)
                    callbackEntered = true
                    callback.onAdFailedToLoad(loadAdError)
                }

                override fun onAdLoaded() {
                    if (aborted) return
                    showLoaded(adView, container, shimmer)
                    callbackEntered = true
                    callback.onAdLoaded()
                }

                override fun onAdClicked() {
                    if (aborted) return
                    super.onAdClicked()
                    suppressResumeAfterAdClick()
                    callbackEntered = true
                    callback.onAdClicked()
                    Log.d(TAG, "onAdClicked")
                    logGmaClick(ERainAd.appContext, adUnitId)
                }

                override fun onAdImpression() {
                    if (aborted) return
                    super.onAdImpression()
                    callbackEntered = true
                    callback.onAdImpression()
                }
            }

            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            Log.w(TAG, "Banner dispatch failed", e)
            // A synchronous host callback already settled the helper; do not fail it again.
            if (!callbackEntered) {
                aborted = true
                endLoadException(createdView, container, shimmer, callback)
            }
        }
    }

    private fun loadCollapsible(
        activity: Activity,
        adUnitId: String,
        gravity: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        callback: AdCallback,
    ) {
        if (AdGate.engineBlocked(activity)) {
            endDeclined(container, shimmer, callback)
            return
        }

        shimmer.visibility = View.VISIBLE
        shimmer.startShimmer()
        var createdView: AdView? = null
        var callbackEntered = false
        var aborted = false
        try {
            val adView = AdView(activity)
            createdView = adView
            adView.adUnitId = adUnitId
            container.addView(adView)
            val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                activity, adWidthDp(activity),
            )
            reserveHeight(shimmer, adSize.height)
            adView.setAdSize(adSize)
            adView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    if (aborted) return
                    super.onAdFailedToLoad(loadAdError)
                    hideSlot(container, shimmer)
                    callbackEntered = true
                    callback.onAdFailedToLoad(loadAdError)
                }

                override fun onAdLoaded() {
                    if (aborted) return
                    showLoaded(adView, container, shimmer)
                    callbackEntered = true
                    callback.onAdLoaded()
                }

                override fun onAdClicked() {
                    if (aborted) return
                    super.onAdClicked()
                    suppressResumeAfterAdClick()
                    logGmaClick(ERainAd.appContext, adUnitId)
                    callbackEntered = true
                    callback.onAdClicked()
                }
            }
            adView.loadAd(collapsibleRequest(gravity))
        } catch (e: Exception) {
            Log.w(TAG, "Collapsible banner dispatch failed", e)
            if (!callbackEntered) {
                aborted = true
                endLoadException(createdView, container, shimmer, callback)
            }
        }
    }

    private fun endLoadException(
        adView: AdView?,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        callback: AdCallback,
    ) {
        // Retire only this attempt before its failure callback can synchronously load a fallback.
        if (adView != null) {
            container.removeView(adView)
            try {
                adView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed banner could not be destroyed", e)
            }
        }
        try {
            endDeclined(container, shimmer, callback)
        } catch (e: Exception) {
            // Preserve dispatch-time callback containment without a second terminal notification.
            Log.w(TAG, "Banner failure callback threw", e)
        }
    }

    // Ends like a no-fill: returning silently would strand BannerAdHelper in Loading.
    private fun endDeclined(
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
        callback: AdCallback,
    ) {
        hideSlot(container, shimmer)
        callback.onAdFailedToLoad(null)
    }

    private fun hideSlot(container: FrameLayout, shimmer: ShimmerFrameLayout) {
        shimmer.stopShimmer()
        shimmer.visibility = View.GONE
        container.visibility = View.GONE
    }

    private fun showLoaded(
        adView: AdView,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout,
    ) {
        Log.d(TAG, "Banner adapter class name: " + adView.responseInfo!!.mediationAdapterClassName)
        shimmer.stopShimmer()
        shimmer.visibility = View.GONE
        container.visibility = View.VISIBLE
        adView.onPaidEventListener = OnPaidEventListener { adValue ->
            Log.d(TAG, "OnPaidEvent banner:" + adValue.valueMicros)
            onGmaPaid(
                ERainAd.appContext, adValue, adView.adUnitId,
                adView.responseInfo!!.mediationAdapterClassName, AdType.BANNER,
            )
        }
    }

    private fun reserveHeight(shimmer: ShimmerFrameLayout, heightDp: Int) {
        val params = shimmer.layoutParams
        params.height = (heightDp * shimmer.resources.displayMetrics.density + 0.5f).toInt()
        shimmer.layoutParams = params
    }

    @SuppressLint("VisibleForTests")
    private fun inlineAdSize(activity: Activity, style: String): AdSize {
        val adWidth = adWidthDp(activity)
        return if (BannerType.Inline.LARGE_STYLE.equals(style, ignoreCase = true)) {
            AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(activity, adWidth)
        } else {
            AdSize.getInlineAdaptiveBannerAdSize(adWidth, MAX_SMALL_INLINE_BANNER_HEIGHT)
        }
    }

    @Suppress("DEPRECATION")
    private fun adWidthDp(activity: Activity): Int {
        val outMetrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(outMetrics)
        return (outMetrics.widthPixels / outMetrics.density).toInt()
    }

    @SuppressLint("VisibleForTests")
    private fun collapsibleRequest(gravity: String): AdRequest {
        val admobExtras = Bundle()
        admobExtras.putString("collapsible", gravity)
        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, admobExtras)
            .build()
    }
}
