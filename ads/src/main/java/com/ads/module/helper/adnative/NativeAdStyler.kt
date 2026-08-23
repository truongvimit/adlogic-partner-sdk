package com.ads.module.helper.adnative

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ads.module.R
import com.ads.module.admob.Admob
import com.ads.module.ads.wrapper.ApNativeAd
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Applies a [NativeAdStyle] to a native layout tree — the loaded ad view or the derived
 * skeleton — and hosts the styled populate used by [NativeAdHelper]'s default binder.
 *
 * Everything runs on detached trees (before the view is attached), so restyling costs
 * pointer operations only, never an extra layout pass.
 */
object NativeAdStyler {

    /**
     * Geometry-affecting styling: CTA height and component order/visibility. Safe for both
     * the real ad view and a skeleton — never touches colors or content.
     */
    @JvmStatic
    fun applyLayout(root: View, style: NativeAdStyle) {
        style.ctaHeightDp?.let { heightDp ->
            root.findViewById<View>(R.id.ad_call_to_action)?.let { cta ->
                cta.layoutParams = cta.layoutParams?.apply { height = root.dp(heightDp) }
            }
        }
        val order = style.components ?: return
        val blocks = linkedMapOf<NativeComponent, View>()
        root.findViewById<View>(R.id.block_icon_headline)
            ?.let { blocks[NativeComponent.ICON_HEADLINE] = it }
        root.findViewById<View>(R.id.ad_body)?.let { blocks[NativeComponent.BODY] = it }
        root.findViewById<View>(R.id.ad_media)?.let { blocks[NativeComponent.MEDIA] = it }
        root.findViewById<View>(R.id.ad_call_to_action)?.let { blocks[NativeComponent.CTA] = it }

        val container = root.findViewById<View>(R.id.ad_container) as? LinearLayout
        if (container != null) {
            // Physical reorder: LinearLayout draws children in add order. removeView keeps
            // each block's LayoutParams, so margins and heights survive the move
            blocks.values.forEach { block ->
                (block.parent as? ViewGroup)?.removeView(block)
                block.visibility = View.GONE
            }
            // distinct: a malformed remote list ("cta","cta") must not crash the re-add
            order.distinct().forEach { component ->
                blocks[component]?.let { block ->
                    container.addView(block)
                    block.visibility = View.VISIBLE
                }
            }
        } else {
            // No reorderable container — fall back to visibility toggles in place
            blocks.forEach { (component, block) ->
                block.visibility = if (component in order) View.VISIBLE else View.GONE
            }
            // Flat layouts carry the icon/headline group as loose views instead of a block.
            // The "Ad" attribution badge (ad_icon) deliberately stays visible.
            if (NativeComponent.ICON_HEADLINE !in blocks) {
                val visibility =
                    if (NativeComponent.ICON_HEADLINE in order) View.VISIBLE else View.GONE
                listOf(R.id.ad_app_icon, R.id.ad_headline, R.id.ad_advertiser).forEach { id ->
                    root.findViewById<View>(id)?.visibility = visibility
                }
            }
        }
    }

    /** Appearance styling (CTA color). Real ad only — a skeleton must stay grey. */
    @JvmStatic
    fun applyAppearance(root: View, style: NativeAdStyle) {
        val color = style.ctaBackgroundColor ?: return
        root.findViewById<View>(R.id.ad_call_to_action)?.let { cta ->
            cta.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = root.dp(style.ctaCornerRadiusDp).toFloat()
            }
        }
    }

    /**
     * Styled counterpart of the classic populate: inflate detached, style, bind, then swap
     * into [container] in one frame (replacing the skeleton). With a null [style] this is
     * behavior-identical to `ERainAd.populateNativeAdView`.
     */
    @JvmStatic
    fun populate(
        activity: Activity,
        nativeAd: ApNativeAd,
        style: NativeAdStyle?,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout?,
    ) {
        if (nativeAd.admobNativeAd == null && nativeAd.nativeView == null) {
            shimmer?.visibility = View.GONE
            return
        }
        val adView = LayoutInflater.from(activity)
            .inflate(nativeAd.layoutCustomNative, null) as NativeAdView
        shimmer?.stopShimmer()
        shimmer?.visibility = View.GONE
        container.visibility = View.VISIBLE
        Admob.getInstance().populateUnifiedNativeAdView(nativeAd.admobNativeAd, adView)
        // Style AFTER the bind: populate force-sets body/CTA visible when the ad carries
        // those assets, so the style pass must land last for exclusions to stick
        if (style != null) {
            applyLayout(adView, style)
            applyAppearance(adView, style)
        }
        container.removeAllViews()
        container.addView(adView)
    }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
