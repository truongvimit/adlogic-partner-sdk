package io.onboardkit.ui.resume

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import io.onboardkit.R

/** Partner-owned native resume layout contract. */
internal object ResumeScreenViews {
    data class Bound(
        val root: View,
        val ad: FrameLayout,
        val adBlock: View,
        val start: View,
    )

    fun inflate(inflater: LayoutInflater, @LayoutRes layoutRes: Int = R.layout.ob_resume_screen): Bound {
        val root = inflater.inflate(layoutRes, null, false)
        val ad = root.findViewById<View>(R.id.ob_resume_ad) as? FrameLayout
            ?: error("Native resume layout is missing FrameLayout @id/ob_resume_ad")
        val adBlock = root.findViewById<View>(R.id.ob_resume_ad_block) ?: ad
        val start = root.findViewById<View>(R.id.ob_resume_start)
            ?: findAction(root, setOf(ad, adBlock))
            ?: error("Native resume layout is missing a Start Button/TextView (use @id/ob_resume_start)")
        return Bound(root, ad, adBlock, start)
    }

    fun bindOptionalCopy(bound: Bound, imageRes: Int, title: CharSequence?, subtitle: CharSequence?, context: Context) {
        bound.root.findViewById<ImageView?>(R.id.ob_resume_image)?.setImageResource(imageRes)
        bound.root.findViewById<TextView?>(R.id.ob_resume_title)?.text = title ?: context.getString(R.string.ob_resume_title)
        bound.root.findViewById<TextView?>(R.id.ob_resume_subtitle)?.text = subtitle ?: context.getString(R.string.ob_resume_subtitle)
    }

    private fun findAction(root: View, excluded: Set<View>): View? {
        val candidates = ArrayList<View>()
        fun walk(view: View) {
            if (view in excluded) return
            if (view is Button || (view is TextView && view.isClickable) ||
                (view !is ViewGroup && view !is CompoundButton && view.isClickable)
            ) candidates += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return candidates.firstOrNull()
    }
}
