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

    var selectedIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val selected = mutableSetOf<String>()

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
            val isSelected = option.id in selected || option.id in selectedIds
            obOptionTick.visibility = if (isSelected) View.VISIBLE else View.GONE
            root.setOnClickListener {
                val nowSelected = option.id !in selected
                if (nowSelected) selected.add(option.id) else selected.remove(option.id)
                obOptionTick.visibility = if (nowSelected) View.VISIBLE else View.GONE
                onToggle(option, nowSelected)
            }
        }
    }
}
