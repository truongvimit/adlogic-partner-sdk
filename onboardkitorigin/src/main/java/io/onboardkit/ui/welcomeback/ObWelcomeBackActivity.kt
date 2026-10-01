package io.onboardkit.ui.welcomeback

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.GoalOption
import io.onboardkit.config.label
import io.onboardkit.core.ObLog
import io.onboardkit.core.GoalAnswer
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.widget.GoalOptionAdapter
import kotlinx.coroutines.launch

/**
 * Returning user, launcher tap: shown on every such launch. Pick one option, then Continue hands
 * the user to the app without marking anything complete.
 *
 * Slot 1 ([AdPlacement.WelcomeBack1]) was preloaded by the splash; slot 2 is preloaded here and
 * replaces slot 1 on the first tap.
 */
class ObWelcomeBackActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_welcome_back"

    private var options: List<GoalOption> = emptyList()
    private var selectedId: String? = null
    private var adapter: GoalOptionAdapter? = null
    private var cta: View? = null
    private var adBlock: ViewGroup? = null
    private var adContainer: FrameLayout? = null
    private var pending: ViewGroup? = null
    private var swapped = false
    private var reuseWelcome1Preload = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        val cfg = sdk.requireConfig().welcomeBackScreen
        options = cfg.options
        if (!sdk.welcomeBackEnabled()) {
            lifecycleScope.launch { lifecycle.withResumed { continueFlow() } }
            return
        }
        // The splash handoff is consumed once; a recreated screen reads it back from its state.
        reuseWelcome1Preload = savedInstanceState?.getBoolean(KEY_REUSE_PRELOAD)
            ?: sdk.preload().takeWelcome1Preload()
        selectedId = savedInstanceState?.getString(KEY_SELECTED)?.takeIf { id -> options.any { it.id == id } }

        val root = LayoutInflater.from(this).inflate(cfg.layoutRes, null, false)
        val list = root.findViewById<RecyclerView?>(R.id.ob_welcome_back_options)
            ?: error("Welcome Back layout is missing RecyclerView @id/ob_welcome_back_options")
        val action = root.findViewById<View?>(R.id.ob_welcome_back_continue)
            ?: error("Welcome Back layout is missing @id/ob_welcome_back_continue")
        val ad = root.findViewById<FrameLayout?>(R.id.ob_welcome_back_ad)
            ?: error("Welcome Back layout is missing FrameLayout @id/ob_welcome_back_ad")
        setContentView(root)
        cta = action
        adContainer = ad
        adBlock = ad.parent as? ViewGroup

        if (list.layoutManager == null) list.layoutManager = GridLayoutManager(this, GRID_SPAN)
        adapter = GoalOptionAdapter(options, cfg.optionLayoutRes) { option, selected -> onOptionTapped(option, selected) }
            .also {
                it.selectedIds = setOfNotNull(selectedId)
                list.adapter = it
            }
        bindCta()
        action.setOnClickListener { if (selectedId != null) onContinue() }

        // Warm slot 2 before binding slot 1, as the goal screen does.
        sdk.preload().preloadWelcome2(this)
        showInitialAd(ad)
    }

    private fun onOptionTapped(option: GoalOption, selected: Boolean) {
        // Single choice: tapping the chosen card keeps it chosen.
        if (!selected) return
        val first = selectedId == null
        selectedId = option.id
        adapter?.selectedIds = setOf(option.id)
        bindCta()
        if (first) adContainer?.let(::replaceAd)
    }

    private fun bindCta() {
        cta?.visibility = if (selectedId != null) View.VISIBLE else View.INVISIBLE
    }

    private fun showInitialAd(container: FrameLayout) {
        showNativeAd(
            placement = AdPlacement.WelcomeBack1,
            unit = sdk.requireConfig().ads.nativeUnitFor(AdPlacement.WelcomeBack1),
            container = container,
            reuseFailedPreload = reuseWelcome1Preload,
            onUnavailable = {
                // A late failure from slot 1 must not hide a successfully swapped slot 2.
                if (!swapped) adBlock?.isVisible = false
            },
        )
    }

    private fun replaceAd(container: FrameLayout) {
        val placement = AdPlacement.WelcomeBack2
        if (pending != null || swapped || sdk.guard().skipReason(this, placement) != null) return
        val host = adBlock ?: return
        val staging = FrameLayout(this).apply { visibility = View.GONE }
        val replacement = FrameLayout(this)
        staging.addView(replacement, FrameLayout.LayoutParams(-1, -1))
        host.addView(staging, ViewGroup.LayoutParams(-1, -1))
        pending = staging
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.nativeUnitFor(placement),
            container = replacement,
            onBound = {
                if (pending === staging) {
                    if (sdk.guard().skipReason(this, placement) == null) {
                        staging.removeView(replacement)
                        swapped = true
                        sdk.provider()?.releaseNative(AdPlacement.WelcomeBack1)
                        container.removeAllViews()
                        container.addView(replacement, FrameLayout.LayoutParams(-1, -1))
                        container.isVisible = true
                        host.isVisible = true
                    } else {
                        sdk.provider()?.releaseNative(placement)
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

    private fun onContinue() {
        val option = options.firstOrNull { it.id == selectedId } ?: return
        OnboardingSdk.recordGoals(listOf(GoalAnswer(option.id, option.label(this))))
        continueFlow()
    }

    private fun continueFlow() {
        if (isFinishing || isDestroyed) return
        ObLog.d(ObLog.Section.NAV, "from=ob_welcome_back selected=$selectedId")
        OnboardingSdk.finishWelcomeBack(this)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_REUSE_PRELOAD, reuseWelcome1Preload)
        selectedId?.let { outState.putString(KEY_SELECTED, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        (pending?.parent as? ViewGroup)?.removeView(pending)
        pending = null
        if (!isChangingConfigurations) {
            sdk.provider()?.releaseNative(AdPlacement.WelcomeBack1)
            sdk.provider()?.releaseNative(AdPlacement.WelcomeBack2)
        }
        super.onDestroy()
    }

    companion object {
        private const val GRID_SPAN = 2
        private const val KEY_REUSE_PRELOAD = "ob_welcome_back_reuse_preload"
        private const val KEY_SELECTED = "ob_welcome_back_selected"

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ObWelcomeBackActivity::class.java))
        }
    }
}
