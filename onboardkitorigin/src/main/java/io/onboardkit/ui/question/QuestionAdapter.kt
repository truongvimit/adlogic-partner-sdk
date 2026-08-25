package io.onboardkit.ui.question

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.databinding.ObItemQuestionOptionBinding

internal class QuestionAdapter(
    private val question: QuestionConfig,
    private val onToggle: (QuestionOption, Boolean) -> Unit,
) : RecyclerView.Adapter<QuestionAdapter.OptionHolder>() {

    /**
     * Single source of truth, owned by the host screen. The adapter deliberately keeps no
     * selection state of its own: a local copy drifts from the screen's set the moment the two
     * disagree (e.g. single-select clearing), which is how a deselect could leave a ticked row
     * while the CTA read "nothing selected".
     */
    var selectedIds: Set<String> = emptySet()
        set(value) {
            val previous = field
            if (previous == value) return
            field = value
            val changed = (previous - value) + (value - previous)
            question.options.forEachIndexed { index, option ->
                if (option.id in changed) notifyItemChanged(index)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionHolder {
        val binding =
            ObItemQuestionOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OptionHolder(binding)
    }

    override fun getItemCount(): Int = question.options.size

    override fun onBindViewHolder(holder: OptionHolder, position: Int) {
        holder.bind(question.options[position])
    }

    inner class OptionHolder(private val binding: ObItemQuestionOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(option: QuestionOption) = with(binding) {
            obOptionTitle.text = option.title
                ?: option.titleRes.takeIf { it != 0 }?.let { root.context.getString(it) }
                ?: option.id
            when {
                option.imageUrl != null -> Glide.with(obOptionImage)
                    .load(option.imageUrl)
                    .centerCrop()
                    .into(obOptionImage)

                option.imageRes != 0 -> obOptionImage.setImageResource(option.imageRes)

                else -> obOptionImage.setImageDrawable(null)
            }
            val isSelected = option.id in selectedIds
            obOptionTick.visibility = if (isSelected) View.VISIBLE else View.GONE
            root.setOnClickListener {
                // Report the intent only; the host applies its selection rules and pushes the
                // resulting set back through [selectedIds], which re-renders the tick.
                onToggle(option, option.id !in selectedIds)
            }
        }
    }
}
