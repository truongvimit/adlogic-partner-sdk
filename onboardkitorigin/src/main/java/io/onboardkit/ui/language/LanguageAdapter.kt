package io.onboardkit.ui.language

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.onboardkit.R
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
            // The first selection retires the hint, so its row has to be redrawn too
            if (previous == null) notifyItemChangedForCode(hintCode)
        }

    /**
     * Row that shows the animated "tap here" hand while nothing has been picked yet — the device
     * language, or English when the device language is not on the list.
     */
    var hintCode: String? = null
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
            bindHint(language)
        }

        /**
         * The hint disappears for good once the user selects anything — it is a nudge for the
         * untouched screen, not a decoration. Glide is asked for the GIF only on the one row that
         * shows it, and cleared on every other row so a recycled holder cannot keep animating.
         */
        private fun bindHint(language: ObLanguage): Unit = with(binding) {
            val show = selectedCode == null && language.code == hintCode
            if (!show) {
                if (obLanguageHint.visibility != View.GONE) {
                    Glide.with(obLanguageHint).clear(obLanguageHint)
                    obLanguageHint.setImageDrawable(null)
                    obLanguageHint.visibility = View.GONE
                }
                return
            }
            // Already running (a plain rebind of the same row) — reloading would restart the loop
            if (obLanguageHint.isVisible && obLanguageHint.drawable != null) return
            obLanguageHint.visibility = View.VISIBLE
            Glide.with(obLanguageHint)
                .load(R.raw.ob_anim_hand_tap)
                .into(obLanguageHint)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ObLanguage>() {
        override fun areItemsTheSame(oldItem: ObLanguage, newItem: ObLanguage): Boolean =
            oldItem.code == newItem.code

        override fun areContentsTheSame(oldItem: ObLanguage, newItem: ObLanguage): Boolean =
            oldItem == newItem
    }
}
