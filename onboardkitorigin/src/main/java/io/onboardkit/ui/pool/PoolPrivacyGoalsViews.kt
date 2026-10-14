package io.onboardkit.ui.pool

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Checkable
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.LayoutRes

/**
 * The partner layout contract of the Privacy and Goal screens, as PrivacyGoalsActivity reads it:
 * the same required ids, the same action discovery and the same consent binding, so a partner
 * layout works on both flows.
 */
internal object PoolPrivacyGoalsViews {

    fun inflate(inflater: LayoutInflater, parent: ViewGroup?, @LayoutRes res: Int): View =
        inflater.inflate(res, parent, false)

    fun required(root: View, id: Int, name: String): View =
        root.findViewById<View>(id) ?: error("Privacy Goals layout is missing $name (id=$id)")

    fun frame(root: View, id: Int, name: String): FrameLayout =
        required(root, id, name) as? FrameLayout ?: error("Privacy Goals $name must reference FrameLayout")

    fun actionView(root: View, legacyId: Int, excluded: Set<View>, name: String): View =
        findAction(root, legacyId, excluded) ?: error(
            "Privacy Goals layout is missing $name. Add a clickable Button/TextView or mark it with @id/ob_privacy_goals_continue",
        )

    /**
     * Action buttons are not part of the required partner id contract: any Button/TextView works,
     * or the optional shared marker id, while the SDK owns enabled state and navigation.
     */
    fun findAction(root: View, legacyId: Int, excluded: Set<View>): View? {
        if (legacyId != 0) root.findViewById<View>(legacyId)?.let { return it }
        root.findViewById<View>(io.onboardkit.R.id.ob_privacy_goals_continue)?.let { return it }
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

    fun bindConsent(view: View, initial: Boolean, onChanged: (Boolean) -> Unit) {
        when (view) {
            is CompoundButton -> {
                view.setOnCheckedChangeListener(null)
                view.isChecked = initial
                view.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
            }
            is Checkable -> {
                view.isChecked = initial
                view.setOnClickListener {
                    view.isChecked = !view.isChecked
                    onChanged(view.isChecked)
                }
            }
            else -> {
                view.isSelected = initial
                view.setOnClickListener {
                    view.isSelected = !view.isSelected
                    onChanged(view.isSelected)
                }
            }
        }
    }
}
