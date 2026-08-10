package io.onboardkit.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import io.onboardkit.R

/**
 * Self-drawn dots indicator. Set [count]/[selectedIndex] explicitly — it never observes the
 * pager, so it stays correct after a hot-swap rebuild (third-party indicators desynced there).
 */
class ObStepIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var activeColor: Int = context.getColor(R.color.ob_dot_active)
    private var inactiveColor: Int = context.getColor(R.color.ob_dot_inactive)
    private var dotSize: Float = dp(8f)
    private var dotSpacing: Float = dp(6f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var count: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            requestLayout()
        }

    var selectedIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.ObStepIndicator).use { a ->
            activeColor = a.getColor(R.styleable.ObStepIndicator_ob_dotColorActive, activeColor)
            inactiveColor =
                a.getColor(R.styleable.ObStepIndicator_ob_dotColorInactive, inactiveColor)
            dotSize = a.getDimension(R.styleable.ObStepIndicator_ob_dotSize, dotSize)
            dotSpacing = a.getDimension(R.styleable.ObStepIndicator_ob_dotSpacing, dotSpacing)
        }
    }

    fun setColors(active: Int?, inactive: Int? = null) {
        active?.let { activeColor = it }
        inactive?.let { inactiveColor = it }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (count == 0) 0 else (count * dotSize + (count - 1) * dotSpacing).toInt()
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize((dotSize.toInt()) + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dotSize / 2f
        val cy = paddingTop + radius
        for (i in 0 until count) {
            paint.color = if (i == selectedIndex) activeColor else inactiveColor
            val cx = paddingLeft + radius + i * (dotSize + dotSpacing)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
