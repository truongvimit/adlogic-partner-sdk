package io.onboardkit.ui.pool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import io.onboardkit.core.ObLog
import io.onboardkit.remote.OnboardingSettings

/**
 * The pooled LFO's ad block as two pages, VideoDownloader's native carousel: each page is
 * [PAGE_WIDTH_FRACTION] of the block wide, so the other page shows at the edge, and a swipe
 * snaps one page. Page 0 keeps [primaryContainer], so the LFO's owner slot binds into it unchanged.
 *
 * Every `lfo.carousel.auto_scroll_ms` it moves to the other page while [owner] is resumed, its
 * window has focus and that page shows an ad. This class owns no ad: what a page shows, and what a
 * settled swipe or auto-scroll requests, belong to the [listener].
 */
internal class PoolLanguageCarousel(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val hasWindowFocus: () -> Boolean,
    private val block: FrameLayout,
    private val primaryContainer: FrameLayout,
    private val listener: Listener,
) {
    interface Listener {
        fun hasAd(index: Int): Boolean

        /** The user settled the carousel on another page. */
        fun onSwipedTo(index: Int)

        /** Auto-scroll settled the carousel on another page. */
        fun onAutoScrolledTo(index: Int)
    }

    private class Page(val root: FrameLayout, val container: FrameLayout, val loading: FrameLayout) {
        var skeleton: ShimmerFrameLayout? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recycler = RecyclerView(context)
    private val snapHelper = PagerSnapHelper()
    private val primaryParams = primaryContainer.layoutParams
    private val pages: List<Page>
    private var running = false
    private var userDragging = false
    private var destroyed = false

    var currentIndex = 0
        private set

    /** Page 1's slot container. */
    val secondContainer: FrameLayout

    private val tick = Runnable { autoAdvance() }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> arm()
            Lifecycle.Event.ON_PAUSE -> handler.removeCallbacks(tick)
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    init {
        val margins = primaryParams as ViewGroup.MarginLayoutParams
        block.removeView(primaryContainer)
        secondContainer = FrameLayout(context).apply { visibility = View.GONE }
        pages = listOf(primaryContainer, secondContainer).map { container ->
            container.layoutParams = FrameLayout.LayoutParams(margins)
            // The card's own bounds: the skeleton covers the old ad edge to edge, never the margins.
            val loading = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(margins).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                // Takes the old ad's taps while it is covered.
                isClickable = true
                visibility = View.GONE
            }
            val root = PeekPage(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(container)
                addView(loading)
            }
            Page(root, container, loading)
        }
        recycler.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(pages.size)
            adapter = PageAdapter()
            addOnScrollListener(ScrollListener())
        }
        snapHelper.attachToRecyclerView(recycler)
        block.addView(recycler)
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    fun start() {
        if (destroyed || running) return
        running = true
        ObLog.d(ObLog.Section.SHOW, "lfo carousel start")
        arm()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** [skeleton] over [index]'s card, built the first time, until [clearLoading]. */
    fun setLoading(index: Int, skeleton: () -> ShimmerFrameLayout) {
        if (destroyed) return
        clearLoading()
        val page = pages[index]
        val shimmer = page.skeleton ?: skeleton().also {
            page.skeleton = it
            page.loading.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        page.loading.visibility = View.VISIBLE
        shimmer.startShimmer()
    }

    fun clearLoading() {
        pages.forEach { page ->
            page.skeleton?.stopShimmer()
            page.loading.visibility = View.GONE
        }
    }

    /** Back to a single native: page 0's container returns to the block as it was. */
    fun dismantle() {
        destroy()
        recycler.adapter = null
        block.removeView(recycler)
        (primaryContainer.parent as? ViewGroup)?.removeView(primaryContainer)
        primaryContainer.layoutParams = primaryParams
        block.addView(primaryContainer)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        clearLoading()
        owner.lifecycle.removeObserver(lifecycleObserver)
    }

    private fun arm() {
        handler.removeCallbacks(tick)
        if (running && !destroyed) handler.postDelayed(tick, OnboardingSettings.carouselAutoScrollMs())
    }

    private fun autoAdvance() {
        if (!running || destroyed) return
        val next = (currentIndex + 1) % pages.size
        val canMove = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            hasWindowFocus() && !userDragging && listener.hasAd(next)
        if (canMove) recycler.smoothScrollToPosition(next)
        arm()
    }

    private fun onSettled() {
        val byUser = userDragging
        userDragging = false
        val position = snapHelper.findSnapView(recycler.layoutManager)
            ?.let { recycler.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        if (position != null && position != currentIndex) {
            currentIndex = position
            if (byUser) listener.onSwipedTo(position) else listener.onAutoScrolledTo(position)
        }
        arm()
    }

    private inner class ScrollListener : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> {
                    userDragging = true
                    handler.removeCallbacks(tick)
                }
                RecyclerView.SCROLL_STATE_IDLE -> onSettled()
            }
        }
    }

    /** As wide as [PAGE_WIDTH_FRACTION] of the width the carousel offers it. */
    private class PeekPage(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widthMeasureSpec
            else MeasureSpec.makeMeasureSpec(
                (MeasureSpec.getSize(widthMeasureSpec) * PAGE_WIDTH_FRACTION).toInt(),
                MeasureSpec.EXACTLY,
            )
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    // One fixed view per position: a page's ad must never be rebound into the other page.
    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val root = pages[viewType].root
            (root.parent as? ViewGroup)?.removeView(root)
            return PageHolder(root)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
    }

    private companion object {
        const val PAGE_WIDTH_FRACTION = 0.85f
    }
}
