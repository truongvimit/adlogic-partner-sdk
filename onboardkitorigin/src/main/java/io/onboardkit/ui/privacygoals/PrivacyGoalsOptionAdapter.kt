package io.onboardkit.ui.privacygoals

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.QuestionOption

internal class PrivacyGoalsOptionAdapter(private val options: List<QuestionOption>, private val config: GoalsScreenConfig, private val onToggle: (QuestionOption, Boolean) -> Unit) : RecyclerView.Adapter<PrivacyGoalsOptionAdapter.Holder>() {
    var selectedIds: Set<String> = emptySet()
        set(value) { val changed = (field - value) + (value - field); field = value; options.forEachIndexed { i, o -> if (o.id in changed) notifyItemChanged(i) } }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(config.optionLayoutRes, parent, false))
    override fun getItemCount() = options.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(options[position])
    inner class Holder(private val item: View) : RecyclerView.ViewHolder(item) {
        fun bind(option: QuestionOption) {
            val selected = option.id in selectedIds; item.isSelected = selected; (item as? android.widget.Checkable)?.isChecked = selected
            val title = option.title ?: option.titleRes.takeIf { it != 0 }?.let(item.context::getString) ?: option.id
            // Always replace the title. ViewHolders are recycled between options and retaining
            // the previous value makes a card display a different option's label.
            findFirst(item, TextView::class.java)?.text = title
            findFirst(item, ImageView::class.java)?.let { image ->
                when {
                    option.imageUrl != null -> Glide.with(image).load(option.imageUrl).centerCrop().into(image)
                    option.imageRes != 0 -> image.setImageResource(option.imageRes)
                }
            }
            item.setOnClickListener { onToggle(option, option.id !in selectedIds) }
        }

        private fun <T : View> findFirst(root: View, type: Class<T>): T? {
            if (type.isInstance(root)) return type.cast(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) findFirst(root.getChildAt(i), type)?.let { return it }
            return null
        }
    }
}
