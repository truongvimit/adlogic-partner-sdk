package io.onboardkit.ui.pool

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/** Placeholder; replaced by the LFO stage implementation. */
internal class PoolLanguageFragment : Fragment(), PoolStage {
    override val blocksAppResume: Boolean get() = false

    companion object {
        private const val ARG_RESTORED_LANGUAGE = "ob_pool_restored_language"

        fun newInstance(restoredLanguage: String?): PoolLanguageFragment =
            PoolLanguageFragment().apply { arguments = bundleOf(ARG_RESTORED_LANGUAGE to restoredLanguage) }
    }
}
