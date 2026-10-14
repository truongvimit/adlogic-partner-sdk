package io.onboardkit.ui.pool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.config.GoalOption
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.SelectionMode
import io.onboardkit.config.label
import io.onboardkit.core.GoalAnswer
import io.onboardkit.core.StepId
import io.onboardkit.ui.widget.GoalOptionAdapter

/**
 * The Goal step as a pooled select screen (DashDrama Favorite): every option tap is a selection,
 * Next is enabled while enough options are picked, and Next ends the run after the leftover
 * splash interstitial, with no paywall.
 */
internal class PoolGoalFragment : Fragment(), PoolStage {

    private val select = PoolSelectScreen(
        fragment = this,
        ad1 = AdPlacement.StepNative(StepId.PARTNER_GOAL),
        ad2 = AdPlacement.StepNative(StepId.PARTNER_GOAL_ALT),
    )
    private val selectedIds = linkedSetOf<String>()
    private var adapter: GoalOptionAdapter? = null
    private var next: View? = null
    private var exiting = false

    override val blocksAppResume: Boolean get() = exiting

    private val config: GoalsScreenConfig
        get() = OnboardingSdk.requireConfig().privacyGoalsScreen.goal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(KEY_SELECTED)?.let(selectedIds::addAll)
        select.restore(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        PoolPrivacyGoalsViews.inflate(inflater, container, config.layoutRes)

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cfg = config
        val list = PoolPrivacyGoalsViews.required(view, cfg.optionsViewId, "goal.optionsViewId") as? RecyclerView
            ?: error("Privacy Goals optionsViewId must reference RecyclerView")
        val ad = PoolPrivacyGoalsViews.frame(
            view, cfg.adContainerId.takeIf { it != 0 } ?: R.id.ob_privacy_goals_ad, "goal.adContainerId",
        )
        val action = PoolPrivacyGoalsViews.actionView(view, cfg.nextViewId, setOf(list, ad), "goal action")
        next = action
        if (list.layoutManager == null) list.layoutManager = GridLayoutManager(requireContext(), 2)
        val options = OnboardingSdk.privacyGoalOptions(OnboardingSdk.requireConfig())
        selectedIds.retainAll(options.map { it.id }.toSet())
        adapter = GoalOptionAdapter(options, cfg.optionLayoutRes) { option, selected -> toggle(cfg, option.id, selected) }
            .also {
                it.selectedIds = selectedIds.toSet()
                list.adapter = it
            }
        select.onViewCreated(ad, action)
        action.isEnabled = selectedIds.size >= cfg.minSelection
        action.setOnClickListener { if (selectedIds.size >= cfg.minSelection) finishGoal(options) }
    }

    private fun toggle(cfg: GoalsScreenConfig, id: String, selected: Boolean) {
        if (cfg.selectionMode == SelectionMode.SINGLE) {
            selectedIds.clear()
            if (selected) selectedIds += id
        } else if (selected) {
            selectedIds += id
        } else {
            selectedIds -= id
        }
        adapter?.selectedIds = selectedIds.toSet()
        next?.isEnabled = selectedIds.size >= cfg.minSelection
        select.onSelection()
    }

    /** DD Favorite Done: the picks, then the leftover splash interstitial, then completion once. */
    private fun finishGoal(options: List<GoalOption>) {
        val host = activity as? ObPoolFlowActivity ?: return
        if (exiting) return
        exiting = true
        val goals = options.filter { it.id in selectedIds }.map { GoalAnswer(it.id, it.label(requireContext())) }
        if (goals.isNotEmpty()) OnboardingSdk.recordGoals(goals)
        host.markExitStarted()
        host.exitWithInterstitial(host.snapshot.goalsExitReuseSplashInter) { host.finishFlow(withPaywall = false) }
    }

    override fun onResume() {
        super.onResume()
        select.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selectedIds))
        select.save(outState)
    }

    override fun onDestroyView() {
        select.onDestroyView()
        adapter = null
        next = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_SELECTED = "ob_pool_goal_selected"
    }
}
