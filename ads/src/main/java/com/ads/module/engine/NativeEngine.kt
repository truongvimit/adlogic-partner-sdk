package com.ads.module.engine

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.ads.module.R
import com.ads.module.ads.wrapper.ApNativeAd
import com.ads.module.funtion.AdCallback
import com.ads.module.funtion.AdType
import com.ads.module.helper.AdGate
import com.ads.module.tracking.TrackingAdCallback
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import io.trackkit.AdFormat

internal object NativeEngine {

    fun load(context: Context, adUnitId: String, layoutRes: Int, callback: AdCallback?) {
        val tracked = TrackingAdCallback.attach(adUnitId, AdFormat.NATIVE, callback)
        // Silent, unlike banner: AdWaterfall.canContinue already failed the caller in this tick.
        if (AdGate.engineBlocked(context)) return
        val videoOptions = VideoOptions.Builder()
            .setStartMuted(true)
            .build()
        val adOptions = NativeAdOptions.Builder()
            .setVideoOptions(videoOptions)
            .build()
        val adLoader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { nativeAd ->
                tracked.onNativeAdLoaded(ApNativeAd(layoutRes, nativeAd))
                nativeAd.setOnPaidEventListener { adValue ->
                    onGmaPaid(
                        context, adValue, adUnitId,
                        nativeAd.responseInfo!!.mediationAdapterClassName, AdType.NATIVE,
                    )
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    tracked.onAdFailedToLoad(error)
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    tracked.onAdImpression()
                }

                override fun onAdOpened() {
                    super.onAdOpened()
                    tracked.onAdOpened()
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    suppressResumeAfterAdClick()
                    tracked.onAdClicked()
                    logGmaClick(context, adUnitId)
                }
            })
            .withNativeAdOptions(adOptions)
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun populate(nativeAd: NativeAd, adView: NativeAdView) {
        val headline = adView.findViewById<View>(R.id.ad_headline)
        val body = adView.findViewById<View>(R.id.ad_body)
        val callToAction = adView.findViewById<View>(R.id.ad_call_to_action)
        val iconView = adView.findViewById<View>(R.id.ad_app_icon)
        val price = adView.findViewById<View>(R.id.ad_price)
        val stars = adView.findViewById<View>(R.id.ad_stars)
        val advertiser = adView.findViewById<View>(R.id.ad_advertiser)

        // GMA also tracks clicks on custom-drawn asset Views; typed casts apply only to our writes.
        adView.mediaView = adView.findViewById<View>(R.id.ad_media) as? MediaView
        adView.headlineView = headline
        adView.bodyView = body
        adView.callToActionView = callToAction
        adView.iconView = iconView
        adView.priceView = price
        adView.starRatingView = stars
        adView.advertiserView = advertiser

        bindAsset { (headline as? TextView)?.text = nativeAd.headline }
        bindAsset { body?.bindText(nativeAd.body) }
        bindAsset { callToAction?.bindText(nativeAd.callToAction) }
        bindAsset {
            iconView?.let { view ->
                val icon = nativeAd.icon
                if (icon == null) {
                    view.visibility = View.GONE
                } else if (view is ImageView) {
                    view.setImageDrawable(icon.drawable)
                    view.visibility = View.VISIBLE
                }
            }
        }
        bindAsset { price?.bindText(nativeAd.price) }
        bindAsset {
            stars?.let { view ->
                val rating = nativeAd.starRating
                if (rating == null) {
                    view.visibility = View.INVISIBLE
                } else if (view is RatingBar) {
                    view.rating = rating.toFloat()
                    view.visibility = View.VISIBLE
                }
            }
        }
        bindAsset {
            advertiser?.let { view ->
                val value = nativeAd.advertiser
                if (value == null) {
                    view.visibility = View.INVISIBLE
                } else if (view is TextView) {
                    view.text = value
                    view.visibility = View.VISIBLE
                }
            }
        }
        adView.setNativeAd(nativeAd)
    }

    private fun View.bindText(value: String?) {
        visibility = if (value == null) View.INVISIBLE else View.VISIBLE
        if (value != null && this is TextView) text = value
    }

    private inline fun bindAsset(bind: () -> Unit) {
        try {
            bind()
        } catch (e: Exception) {
            // A failing vendor getter or custom view must not drop the remaining native assets.
            Log.w("ERainStudio", "Native asset binding failed", e)
        }
    }
}
