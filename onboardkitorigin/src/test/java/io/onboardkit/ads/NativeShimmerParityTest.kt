package io.onboardkit.ads

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdShimmer
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
 * Every native frame the SDK ships, held to the promise [NativeAdShimmer] makes: the skeleton is
 * derived from the ad's own layout so the slot is the right size from the first frame and does not
 * resize when the ad lands.
 *
 * The check is a comparison, never a table of expected sizes — a layout is measured against itself
 * inflated for real, so it holds at any width and survives a designer changing the spec.
 *
 * Card frames are the ones held to exact parity. A frame whose root is `match_parent` sizes from
 * its host and may hand its media to a weight, which resolves to nothing in an unbounded measure;
 * `skeletonizeMedia` deliberately substitutes a floor there, and that stand-in predates this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeShimmerParityTest {

    private val frames = mapOf(
        "media_left" to R.layout.ob_layout_native_media_left,
        "dialog" to R.layout.ob_layout_native_dialog,
        "compact" to R.layout.ob_layout_native_compact,
        "cta_bottom" to R.layout.ob_layout_native_cta_bottom,
        "cta_top" to R.layout.ob_layout_native_cta_top,
        "fullscreen" to R.layout.ob_layout_native_fullscreen,
    )

    private fun laidOut(host: Activity, view: View, width: Int): View {
        val parent = FrameLayout(host)
        parent.addView(view)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, width, parent.measuredHeight)
        return view
    }

    private fun isCard(host: Activity, layout: Int): Boolean =
        LayoutInflater.from(host).inflate(layout, FrameLayout(host), false)
            .layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT

    @Test fun `every card frame's skeleton is the size of the ad that replaces it`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = controller.get()
            var checked = 0
            for ((name, layout) in frames) {
                if (!isCard(host, layout)) continue
                for (width in listOf(320, 360, 412)) {
                    val real = laidOut(host, LayoutInflater.from(host).inflate(layout, FrameLayout(host), false), width)
                    val skeleton = laidOut(host, NativeAdShimmer.from(host, layout), width)
                    assertEquals(
                        "$name at ${width}dp: skeleton is ${skeleton.height}dp tall, the ad is ${real.height}dp — the slot would resize when the ad lands",
                        real.height,
                        skeleton.height,
                    )
                    val realMedia = real.findViewById<View>(R.id.ad_media)
                    val skeletonMedia = skeleton.findViewById<View>(R.id.ad_media)
                    if (realMedia != null && skeletonMedia != null) {
                        assertEquals(
                            "$name at ${width}dp: skeleton media is ${skeletonMedia.width}x${skeletonMedia.height}, the ad's is ${realMedia.width}x${realMedia.height}",
                            realMedia.width to realMedia.height,
                            skeletonMedia.width to skeletonMedia.height,
                        )
                    }
                    checked++
                }
            }
            // A frame list that stopped matching any card would make every assertion above vacuous.
            assertTrue("no card frame was checked — the layout list has drifted", checked > 0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `no frame's skeleton collapses or runs away`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = controller.get()
            for ((name, layout) in frames) {
                val skeleton = laidOut(host, NativeAdShimmer.from(host, layout), 360)
                assertTrue("$name: skeleton measured ${skeleton.height}dp — an empty slot", skeleton.height > 0)
                assertTrue("$name: skeleton measured ${skeleton.height}dp — taller than the screen", skeleton.height <= 2000)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
