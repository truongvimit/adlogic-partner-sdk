package com.itg.template.ads

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.ads.module.admob.Admob
import com.ads.module.ads.ERainAd
import com.ads.module.ads.wrapper.ApNativeAd
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdView
import com.itg.template.R
import com.itg.template.ui.bases.ext.dpToPx

import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Top-level helper – CTA height/color resolved automatically from [AdsManager.getAdConfig].
 */
fun populateNativeAdView(
    activity: Activity,
    apNativeAd: ApNativeAd,
    adPlaceHolder: FrameLayout,
    containerShimmerLoading: ShimmerFrameLayout,
) {
    if (apNativeAd.admobNativeAd == null && apNativeAd.nativeView == null) {
        containerShimmerLoading.visibility = View.GONE
        return
    }

    val config = AdsManager.getAdConfig(apNativeAd)

    val adView = LayoutInflater.from(activity)
        .inflate(apNativeAd.layoutCustomNative, null) as NativeAdView

    containerShimmerLoading.stopShimmer()
    containerShimmerLoading.visibility = View.GONE
    adPlaceHolder.visibility = View.VISIBLE

    adView.findViewById<View>(R.id.ad_call_to_action)?.let { ctaButton ->
        ctaButton.updateLayoutParams {
            val rawHeightDp = config?.heightCTA ?: 40
            val clampedHeightDp = rawHeightDp.coerceIn(36, 52)
            height = clampedHeightDp.dpToPx(activity).toInt()

        }
        applyCtaColor(ctaButton, config?.colorCTA ?: "default")
    }

    // Dynamic component reordering and visibility control
    if (config != null) {
        val validKeys = setOf("icon_headline", "body", "media", "cta")
        var enabledComponents = config.components
        if (enabledComponents.isEmpty() || enabledComponents.none { it in validKeys }) {
            enabledComponents = listOf("icon_headline", "body", "media", "cta")
        }
        val blockMap = mutableMapOf<String, View>()

        adView.findViewById<View>(R.id.block_icon_headline)?.let { blockMap["icon_headline"] = it }
        adView.findViewById<View>(R.id.ad_body)?.let { blockMap["body"] = it }
        adView.findViewById<View>(R.id.ad_media)?.let { blockMap["media"] = it }
        adView.findViewById<View>(R.id.ad_call_to_action)?.let { blockMap["cta"] = it }

        val adContainer = adView.findViewById<View>(R.id.ad_container) as? LinearLayout
        if (adContainer != null) {
            // Option 2: Dynamic reordering in LinearLayout container
            blockMap.values.forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.visibility = View.GONE
            }
            enabledComponents.forEach { componentName ->
                blockMap[componentName]?.let { view ->
                    adContainer.addView(view)
                    view.visibility = View.VISIBLE
                }
            }
        } else {
            // Option 1 fallback: Toggle visibility on existing flat structures
            val showIconHeadline = enabledComponents.contains("icon_headline")
            val blockIconHeadline = adView.findViewById<View>(R.id.block_icon_headline)
            if (blockIconHeadline != null) {
                blockIconHeadline.visibility = if (showIconHeadline) View.VISIBLE else View.GONE
            } else {
                adView.findViewById<View>(R.id.ad_app_icon)?.visibility = if (showIconHeadline) View.VISIBLE else View.GONE
                adView.findViewById<View>(R.id.ad_icon)?.visibility = if (showIconHeadline) View.VISIBLE else View.GONE
                adView.findViewById<View>(R.id.ad_headline)?.visibility = if (showIconHeadline) View.VISIBLE else View.GONE
                adView.findViewById<View>(R.id.ad_advertiser)?.visibility = if (showIconHeadline) View.VISIBLE else View.GONE
            }
            adView.findViewById<View>(R.id.ad_body)?.visibility = if (enabledComponents.contains("body")) View.VISIBLE else View.GONE
            adView.findViewById<View>(R.id.ad_media)?.visibility = if (enabledComponents.contains("media")) View.VISIBLE else View.GONE
            adView.findViewById<View>(R.id.ad_call_to_action)?.visibility = if (enabledComponents.contains("cta")) View.VISIBLE else View.GONE
        }
    }

    Admob.getInstance().populateUnifiedNativeAdView(apNativeAd.admobNativeAd, adView)
    adPlaceHolder.removeAllViews()
    adPlaceHolder.addView(adView)
}

private fun applyCtaColor(ctaButton: View, colorCTA: String) {
    if (colorCTA == "default" || colorCTA.isBlank()) return
    try {
        val color = Color.parseColor(colorCTA)
        ctaButton.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 20.dpToPx(ctaButton.context).toFloat()
        }
    } catch (_: IllegalArgumentException) { }
}

/**
 * ERainAd extension – explicit ctaHeightInDp, kept for backward-compat.
 */
fun ERainAd.populateNativeAdView(
    activity: Activity,
    apNativeAd: ApNativeAd,
    adPlaceHolder: FrameLayout,
    containerShimmerLoading: ShimmerFrameLayout,
    ctaHeightInDp: Int = 40,
) {
    if (apNativeAd.admobNativeAd == null && apNativeAd.nativeView == null) {
        containerShimmerLoading.visibility = View.GONE
        return
    }

    val adView: NativeAdView =
        LayoutInflater.from(activity).inflate(apNativeAd.layoutCustomNative, null) as NativeAdView

    containerShimmerLoading.stopShimmer()
    containerShimmerLoading.visibility = View.GONE
    adPlaceHolder.visibility = View.VISIBLE

    adView.findViewById<View>(R.id.ad_call_to_action)?.updateLayoutParams {
        height = ctaHeightInDp.dpToPx(adPlaceHolder.context).toInt()
    }

    Admob.getInstance().populateUnifiedNativeAdView(apNativeAd.admobNativeAd, adView)
    adPlaceHolder.removeAllViews()
    adPlaceHolder.addView(adView)
}
