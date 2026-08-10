package io.onboardkit.ui.language

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.onboardkit.config.ObLanguage
import io.onboardkit.databinding.ObItemLanguageBinding

/**
 * Flat language list per Figma "LFO" (node 14:33160): every language is its own row with
 * flag + name + radio, no expandable regional grouping.
 */
internal class LanguageAdapter(
    private val onLanguageTapped: (ObLanguage) -> Unit,
) : ListAdapter<ObLanguage, LanguageAdapter.RowHolder>(Diff) {

    var selectedCode: String? = null
        set(value) {
            val previous = field
            field = value
            if (previous == value) return
            notifyItemChangedForCode(previous)
            notifyItemChangedForCode(value)
        }

    private fun notifyItemChangedForCode(code: String?) {
        if (code == null) return
        val index = currentList.indexOfFirst { it.code == code }
        if (index != RecyclerView.NO_POSITION) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding =
            ObItemLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowHolder(binding)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RowHolder(private val binding: ObItemLanguageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(language: ObLanguage) = with(binding) {
            obLanguageFlag.setImageResource(language.flagRes)
            obLanguageName.text = language.displayName
            obLanguageRadio.visibility = View.VISIBLE
            obLanguageRadio.isSelected = language.code == selectedCode
            root.setOnClickListener { onLanguageTapped(language) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ObLanguage>() {
        override fun areItemsTheSame(oldItem: ObLanguage, newItem: ObLanguage): Boolean =
            oldItem.code == newItem.code

        override fun areContentsTheSame(oldItem: ObLanguage, newItem: ObLanguage): Boolean =
            oldItem == newItem
    }
}
