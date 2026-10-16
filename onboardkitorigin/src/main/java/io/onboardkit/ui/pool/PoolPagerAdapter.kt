package io.onboardkit.ui.pool

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.StepType
import io.onboardkit.ui.onboarding.ContentStepFragment
import io.onboardkit.ui.pager.LazyStepFragment

/**
 * The pager stage's pages over a list frozen when the stage was created. Content pages are the
 * classic [ContentStepFragment]; full pages render the pool instead of requesting their own ad.
 * The pages are children of [host], so they are looked up in its child fragment manager.
 */
internal class PoolPagerAdapter(
    private val host: Fragment,
    private val pages: List<StepDefinition>,
) : FragmentStateAdapter(host) {

    private val itemIds = pages.map(::stableIdOf)

    fun pageAt(position: Int): StepDefinition? = pages.getOrNull(position)

    fun fragmentAt(position: Int): LazyStepFragment? {
        val itemId = itemIds.getOrNull(position) ?: return null
        return host.childFragmentManager.findFragmentByTag("f$itemId") as? LazyStepFragment
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = itemIds[position]

    override fun containsItem(itemId: Long): Boolean = itemId in itemIds

    override fun createFragment(position: Int): Fragment {
        val page = pages[position]
        return when (page.type) {
            StepType.CONTENT -> ContentStepFragment.newInstance(page.id, position)
            StepType.AD_FULL_SCREEN -> PoolFullAdPageFragment.newInstance(page.id, position)
        }
    }

    private fun stableIdOf(page: StepDefinition): Long =
        (page.id.value.hashCode().toLong() shl 32) or page.type.ordinal.toLong()
}
