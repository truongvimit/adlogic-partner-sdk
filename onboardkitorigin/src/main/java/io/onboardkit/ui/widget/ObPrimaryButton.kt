package io.onboardkit.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.use
import io.onboardkit.R

/**
 * Two-state primary button: NEXT (more steps remain) vs LAST ("Get Started").
 * Each state carries its own text, text color and background; remote UI may override both
 * labels independently — the original SDK collapsed them into one.
 */
class ObPrimaryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    enum class State { NEXT, LAST }

    private var textNext: CharSequence = context.getString(R.string.ob_next)
    private var textLast: CharSequence = context.getString(R.string.ob_get_started)
    private var textColorNext: Int = context.getColor(R.color.ob_white)
    private var textColorLast: Int = context.getColor(R.color.ob_white)
    private var bgColorNext: Int = context.getColor(R.color.ob_accent)
    private var bgColorLast: Int = context.getColor(R.color.ob_accent)
    private var cornerRadius: Float = resources.displayMetrics.density * DEFAULT_RADIUS_DP

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    var state: State = State.NEXT
        set(value) {
            field = value
            applyState()
        }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.ObPrimaryButton).use { a ->
            a.getString(R.styleable.ObPrimaryButton_ob_textNext)?.let { textNext = it }
            a.getString(R.styleable.ObPrimaryButton_ob_textLast)?.let { textLast = it }
            textColorNext =
                a.getColor(R.styleable.ObPrimaryButton_ob_textColorNext, textColorNext)
            textColorLast =
                a.getColor(R.styleable.ObPrimaryButton_ob_textColorLast, textColorLast)
            bgColorNext = a.getColor(R.styleable.ObPrimaryButton_ob_bgColorNext, bgColorNext)
            bgColorLast = a.getColor(R.styleable.ObPrimaryButton_ob_bgColorLast, bgColorLast)
            cornerRadius =
                a.getDimension(R.styleable.ObPrimaryButton_ob_cornerRadius, cornerRadius)
        }
        gravity = android.view.Gravity.CENTER
        applyState()
    }

    fun overrideLabels(next: CharSequence?, last: CharSequence?, textColor: Int?) {
        next?.let { textNext = it }
        last?.let { textLast = it }
        textColor?.let {
            textColorNext = it
            textColorLast = it
        }
        applyState()
    }

    private fun applyState() {
        when (state) {
            State.NEXT -> {
                text = textNext
                setTextColor(textColorNext)
                bgPaint.color = bgColorNext
            }

            State.LAST -> {
                text = textLast
                setTextColor(textColorLast)
                bgPaint.color = bgColorLast
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        super.onDraw(canvas)
    }

    private companion object {
        const val DEFAULT_RADIUS_DP = 42f
    }
}
