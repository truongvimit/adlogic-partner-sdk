package io.onboardkit.ui.pager

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Whether one fling is the pager's own "advance" gesture: mostly horizontal, toward the next
 * page for the current layout direction, and fast enough to be deliberate. [deltaX]/[deltaY]
 * are end minus start, so toward-next is negative X under LTR and positive X under RTL.
 */
internal fun isAdvanceFling(
    deltaX: Float,
    deltaY: Float,
    velocityX: Float,
    rtl: Boolean,
    minDistancePx: Float,
    minVelocityPx: Float,
): Boolean {
    if (abs(deltaX) < minDistancePx) return false
    if (abs(deltaX) <= abs(deltaY)) return false
    val movedTowardNext = if (rtl) deltaX > 0 else deltaX < 0
    val flungTowardNext = if (rtl) velocityX > 0 else velocityX < 0
    return movedTowardNext && flungTowardNext && abs(velocityX) >= minVelocityPx
}

/**
 * Reads the advance fling off raw window touches. The pager cannot report this itself: with
 * [androidx.viewpager2.widget.ViewPager2.isUserInputEnabled] off it sees no gesture at all,
 * and on its last page a forward drag moves nothing either way. The host feeds every event
 * in from `dispatchTouchEvent`; nothing is ever consumed here, so children behave as before.
 */
internal class AdvanceFlingDetector(
    context: Context,
    private val rtl: () -> Boolean,
    private val onAdvanceFling: () -> Unit,
) {
    private val minDistancePx: Float
    private val minVelocityPx: Float
    private val detector: GestureDetector

    init {
        val viewConfiguration = ViewConfiguration.get(context)
        minDistancePx = viewConfiguration.scaledPagingTouchSlop.toFloat()
        minVelocityPx = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
        detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val down = e1 ?: return false
                    val advance = isAdvanceFling(
                        deltaX = e2.x - down.x,
                        deltaY = e2.y - down.y,
                        velocityX = velocityX,
                        rtl = rtl(),
                        minDistancePx = minDistancePx,
                        minVelocityPx = minVelocityPx,
                    )
                    if (advance) onAdvanceFling()
                    return false
                }
            },
        )
    }

    fun observe(event: MotionEvent) {
        detector.onTouchEvent(event)
    }
}

/**
 * True when a horizontally scrollable view of the current page sits under ([xInWindow],
 * [yInWindow]) — a carousel, a horizontal list — whose own gesture a window-level fling must
 * not steal. The walk starts below the pager's internal RecyclerView, which always reports it
 * can scroll back from the last page and would veto everything.
 */
internal fun ViewPager2.pageHasHorizontallyScrollableViewUnder(
    xInWindow: Float,
    yInWindow: Float,
): Boolean {
    val pages = getChildAt(0) as? ViewGroup ?: return false
    return pages.hasHorizontallyScrollableDescendantUnder(xInWindow, yInWindow)
}

private fun ViewGroup.hasHorizontallyScrollableDescendantUnder(
    xInWindow: Float,
    yInWindow: Float,
): Boolean {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility != View.VISIBLE || !child.isUnderInWindow(xInWindow, yInWindow)) {
            continue
        }
        if (child.canScrollHorizontally(-1) || child.canScrollHorizontally(1)) return true
        if (child is ViewGroup &&
            child.hasHorizontallyScrollableDescendantUnder(xInWindow, yInWindow)
        ) {
            return true
        }
    }
    return false
}

private fun View.isUnderInWindow(xInWindow: Float, yInWindow: Float): Boolean {
    val location = IntArray(2)
    getLocationInWindow(location)
    return xInWindow >= location[0] && xInWindow < location[0] + width &&
        yInWindow >= location[1] && yInWindow < location[1] + height
}
