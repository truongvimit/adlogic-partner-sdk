package io.onboardkit.ads

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ads.module.helper.adnative.NativeAdShimmer
import io.onboardkit.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullscreenNativeShimmerTest {
    @Test fun `fullscreen skeleton fills the viewport with media and bottom CTA`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = controller.get()
            val parent = FrameLayout(host)
            val skeleton = NativeAdShimmer.from(host, R.layout.ob_layout_native_fullscreen)
            parent.addView(skeleton)
            parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 400, 800)
            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
            parent.draw(Canvas(bitmap))
            File("/tmp/ob-fullscreen-shimmer.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, skeleton.layoutParams.height)
            assertEquals(800, skeleton.height)
            assertEquals(800, skeleton.findViewById<View>(R.id.ad_media).height)
            val cta = skeleton.findViewById<View>(R.id.ad_call_to_action)
            val rect = android.graphics.Rect(0, 0, cta.width, cta.height)
            skeleton.offsetDescendantRectToMyCoords(cta, rect)
            assertTrue("CTA belongs near the bottom of the full screen", rect.top > 650)
            assertTrue(rect.bottom <= 800)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `content native skeleton still wraps its card instead of filling the page`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val skeleton = NativeAdShimmer.from(controller.get(), R.layout.ob_layout_native_compact)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, skeleton.layoutParams.height)
        } finally { controller.pause().stop().destroy() }
    }
}
