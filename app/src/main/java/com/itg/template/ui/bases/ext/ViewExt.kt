package com.itg.template.ui.bases.ext

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.databinding.ViewDataBinding
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.itg.template.app.AppConstants
import com.itg.template.ui.component.dialog.DialogRateApp
import com.itg.template.utils.EasyPreferences
import com.itg.template.utils.EasyPreferences.set

internal const val DISPLAY = 1080

fun View.goneView() {
    visibility = View.GONE
}

fun View.visibleView() {
    visibility = View.VISIBLE
}

fun View.invisibleView() {
    visibility = View.INVISIBLE
}

fun View.isVisible() = visibility == View.VISIBLE

fun View.isInvisible() = visibility == View.INVISIBLE

fun View.isGone() = visibility == View.GONE

fun ViewDataBinding.goneView() {
    this.root.goneView()
}

fun ViewDataBinding.visibleView() {
    this.root.visibleView()
}

fun ViewDataBinding.invisibleView() {
    this.root.invisibleView()
}

fun ViewDataBinding.isVisible() = this.root.visibility == View.VISIBLE

fun ViewDataBinding.isInvisible() = this.root.visibility == View.INVISIBLE

fun ViewDataBinding.isGone() = this.root.visibility == View.GONE

/*Resize View*/
fun View.resizeView(width: Int, height: Int = 0) {
    val pW = context.getWidthScreenPx() * width / DISPLAY
    val pH = if (height == 0) pW else pW * height / width
    val params = layoutParams
    params.let {
        it.width = pW
        it.height = pH
    }
}


private var lastClickTime: Long = 0

fun View.click(action: (view: View?) -> Unit) {
    this.setOnClickListener(object : View.OnClickListener {
        override fun onClick(v: View) {
            if (SystemClock.elapsedRealtime() - lastClickTime < 300L) return
            else action(v)
            lastClickTime = SystemClock.elapsedRealtime()
        }
    })
}

fun showRateDialog(
    activity: Activity, isFinish: Boolean
) {
    val dialog = DialogRateApp(activity, onRating = {
        val manager: ReviewManager = ReviewManagerFactory.create(activity)
        val request: Task<ReviewInfo> = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo: ReviewInfo = task.result
                val flow: Task<Void> = manager.launchReviewFlow(
                    activity, reviewInfo
                )
                flow.addOnSuccessListener {
                    EasyPreferences.defaultPrefs(activity)[AppConstants.KEY_SET_SHOW_DIALOG_RATE] = true
                    if (isFinish) {
                        activity.finishAffinity()
                        activity.finish()
                    }
                }
            } else {
                if (isFinish) {
                    activity.finishAffinity()
                    activity.finish()
                }
            }
        }
    })
    try {
        dialog.show()
    } catch (e: WindowManager.BadTokenException) {
        e.printStackTrace()
    }
}
