package io.onboardkit.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdShimmer
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The splash slot shows a skeleton derived from the real frame for the whole load window, so this
 * layout is inflated on every launch that picks the native — before any ad exists. A bad resource
 * reference or a retyped view surfaces here rather than as an empty slot on the launch screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashNativeShimmerTest {

    private fun skeleton(host: Activity, widthPx: Int): ShimmerFrameLayout {
        val parent = FrameLayout(host)
        val view = NativeAdShimmer.from(host, R.layout.ob_layout_native_media_left)
        parent.addView(view)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, widthPx, parent.measuredHeight)
        return view
    }

    @Test fun `the skeleton wraps the card rather than filling the splash`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // mdpi: 1px == 1dp. The card is the design's 147dp — 123dp row plus 12dp padding
            // top and bottom — so the slot it occupies is the size of the ad that replaces it.
            val view = skeleton(controller.get(), 360)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height)
            assertEquals(147, view.height)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `media keeps the design's 4 to 3 well at the reference width`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val media = skeleton(controller.get(), 360).findViewById<View>(R.id.ad_media)
            // (360 - 24 padding - 8 gap) / 2 = 164, and 164 x 3/4 = 123.
            assertEquals(164, media.width)
            assertEquals(123, media.height)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `the CTA stays at the bottom of the row and keeps its height`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = skeleton(controller.get(), 360)
            val cta = view.findViewById<View>(R.id.ad_call_to_action)
            assertEquals(44, cta.height)
            val rect = android.graphics.Rect(0, 0, cta.width, cta.height)
            view.offsetDescendantRectToMyCoords(cta, rect)
            // The Space above it absorbs the slack, so the CTA sits on the card's bottom padding.
            assertEquals(147 - 12, rect.bottom)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `a narrow screen still leaves the text column room for every block`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // 320dp is the narrowest width Android ships. The row height is fixed, so the CTA
            // must not be squeezed out by the badge, headline and body above it.
            val view = skeleton(controller.get(), 320)
            val cta = view.findViewById<View>(R.id.ad_call_to_action)
            assertEquals(44, cta.height)
            assertTrue("headline must keep a visible line", view.findViewById<View>(R.id.ad_headline).height > 0)
            assertTrue("body must keep a visible line", view.findViewById<View>(R.id.ad_body).height > 0)
        } finally { controller.pause().stop().destroy() }
    }
}
