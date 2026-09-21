package com.ads.module.engine

import android.content.Context
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
                    tracked.onAdClicked()
                    onGmaClick(context, adUnitId)
                }
            })
            .withNativeAdOptions(adOptions)
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun populate(nativeAd: NativeAd, adView: NativeAdView) {
        adView.mediaView = adView.findViewById(R.id.ad_media)
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)
        adView.priceView = adView.findViewById(R.id.ad_price)
        adView.starRatingView = adView.findViewById(R.id.ad_stars)
        adView.advertiserView = adView.findViewById(R.id.ad_advertiser)

        bindAsset { (adView.headlineView as TextView).text = nativeAd.headline }
        bindAsset {
            val view = checkNotNull(adView.bodyView)
            if (nativeAd.body == null) {
                view.visibility = View.INVISIBLE
            } else {
                view.visibility = View.VISIBLE
                (view as TextView).text = nativeAd.body
            }
        }
        bindAsset {
            val view = checkNotNull(adView.callToActionView)
            if (nativeAd.callToAction == null) {
                view.visibility = View.INVISIBLE
            } else {
                view.visibility = View.VISIBLE
                (view as TextView).text = nativeAd.callToAction
            }
        }
        bindAsset {
            val view = checkNotNull(adView.iconView)
            val icon = nativeAd.icon
            if (icon == null) {
                view.visibility = View.GONE
            } else {
                (view as ImageView).setImageDrawable(icon.drawable)
                view.visibility = View.VISIBLE
            }
        }
        bindAsset {
            val view = checkNotNull(adView.priceView)
            if (nativeAd.price == null) {
                view.visibility = View.INVISIBLE
            } else {
                view.visibility = View.VISIBLE
                (view as TextView).text = nativeAd.price
            }
        }
        bindAsset {
            val view = checkNotNull(adView.starRatingView)
            val rating = nativeAd.starRating
            if (rating == null) {
                view.visibility = View.INVISIBLE
            } else {
                (view as RatingBar).rating = rating.toFloat()
                view.visibility = View.VISIBLE
            }
        }
        bindAsset {
            val view = checkNotNull(adView.advertiserView)
            if (nativeAd.advertiser == null) {
                view.visibility = View.INVISIBLE
            } else {
                (view as TextView).text = nativeAd.advertiser
                view.visibility = View.VISIBLE
            }
        }
        adView.setNativeAd(nativeAd)
    }

    // A missing or mistyped view only drops that asset: the ad still ships, without it.
    private inline fun bindAsset(bind: () -> Unit) {
        try {
            bind()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
