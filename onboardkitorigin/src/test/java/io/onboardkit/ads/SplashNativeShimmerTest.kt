package io.onboardkit.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdShimmer
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
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

    // Robolectric's font scale is process-wide and survives a failed test, which would silently
    // re-scale every class that runs after this one in the same JVM.
    @After fun resetFontScale() = RuntimeEnvironment.setFontScale(1.0f)

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
            val host = controller.get()
            val view = skeleton(host, 360)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height)
            // The card is its media row plus the card padding, read from the resources that draw
            // it — naming the design's pixel total here would only restate the dimens file.
            val pad = host.resources.getDimensionPixelSize(R.dimen.ob_splash_native_padding)
            val well = view.findViewById<View>(R.id.ob_splash_native_media_well)
            assertEquals(well.height + 2 * pad, view.height)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `the media well holds 4 to 3 at every width, not just the design's`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // The whole point of putting the ratio on the well: a pinned height would only be 4:3
            // at 360dp and would stretch on everything else. 320dp is the narrowest Android ships;
            // 412dp is a common large phone. 1px tolerance absorbs integer rounding.
            for (width in listOf(320, 360, 412)) {
                val well = skeleton(controller.get(), width)
                    .findViewById<View>(R.id.ob_splash_native_media_well)
                val expected = (well.width * 3.0 / 4.0)
                assertTrue(
                    "at ${width}dp the well is ${well.width}x${well.height}, not 4:3",
                    kotlin.math.abs(well.height - expected) <= 1.0,
                )
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `the MediaView fills the well, so the ad is framed by the ratio`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = skeleton(controller.get(), 360)
            val well = view.findViewById<View>(R.id.ob_splash_native_media_well)
            val media = view.findViewById<View>(R.id.ad_media)
            // skeletonizeMedia sets a 160dp minimumHeight on a match_parent MediaView. The well
            // measures it EXACTLY, so that floor must not leak into the skeleton's geometry.
            assertEquals(well.width, media.width)
            assertEquals(well.height, media.height)
            assertTrue("the floor leaked into the media box", media.height < 160)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `the CTA stays at the bottom of the row and keeps its height`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = controller.get()
            val view = skeleton(host, 360)
            val cta = view.findViewById<View>(R.id.ad_call_to_action)
            assertEquals(host.resources.getDimensionPixelSize(R.dimen.ob_splash_native_cta_height), cta.height)
            val rect = android.graphics.Rect(0, 0, cta.width, cta.height)
            view.offsetDescendantRectToMyCoords(cta, rect)
            // The design's space-between: the CTA rests on the card's bottom padding.
            val pad = host.resources.getDimensionPixelSize(R.dimen.ob_splash_native_padding)
            assertEquals(view.height - pad, rect.bottom)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `a narrow screen still leaves the text column room for every block`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // 320dp is the narrowest width Android ships.
            val view = skeleton(controller.get(), 320)
            val cta = view.findViewById<View>(R.id.ad_call_to_action)
            assertEquals(
                controller.get().resources.getDimensionPixelSize(R.dimen.ob_splash_native_cta_height),
                cta.height,
            )
            assertTrue("headline must keep a visible line", view.findViewById<View>(R.id.ad_headline).height > 0)
            assertTrue("body must keep a visible line", view.findViewById<View>(R.id.ad_body).height > 0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `the CTA survives a large font scale on the narrowest screen`() {
        // The row's height follows the media's 4:3, so it shrinks with screen width while the
        // badge and the 44dp CTA do not, and the headline and body grow with the font scale. At
        // 320dp the ratio gives only 108dp, and 1.3 is Android's ordinary "largest" text step —
        // no accessibility mode needed. Without height_min="wrap" the CTA is clipped out of the
        // card, and clipChildren makes the lost strip untappable as well as invisible.
        for (scale in listOf(1.0f, 1.3f, 1.5f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val view = skeleton(controller.get(), 320)
                val body = view.findViewById<View>(R.id.ad_body)
                val cta = view.findViewById<View>(R.id.ad_call_to_action)
                val rect = android.graphics.Rect(0, 0, cta.width, cta.height)
                view.offsetDescendantRectToMyCoords(cta, rect)
                val bodyRect = android.graphics.Rect(0, 0, body.width, body.height)
                view.offsetDescendantRectToMyCoords(body, bodyRect)
                // The card has to grow to hold the button whole, at its full declared height.
                assertEquals(
                    "fontScale $scale: CTA lost height",
                    controller.get().resources.getDimensionPixelSize(R.dimen.ob_splash_native_cta_height),
                    cta.height,
                )
                assertTrue(
                    "fontScale $scale: CTA bottom ${rect.bottom} overflows card ${view.height}",
                    rect.bottom <= view.height,
                )
                // …and it must not ride up over the copy it sits beneath.
                assertTrue(
                    "fontScale $scale: CTA top ${rect.top} overlaps body bottom ${bodyRect.bottom}",
                    rect.top >= bodyRect.bottom,
                )
            } finally { controller.pause().stop().destroy() }
        }
    }
}
