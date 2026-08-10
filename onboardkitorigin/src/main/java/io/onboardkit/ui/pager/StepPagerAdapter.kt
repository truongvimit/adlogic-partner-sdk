package io.onboardkit.ui.pager

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.onboardkit.config.StepDefinition
import io.onboardkit.core.StepType
import io.onboardkit.ui.onboarding.AdStepFragment
import io.onboardkit.ui.onboarding.ContentStepFragment

/**
 * One page of the flow. [variantKey] is what hot-swap compares — no empty fragment
 * subclasses needed just to force recreation.
 */
data class StepPage(
    val definition: StepDefinition,
    val variantKey: String = "default",
) {
    val stableId: Long =
        (definition.id.value.hashCode().toLong() shl 32) or
            (variantKey.hashCode().toLong() and 0xFFFFFFFFL)
}

/**
 * FragmentStateAdapter with stable ids derived from (stepId, variantKey): changing a page's
 * variant changes its id, which makes ViewPager2 rebuild exactly that page.
 * [submit] skips the currently visible page so a hot-swap never yanks the screen mid-view.
 */
class StepPagerAdapter(
    private val host: FragmentActivity,
) : FragmentStateAdapter(host) {

    private val pages = mutableListOf<StepPage>()

    fun currentPages(): List<StepPage> = pages.toList()

    fun pageAt(position: Int): StepPage? = pages.getOrNull(position)

    fun submit(newPages: List<StepPage>, visibleIndex: Int) {
        if (newPages.map { it.definition.id } != pages.map { it.definition.id }) {
            pages.clear()
            pages.addAll(newPages)
            notifyDataSetChanged()
            return
        }
        var changed = false
        newPages.forEachIndexed { index, newPage ->
            val old = pages[index]
            val skipVisible = index == visibleIndex && old.variantKey != newPage.variantKey
            if (!skipVisible && old.variantKey != newPage.variantKey) {
                pages[index] = newPage
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    fun fragmentAt(position: Int): LazyStepFragment? {
        val page = pageAt(position) ?: return null
        return host.supportFragmentManager
            .findFragmentByTag("f${page.stableId}") as? LazyStepFragment
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = pages[position].stableId

    override fun containsItem(itemId: Long): Boolean = pages.any { it.stableId == itemId }

    override fun createFragment(position: Int): Fragment {
        val page = pages[position]
        return when (page.definition.type) {
            StepType.CONTENT -> ContentStepFragment.newInstance(page.definition.id, position)
            StepType.AD_FULL_SCREEN -> AdStepFragment.newInstance(page.definition.id, position)
        }
    }
}
