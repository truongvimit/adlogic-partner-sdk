package com.ads.module.helper.adnative

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.ads.module.R
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.MediaView
import androidx.core.view.isEmpty

/**
 * Derives a shimmer skeleton from a placement's real ad layout: inflates the layout once,
 * repaints every view into a grey block, and hosts the result in a [ShimmerFrameLayout].
 *
 * Because the skeleton comes from the same layout resource the loaded ad will be bound
 * into, the swap keeps the exact geometry — the slot is occupied from the first frame and
 * the ad never pops into blank space.
 *
 * Only paint-level properties are touched (background, text color, image drawable) — never
 * text content or LayoutParams — so the measured shape stays identical to the real ad view.
 *
 * [from] never throws: it degrades to the bundled static skeleton, then to a flat block.
 * The returned view does not animate on its own; [NativeAdHelper] starts and stops it.
 */
object NativeAdShimmer {

    /** Views tagged with this string survive the transform untouched (brand mark, badge). */
    const val TAG_SHIMMER_KEEP = "shimmer_keep"

    private const val CONTAINER_COLOR = 0xFFE0E0E0.toInt()
    private const val BLOCK_COLOR = 0xFFC0C0C0.toInt()
    private const val CORNER_RADIUS_DP = 8f
    private const val EMPTY_TEXT_MIN_WIDTH_DP = 48
    private const val MEDIA_MIN_HEIGHT_DP = 160
    private const val FALLBACK_HEIGHT_DP = 200
    private const val MAX_DEPTH = 32

    /** Builds a skeleton from [adLayoutId]. Never throws, never returns an empty view. */
    @JvmStatic
    fun from(context: Context, @LayoutRes adLayoutId: Int): ShimmerFrameLayout =
        runCatching { buildFromAdLayout(context, adLayoutId) }
            .recoverCatching { staticFallback(context) }
            .getOrElse { flatBlock(context) }
            .apply {
                // Transparent-text placeholders must not be announced by TalkBack
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                // Shimmer 0.5.0 auto-(re)starts on attach by default, which would animate
                // behind GONE — the helper alone starts and stops the animation
                setShimmer(Shimmer.AlphaHighlightBuilder().setAutoStart(false).build())
            }

    /**
     * Optional: pre-pays the one-time class-load and cold-inflate cost at main-looper idle.
     * Call once at splash with the heaviest ad layout of the first screen.
     */
    @JvmStatic
    fun prewarm(context: Context, @LayoutRes adLayoutId: Int) {
        Looper.getMainLooper().queue.addIdleHandler {
            runCatching { from(context.applicationContext, adLayoutId) }
            false
        }
    }

    private fun buildFromAdLayout(context: Context, @LayoutRes adLayoutId: Int): ShimmerFrameLayout {
        val shimmer = ShimmerFrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val root = inflateForSkeleton(context, adLayoutId, shimmer)
        toSkeleton(root, depth = 0)
        shimmer.addView(root)
        return shimmer
    }

    /**
     * Inflates the ad layout with NativeAdView/MediaView stubbed to plain FrameLayouts, so
     * a mere placeholder never boots GMS ad machinery. NativeAdView is itself a FrameLayout
     * whose overlay child is empty pre-fill, so the stub measures identically.
     *
     * Falls back to a real inflate + root unwrap when a factory is already installed
     * (an app-wide inflater factory à la ViewPump makes [LayoutInflater.setFactory2] throw).
     */
    private fun inflateForSkeleton(
        context: Context,
        @LayoutRes adLayoutId: Int,
        parent: ViewGroup,
    ): View {
        val stubbed = runCatching {
            // Clone from the app-context service inflater: an Activity inflater already
            // carries the AppCompat factory and would reject ours
            val base = context.applicationContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val inflater = base.cloneInContext(context)
            inflater.factory2 = StubFactory()
            inflater.inflate(adLayoutId, parent, false)
        }.getOrNull()
        return stubbed ?: unwrapAdRoot(LayoutInflater.from(context).inflate(adLayoutId, parent, false))
    }

