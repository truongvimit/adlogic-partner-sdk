package com.ads.module.helper.adnative

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.drawable.DrawableCompat
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
     *
     * `components` needs the vertical `ad_container`; a layout without one is a fixed design
     * and keeps its XML blocks as they are.
     */
    @JvmStatic
    fun applyLayout(root: View, style: NativeAdStyle) {
        style.ctaHeightDp?.let { heightDp ->
            root.findViewById<View>(R.id.ad_call_to_action)?.let { cta ->
                cta.layoutParams = cta.layoutParams?.apply { height = root.dp(heightDp) }
            }
        }
        val order = style.components ?: return
        val container = root.findViewById<View>(R.id.ad_container) as? LinearLayout ?: return
        val blocks = linkedMapOf<NativeComponent, View>()
        root.findViewById<View>(R.id.block_icon_headline)
            ?.let { blocks[NativeComponent.ICON_HEADLINE] = it }
        root.findViewById<View>(R.id.ad_body)?.let { blocks[NativeComponent.BODY] = it }
        // A ratio-locked media sits in a well, and the well is what the stack holds
        (root.findViewById(R.id.block_media) ?: root.findViewById<View>(R.id.ad_media))
            ?.let { blocks[NativeComponent.MEDIA] = it }
        root.findViewById<View>(R.id.ad_call_to_action)?.let { blocks[NativeComponent.CTA] = it }

        // Only blocks the container already holds may be reordered. A layout is free to keep
        // one somewhere else — a body nested in the header is still `ad_body` — and pulling that
        // into the stack would tear the design apart. Those stay put and answer to visibility alone.
        val ordered = blocks.filterValues { it.parent === container }
        val anchored = blocks.filterKeys { it !in ordered.keys }

        // Physical reorder: LinearLayout draws children in add order. removeView keeps
        // each block's LayoutParams, so margins, heights and weights survive the move
        ordered.values.forEach { block ->
            container.removeView(block)
            block.visibility = View.GONE
        }
        // distinct: a malformed remote list ("cta","cta") must not crash the re-add
        order.distinct().forEach { component ->
            ordered[component]?.let { block ->
                container.addView(block)
                block.visibility = View.VISIBLE
            }
        }
        anchored.forEach { (component, block) ->
            block.visibility = if (component in order) View.VISIBLE else View.GONE
        }
    }

    /** Appearance styling (CTA and attribution colors) for a loaded ad or its skeleton. */
    @JvmStatic
    fun applyAppearance(root: View, style: NativeAdStyle) {
        val color = style.ctaBackgroundColor ?: return
        root.findViewById<View>(R.id.ad_call_to_action)?.let { cta ->
            cta.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = root.dp(style.ctaCornerRadiusDp).toFloat()
            }
        }
        // The attribution badge is part of the native's visual CTA language. Layouts expose it
        // as `ad_icon`; tint the existing drawable so its shape, padding and corner radii survive.
        root.findViewById<View>(R.id.ad_icon)?.let { badge ->
            badge.background?.let { background ->
                badge.background = DrawableCompat.wrap(background.mutate()).also { it.setTint(color) }
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
        val previous = (0 until container.childCount).map { container.getChildAt(it) }
            .filterIsInstance<NativeAdView>()
        container.removeAllViews()
        container.addView(adView)
        previous.forEach { it.destroy() }
    }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
