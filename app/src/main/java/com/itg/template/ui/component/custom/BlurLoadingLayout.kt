package com.itg.template.ui.component.custom

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import com.itg.template.R

class BlurLoadingLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private var blurView: BlurView? = null
    private var blurTarget: BlurTarget? = null
    private var blurSourceView: View? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        blurView = findViewById(R.id.blurView)
        blurTarget = findViewById(R.id.blurTarget)
        blurSourceView = findViewById(R.id.blurSourceImage)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { setupBlur() }
    }

    private fun setupBlur() {
        val activity = context.findActivity() ?: return
        val blurView = blurView ?: return
        val blurTarget = blurTarget ?: return
        val sourceView = blurSourceView as? ImageView ?: return
        val bitmap = captureDecorBitmap(activity) ?: return
        sourceView.setImageBitmap(bitmap)
        blurView.setupWith(blurTarget)
            .setBlurRadius(20f)
    }

    private fun captureDecorBitmap(activity: Activity): Bitmap? {
        val decorView = activity.window?.decorView ?: return null
        val width = decorView.width
        val height = decorView.height
        if (width == 0 || height == 0) return null
        val previousVisibility = visibility
        return try {
            visibility = View.INVISIBLE
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decorView.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            visibility = previousVisibility
        }
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}

