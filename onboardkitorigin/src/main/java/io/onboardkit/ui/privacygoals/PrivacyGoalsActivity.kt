package io.onboardkit.ui.privacygoals

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.PrivacyGoalsScreenConfig
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.QuestionOption
import io.onboardkit.config.SelectionMode
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.ui.base.BaseOnboardActivity

/** SDK controller for partner-owned Privacy -> Goal XML screens. */
class PrivacyGoalsActivity : BaseOnboardActivity() {
    private enum class Screen { PRIVACY, GOAL }
    override val screenName = "ob_privacy_goals_screen"
    private val swapped = mutableSetOf<AdPlacement>()
    private val boundPlacements = mutableSetOf<AdPlacement>()
    private var screen = Screen.PRIVACY; private var accepted = false
    private var goalConfig: GoalsScreenConfig? = null; private val selectedIds = linkedSetOf<String>()
    private var adapter: PrivacyGoalsOptionAdapter? = null; private var firstContainer: FrameLayout? = null; private var action: View? = null; private var pending: ViewGroup? = null

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val flow = sdk.requireConfig().privacyGoalsScreen
        // A stale Activity can survive a remote flag update. Do not mark onboarding complete in
        // that case: the normal host owns completion when this optional tail is disabled.
        if (!sdk.privacyGoalsScreenEnabled()) { finish(); return }
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.PRIVACY
        accepted = savedInstanceState?.getBoolean(KEY_ACCEPTED) == true
        if (screen == Screen.GOAL) showGoal(flow) else showPrivacy(flow)
    }
    private fun showPrivacy(flow: PrivacyGoalsScreenConfig) {
        clearAds()
        val cfg = flow.privacy; screen = Screen.PRIVACY; val root = inflate(cfg.layoutRes)
        val consent = required(root, cfg.consentViewId, "privacy.consentViewId")
        val ad = frame(root, cfg.adContainerId.takeIf { it != 0 } ?: io.onboardkit.R.id.ob_privacy_goals_ad, "privacy.adContainerId")
        val cta = actionView(root, cfg.continueViewId, setOf(consent, ad), "privacy action"); action = cta
        setContentView(root); sdk.preload().preloadPrivacy2(this)
        bindConsent(consent, accepted) { checked ->
            accepted = checked
            cta.isEnabled = checked
            if (checked) {
                // Goal slot 1 starts warming as soon as the user opts in, while Privacy ALT
                // replaces the visible card independently.
                sdk.preload().preloadGoal1(this)
                replaceAd(placement(StepId.PARTNER_PRIVACY_ALT), ad)
            }
        }
        cta.isEnabled = accepted; cta.setOnClickListener { if (accepted) showGoal(flow) }; firstContainer = ad
        initialAd(placement(StepId.PARTNER_PRIVACY), ad)
    }
    private fun showGoal(flow: PrivacyGoalsScreenConfig) {
        clearAds()
        sdk.preload().preloadGoal2(this)
        val cfg = flow.goal; screen = Screen.GOAL; goalConfig = cfg; selectedIds.clear(); val root = inflate(cfg.layoutRes)
        val list = required(root, cfg.optionsViewId, "goal.optionsViewId") as? RecyclerView ?: error("Privacy Goals optionsViewId must reference RecyclerView")
        val ad = frame(root, cfg.adContainerId.takeIf { it != 0 } ?: io.onboardkit.R.id.ob_privacy_goals_ad, "goal.adContainerId")
        val next = actionView(root, cfg.nextViewId, setOf(list, ad), "goal action"); action = next; setContentView(root)
        if (list.layoutManager == null) list.layoutManager = GridLayoutManager(this, 2)
        val options = sdk.privacyGoalOptions(sdk.requireConfig()); adapter = PrivacyGoalsOptionAdapter(options, cfg) { option, selected -> toggle(cfg, option.id, selected) }.also { list.adapter = it }
        next.isEnabled = false; next.visibility = View.VISIBLE; next.setOnClickListener { if (selectedIds.size >= cfg.minSelection) finishGoal(options) }; firstContainer = ad
        initialAd(placement(StepId.PARTNER_GOAL), ad)
    }
    private fun toggle(cfg: GoalsScreenConfig, id: String, selected: Boolean) {
        if (cfg.selectionMode == SelectionMode.SINGLE) { selectedIds.clear(); if (selected) selectedIds += id } else if (selected) selectedIds += id else selectedIds -= id
        adapter?.selectedIds = selectedIds.toSet()
        action?.let {
            it.isEnabled = selectedIds.size >= cfg.minSelection
        }
        if (selected && selectedIds.size == 1) firstContainer?.let { replaceAd(placement(StepId.PARTNER_GOAL_ALT), it) }
    }
    private fun finishGoal(options: List<QuestionOption>) { val answers = options.filter { it.id in selectedIds }.map { QuestionAnswer(it.id, it.title?.toString() ?: it.id) }; if (answers.isNotEmpty()) { OnboardingSdk.persistAnswers(answers); OnboardingSdk.emitEvent(OnboardingEvent.QuestionAnswered(answers)) }; continueFlow() }
    private fun initialAd(p: AdPlacement, container: FrameLayout) {
        boundPlacements += p
        showNativeAd(
            placement = p,
            unit = sdk.requireConfig().ads.nativeUnitFor(p),
            container = container,
            onUnavailable = {
                // A late failure from slot 1 must not hide a successfully swapped slot 2.
                if (p in boundPlacements && swapped.isEmpty()) container.isVisible = false
            },
        )
    }

    private fun replaceAd(p: AdPlacement, container: FrameLayout) {
        if (pending != null || p in swapped || sdk.guard().skipReason(this, p) != null) return
        val host = container.parent as? ViewGroup ?: return
        val staging = FrameLayout(this).apply { visibility = View.GONE }
        val replacement = FrameLayout(this)
        // Both buffers wrap the same horizontal card. MATCH_PARENT here creates a circular
        // measurement when the destination slot is WRAP_CONTENT, collapsing the ALT card.
        staging.addView(replacement, FrameLayout.LayoutParams(-1, -2))
        host.addView(staging, ViewGroup.LayoutParams(-1, -2))
        pending = staging
        boundPlacements += p
        showNativeAd(
            placement = p,
            unit = sdk.requireConfig().ads.nativeUnitFor(p),
            container = replacement,
            onBound = {
                if (pending === staging) {
                    // Recheck the group and per-screen gates after an asynchronous load.
                    if (sdk.guard().skipReason(this, p) == null) {
                        staging.removeView(replacement)
                        swapped += p
                        val previous = placement(
                            if (screen == Screen.PRIVACY) StepId.PARTNER_PRIVACY else StepId.PARTNER_GOAL,
                        )
                        boundPlacements -= previous
                        sdk.provider()?.releaseNative(previous)
                        container.removeAllViews()
                        container.addView(replacement, FrameLayout.LayoutParams(-1, -2))
                        container.isVisible = true
                        if (p == placement(StepId.PARTNER_PRIVACY_ALT)) sdk.preload().preloadGoal1(this)
                    } else {
                        boundPlacements -= p
                        sdk.provider()?.releaseNative(p)
                    }
                    host.removeView(staging)
                    pending = null
                }
            },
            onUnavailable = {
                if (pending === staging) {
                    host.removeView(staging)
                    pending = null
                }
            },
        )
    }
    private fun bindConsent(view: View, initial: Boolean, onChanged: (Boolean) -> Unit) { when (view) { is CompoundButton -> { view.setOnCheckedChangeListener(null); view.isChecked = initial; view.setOnCheckedChangeListener { _, v -> onChanged(v) } }; is Checkable -> { view.isChecked = initial; view.setOnClickListener { view.isChecked = !view.isChecked; onChanged(view.isChecked) } }; else -> { view.isSelected = initial; view.setOnClickListener { view.isSelected = !view.isSelected; onChanged(view.isSelected) } } } }
    private fun continueFlow() { if (isFinishing || isDestroyed) return; OnboardingSdk.completeFlow(this); finish() }
    override fun handleBack() { if (screen == Screen.GOAL) showPrivacy(sdk.requireConfig().privacyGoalsScreen) else finishAffinity() }
    override fun onSaveInstanceState(out: Bundle) { out.putString(KEY_SCREEN, screen.name); out.putBoolean(KEY_ACCEPTED, accepted); super.onSaveInstanceState(out) }
    private fun clearAds() {
        val old = pending
        pending = null
        (old?.parent as? ViewGroup)?.removeView(old)
        swapped.clear()
        // Release only ads bound to the outgoing screen. Privacy 1 and Goal 1 can already be
        // buffered for the incoming screen and must survive this transition.
        boundPlacements.forEach { sdk.provider()?.releaseNative(it) }
        boundPlacements.clear()
    }
    override fun onDestroy() {
        clearAds()
        if (!isChangingConfigurations) {
            listOf(StepId.PARTNER_PRIVACY, StepId.PARTNER_PRIVACY_ALT, StepId.PARTNER_GOAL, StepId.PARTNER_GOAL_ALT)
                .forEach { sdk.provider()?.releaseNative(placement(it)) }
        }
        super.onDestroy()
    }
    private fun inflate(res: Int) = LayoutInflater.from(this).inflate(res, null, false)
    private fun required(root: View, id: Int, name: String) = root.findViewById<View>(id) ?: error("Privacy Goals layout is missing $name (id=$id)")
    private fun frame(root: View, id: Int, name: String) = required(root, id, name) as? FrameLayout ?: error("Privacy Goals $name must reference FrameLayout")
    private fun actionView(root: View, legacyId: Int, excluded: Set<View>, name: String): View =
        findAction(root, legacyId, excluded) ?: error(
            "Privacy Goals layout is missing $name. Add a clickable Button/TextView or mark it with @id/ob_privacy_goals_continue",
        )

    /**
     * Action buttons are deliberately not part of the required partner ID contract. A partner can
     * draw the CTA with any Button/TextView (or use the optional shared marker ID), while the SDK
     * still owns enabled state and navigation.
     */
    private fun findAction(root: View, legacyId: Int, excluded: Set<View>): View? {
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
    private fun placement(id: StepId): AdPlacement = AdPlacement.StepNative(id)
    companion object { private const val KEY_SCREEN = "ob_privacy_goals_screen"; private const val KEY_ACCEPTED = "ob_privacy_goals_accepted"; fun start(activity: Activity) { activity.startActivity(Intent(activity, PrivacyGoalsActivity::class.java)) } }
}