    // A real NativeAdView root carries GMS machinery a placeholder must not own — keep its
    // content child instead. Child 0 is the XML content; GMS appends its overlay last.
    private fun unwrapAdRoot(inflated: View): View {
        val vg = inflated as? ViewGroup ?: return inflated
        val isAdRoot = vg.javaClass.simpleName.endsWith("NativeAdView")
        if (!isAdRoot || vg.isEmpty()) return inflated
        val content = vg.getChildAt(0)
        vg.removeView(content)
        if (content.layoutParams == null) content.layoutParams = vg.layoutParams
        return content
    }

    private fun toSkeleton(view: View, depth: Int) {
        if (depth > MAX_DEPTH || view.tag == TAG_SHIMMER_KEEP) return
        when (view) {
            is StubMediaView -> skeletonizeMedia(view)

            // Fallback path only; GMS owns its children — repaint, never recurse
            is MediaView -> skeletonizeMedia(view)

            is ViewGroup -> {
                view.background = rounded(view, CONTAINER_COLOR)
                for (i in 0 until view.childCount) toSkeleton(view.getChildAt(i), depth + 1)
            }

            // Covers RatingBar: stars/spinners must not draw on the skeleton, space stays
            is ProgressBar -> view.visibility = View.INVISIBLE

            is TextView -> { // covers Button
                // The mandated "Ad" badge stays readable on the skeleton
                if (view.text?.toString()?.trim().equals("ad", ignoreCase = true)) return
                view.background = rounded(view, BLOCK_COLOR)
                view.setTextColor(Color.TRANSPARENT)
                view.setCompoundDrawables(null, null, null, null)
                view.setCompoundDrawablesRelative(null, null, null, null)
                // wrap_content with no placeholder text would collapse to a zero-width bar
                if (view.text.isNullOrBlank()) view.minimumWidth = view.dp(EMPTY_TEXT_MIN_WIDTH_DP)
            }

            is ImageView -> {
                view.background = rounded(view, BLOCK_COLOR)
                view.setImageDrawable(null)
            }

            else -> view.background = rounded(view, BLOCK_COLOR)
        }
    }

    // A real MediaView gets its height from the media content at runtime; the empty
    // placeholder would collapse to zero and shrink the whole skeleton. Give it a floor,
    // honoring any fixed height the layout declares.
    private fun skeletonizeMedia(view: View) {
        view.background = rounded(view, BLOCK_COLOR)
        val lp = view.layoutParams
        when {
            lp == null -> view.minimumHeight = view.dp(MEDIA_MIN_HEIGHT_DP)
            // 0dp means weight/constraint decides — with no content that resolves to zero
            // in wrap_content hosts, so pin a concrete height instead
            lp.height == 0 -> {
                lp.height = view.dp(MEDIA_MIN_HEIGHT_DP)
                view.layoutParams = lp
            }
            lp.height < 0 -> view.minimumHeight = view.dp(MEDIA_MIN_HEIGHT_DP)
        }
    }

    // Fresh drawable per view: a shared instance would fight over bounds
    private fun rounded(view: View, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = CORNER_RADIUS_DP * view.resources.displayMetrics.density
    }

    private fun staticFallback(context: Context): ShimmerFrameLayout =
        // Inflate against a throwaway parent so the root's XML width/height survive
        LayoutInflater.from(context)
            .inflate(R.layout.load_fb_native, FrameLayout(context), false) as ShimmerFrameLayout

    private fun flatBlock(context: Context): ShimmerFrameLayout =
        ShimmerFrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                View(context).apply { setBackgroundColor(CONTAINER_COLOR) },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FALLBACK_HEIGHT_DP)),
            )
        }

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Stand-in for GMS ad views during skeleton inflation. */
    private class StubMediaView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs)

    private class StubAdRootView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs)

    private class StubFactory : LayoutInflater.Factory2 {
        override fun onCreateView(
            parent: View?,
            name: String,
            context: Context,
            attrs: AttributeSet,
        ): View? = when {
            name.endsWith("NativeAdView") -> StubAdRootView(context, attrs)
            name.endsWith("MediaView") -> StubMediaView(context, attrs)
            else -> null // everything else resolves through the normal inflater path
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }
}
