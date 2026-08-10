package com.itg.template.ui.component.custom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.itg.template.R

class LoadingDotsTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val handler = Handler(Looper.getMainLooper())
    private var animationRunnable: Runnable? = null
    private var dotCount = 0
    private val baseText: String
    private val maxDots = 3
    private val animationDelay = 500L

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.LoadingDotsTextView)
        baseText = typedArray.getString(R.styleable.LoadingDotsTextView_loadingText) ?: ""
        typedArray.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun startAnimation() {
        stopAnimation()
        dotCount = 0
        animationRunnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount % maxDots) + 1
                text = "$baseText${".".repeat(dotCount)}"
                handler.postDelayed(this, animationDelay)
            }
        }
        handler.post(animationRunnable!!)
    }

    private fun stopAnimation() {
        animationRunnable?.let {
            handler.removeCallbacks(it)
            animationRunnable = null
        }
    }
}

