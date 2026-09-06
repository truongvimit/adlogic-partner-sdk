package com.ads.module.helper.adnative

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.ads.module.R
import com.facebook.shimmer.ShimmerFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** Pixel-level checks of the public SDK skeleton on top of contrasting host content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeAdShimmerOpacityTest {
    private val main get() = shadowOf(Looper.getMainLooper())

    @Test
    fun opaqueViewControlHidesTheBackdrop() {
        checkBackdrop { activity ->
            View(activity).apply {
                setBackgroundColor(Color.GRAY)
                layoutParams = FrameLayout.LayoutParams(320, 180)
            }
        }
    }

    @Test
    fun generatedNativeSkeletonHidesContentBehindItsInterior() {
        checkBackdrop { activity ->
            NativeAdShimmer.from(activity, R.layout.custom_native_admob_medium)
        }
    }

    @Test
    fun fallbackNativeSkeletonHidesContentBehindItsInterior() {
        checkBackdrop { activity -> NativeAdShimmer.from(activity, 0) }
    }

    @Test
    fun generatedSkeletonRetainsVisibleBlocksAndReadableAdBadge() {
        withSkeleton({ NativeAdShimmer.from(it, R.layout.custom_native_admob_medium) }) { host, skeleton ->
            val headline = skeleton.findViewById<TextView>(R.id.ad_headline)
            val cta = skeleton.findViewById<TextView>(R.id.ad_call_to_action)
            val badge = skeleton.findViewById<TextView>(R.id.ad_icon)
            val headlineRect = boundsIn(skeleton as ViewGroup, headline)
            val ctaRect = boundsIn(skeleton, cta)
            assertTrue("Headline keeps its placeholder geometry", headlineRect.width() > 0 && headlineRect.height() > 0)
            assertTrue("CTA remains below the headline", ctaRect.top >= headlineRect.bottom)
            assertTrue("Ad badge remains readable", badge.text.toString().trim().equals("ad", ignoreCase = true))
            assertTrue("Ad badge text must not be transparent", Color.alpha(badge.currentTextColor) > 0)
            assertEquals("Placeholder headline text is hidden", 0, Color.alpha(headline.currentTextColor))
            val frame = render(host, Color.RED)
            try {
                val block = frame.getPixel(headlineRect.centerX(), headlineRect.centerY())
                val padding = frame.getPixel(headlineRect.centerX(), 3)
                assertTrue("A loading skeleton must retain block contrast, not become a flat card", block != padding)
            } finally { frame.recycle() }
        }
    }

    @Test
    fun shimmerKeepTagPreservesHostDecoration() {
        withSkeleton({ activity ->
            NativeAdShimmer.from(TaggedInflaterContext(activity), R.layout.custom_native_admob_medium)
        }) { _, skeleton ->
            val kept = skeleton.findViewById<TextView>(R.id.ad_body)
            assertEquals(NativeAdShimmer.TAG_SHIMMER_KEEP, kept.tag)
            assertEquals("Host decoration", kept.text.toString())
            assertEquals(Color.RED, kept.currentTextColor)
            assertEquals(Color.YELLOW, (kept.background as ColorDrawable).color)
        }
    }

    @Test
    fun animationChangesTheSkeletonThenStopsWithoutRevealingHostContent() {
        val wasPaused = ShadowChoreographer.isPaused()
        val frameDelay = ShadowChoreographer.getFrameDelay()
        // Advance external Android frames explicitly; automatic VSync can finish an animation
        // before the first Canvas snapshot in native graphics mode.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            withSkeleton({ NativeAdShimmer.from(it, R.layout.custom_native_admob_medium) }) { host, view ->
                val skeleton = view as ShimmerFrameLayout
                assertFalse("Factory leaves animation under caller control", skeleton.isShimmerStarted)
                assertBackdropHidden(host)
                skeleton.startShimmer()
                assertTrue("The public start API must start animation", skeleton.isShimmerStarted)
                main.idleFor(Duration.ofMillis(100))
                assertTrue("First opacity snapshot is taken while animation is active", skeleton.isShimmerStarted)
                assertBackdropHidden(host)
                val early = render(host, Color.BLACK)
                main.idleFor(Duration.ofMillis(500))
                assertTrue("Later opacity snapshot is taken while animation is active", skeleton.isShimmerStarted)
                assertBackdropHidden(host)
                val later = render(host, Color.BLACK)
                try {
                    assertTrue("Animation must visibly change the skeleton", countChangedInterior(early, later) > 0)
                } finally {
                    early.recycle()
                    later.recycle()
                }
                skeleton.stopShimmer()
                assertFalse("The public stop API must stop animation", skeleton.isShimmerStarted)
                assertBackdropHidden(host)
                val stopped = render(host, Color.BLACK)
                main.idleFor(Duration.ofSeconds(1))
                val stillStopped = render(host, Color.BLACK)
                try {
                    assertEquals("Stopped shimmer must retain the same frame", 0, countChangedInterior(stopped, stillStopped))
                } finally {
                    stopped.recycle()
                    stillStopped.recycle()
                }
            }
        } finally {
            ShadowChoreographer.setFrameDelay(frameDelay)
            ShadowChoreographer.setPaused(wasPaused)
        }
    }

    private fun checkBackdrop(create: (Activity) -> View) = withSkeleton(create) { host, skeleton ->
        (skeleton as? ShimmerFrameLayout)?.startShimmer()
        main.idleFor(Duration.ofMillis(300))
        assertBackdropHidden(host)
    }

    private fun withSkeleton(create: (Activity) -> View, check: (FrameLayout, View) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val skeleton = create(activity)
        host.addView(skeleton)
        activity.setContentView(host)
        measureSlot(host)
        try {
            check(host, skeleton)
        } finally {
            (skeleton as? ShimmerFrameLayout)?.stopShimmer()
            controller.pause().stop().destroy()
            main.idle()
        }
    }

    private fun assertBackdropHidden(host: FrameLayout) {
        val overRed = render(host, Color.RED)
        val overBlue = render(host, Color.BLUE)
        try {
            val changed = countChangedInterior(overRed, overBlue)
            assertEquals("Host content changes $changed skeleton interior pixels", 0, changed)
        } finally {
            overRed.recycle()
            overBlue.recycle()
        }
    }

    private fun countChangedInterior(first: Bitmap, second: Bitmap): Int {
        var changed = 0
        // Exclude rounded corners and outer edges: the card interior must hide content.
        for (y in 12 until first.height - 12) {
            for (x in 12 until first.width - 12) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
            }
        }
        return changed
    }

    private fun boundsIn(parent: ViewGroup, child: View): Rect = Rect().also {
        child.getDrawingRect(it)
        parent.offsetDescendantRectToMyCoords(child, it)
    }

    private fun render(host: FrameLayout, backdrop: Int): Bitmap {
        // Activity traversal may relayout its content to the window during an animation tick.
        // Render only the measured ad slot, not unused Activity space around it.
        measureSlot(host)
        return Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888).also {
            host.setBackgroundColor(backdrop)
            host.draw(Canvas(it))
        }
    }

    private fun measureSlot(host: FrameLayout) {
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        assertTrue("The real skeleton must occupy a visible slot", host.height > 32)
    }
}

/** A host-owned Android inflater marks a view before the SDK skeleton transform runs. */
private class TaggedInflaterContext(base: Context) : ContextWrapper(base) {
    private val inflater by lazy {
        (baseContext.applicationContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater)
            .cloneInContext(this).apply {
                factory2 = object : LayoutInflater.Factory2 {
                    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                        when {
                            // Only the external GMA container is substituted; no ad is loaded.
                            name.endsWith("NativeAdView") || name.endsWith("MediaView") -> FrameLayout(context, attrs)
                            name == "TextView" -> TextView(context, attrs).apply {
                                if (id == R.id.ad_body) {
                                    tag = NativeAdShimmer.TAG_SHIMMER_KEEP
                                    text = "Host decoration"
                                    setTextColor(Color.RED)
                                    setBackgroundColor(Color.YELLOW)
                                }
                            }
                            else -> null
                        }
                    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                        onCreateView(null, name, context, attrs)
                }
            }
    }
    override fun getApplicationContext(): Context = this
    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
}
