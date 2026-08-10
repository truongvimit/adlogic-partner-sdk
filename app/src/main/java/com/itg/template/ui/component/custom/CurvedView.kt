package com.itg.template.ui.component.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * @author : CuongNK
 * @created : 3/4/2025
 **/


class CurvedView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {
    private val paint: Paint = Paint()
    private val path: Path = Path()

    init {
        init()
    }

    private fun init() {
        paint.color = Color.parseColor("#D3D3D3")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        path.reset()

        path.moveTo(width / 2, 0f)

        path.lineTo(width / 2, height * 0.8f)

        path.quadTo(
            width / 2,
            height * 0.9f,
            width * 0.95f,
            height * 0.95f
        )

        canvas.drawPath(path, paint)
    }
}