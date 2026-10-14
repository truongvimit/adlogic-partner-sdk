package io.onboardkit.ui.pool

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ads.module.helper.adnative.pool.LoadMode
import com.ads.module.helper.adnative.pool.LoadResult
import com.ads.module.helper.adnative.pool.reportsSuccess
import com.bumptech.glide.Glide
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.ads.pool.OnboardingPool
import io.onboardkit.ads.pool.PoolNativeSlot
import io.onboardkit.ads.pool.PoolSplashAdoption
import io.onboardkit.ads.pool.screenSlotMinState
import io.onboardkit.config.LanguageConfirmButtonStyle
import io.onboardkit.config.ObLanguage
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityLanguageBinding
import io.onboardkit.flow.NativeFlowSnapshot
import io.onboardkit.remote.OnboardingSettings
import io.onboardkit.ui.language.LanguageAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pooled LFO (DashDrama LanguageFirstFragment). One owner slot renders the pool's selection
 * under the Language1 template until a tap's Language2 claim succeeds, then under Language2; the
 * confirm control appears a fixed delay after the first tap, never before.
 *
 * Analytics timing (deviation D-ANALYTICS-ORDER): there is no second slot to swap in, so
 * `LanguageCompleted(1)` and `LanguageViewed(2)` fire when the first tap makes Language2 the owner,
 * whether or not its ad has filled yet. The pick is persisted only after the exit interstitial,
 * right before the next stage applies its locale.
 */
internal class PoolLanguageFragment : Fragment(), PoolStage {

    private var binding: ObActivityLanguageBinding? = null
    private var adapter: LanguageAdapter? = null
    private var slot: PoolNativeSlot? = null
    private var dialog: PoolConfirmLanguageDialog? = null
    private var revealJob: Job? = null

    private var selectedCode: String? = null
    private var tapCount = 0

    /** DashDrama's screen index: Language1 until a tap's Language2 claim reports success. */
    private var currentKey: AdPlacement = AdPlacement.Language1

    /** What onDestroyView clears: the LFO key this screen last made the owner. */
    private var ownedKey: AdPlacement? = null

    /** The splash's LFO1 handoff, spent by the first adoption so a restore cannot adopt twice. */
    private var handoff = false
    private var confirmRevealed = false
    private var resumedOnce = false
    private var exitLatched = false
    private var tearingDown = false

    override val blocksAppResume: Boolean get() = exitLatched || dialog?.isShowing == true

    private val host: ObPoolFlowActivity? get() = activity as? ObPoolFlowActivity

    private val snapshot: NativeFlowSnapshot
        get() = host?.snapshot ?: OnboardingSdk.session.nativeFlow ?: OnboardingSettings.nativeFlowSnapshot()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ObActivityLanguageBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = requireNotNull(binding)
        tearingDown = false
        val languages = OnboardingSdk.requireConfig().language.languages
        fun offered(code: String?): String? = code?.takeIf { c -> languages.any { it.code == c } }
        val restored = arguments?.getString(ARG_RESTORED_LANGUAGE)
        when {
            // A recreation keeps what the user did after any OB1-back restore.
            savedInstanceState != null -> {
                selectedCode = offered(savedInstanceState.getString(KEY_SELECTED))
                tapCount = savedInstanceState.getInt(KEY_TAPS)
                if (savedInstanceState.getBoolean(KEY_ON_LANGUAGE2)) currentKey = AdPlacement.Language2
                confirmRevealed = savedInstanceState.getBoolean(KEY_CONFIRM_REVEALED)
                handoff = savedInstanceState.getBoolean(KEY_HANDOFF)
            }
            // OB1 back (D-OB1BACK): the pick and LFO2 come back, the splash handoff stays spent.
            restored != null -> {
                selectedCode = offered(restored)
                tapCount = 1
                currentKey = AdPlacement.Language2
                confirmRevealed = selectedCode != null
            }
            else -> {
                // DashDrama preselects the saved language but keeps Done hidden until a tap.
                selectedCode = offered(OnboardingSdk.selectedLanguageOrNull())
                handoff = OnboardingSdk.preload().takeLanguage1Preload()
            }
        }

        // Recreated under its own exit's ad: Done was tapped and the exit runs on this host.
        exitLatched = exitLatched || host?.pendingExit is PoolExit.Language

        adapter = LanguageAdapter(::onLanguageTapped, OnboardingSettings.onboardingPrimaryColor()).also {
            it.selectedCode = selectedCode
            b.obLanguageList.layoutManager = LinearLayoutManager(requireContext())
            b.obLanguageList.adapter = it
            it.submitList(languages)
        }
        bindConfirmChrome(b)
        bindConfirm()
        b.obLanguageConfirm.setOnClickListener { onDone() }
        b.obAdBlock2.visibility = View.GONE

        val flow = snapshot
        slot = PoolNativeSlot(
            owner = viewLifecycleOwner,
            placement = currentKey,
            kind = PoolNativeSlot.Kind.OWNER,
            minState = flow.screenSlotMinState,
            container = b.obNativeContainer,
            destroyOnTeardown = flow.viewDestroyOnTeardown,
        ).also { it.start() }
        if (exitLatched) {
            // Requests nothing (see onResume) and is no new view of the screen.
            OnboardingPool.setOwner(currentKey)
            ownedKey = currentKey
            return
        }
        // Before the claim, so it finds LFO1 held or in flight instead of starting a second chain.
        if (restored == null || savedInstanceState != null) {
            PoolSplashAdoption.run(handoff, flow)
            handoff = false
        }
        claimCurrent()
        if (tapCount > 0 && selectedCode != null && !confirmRevealed) scheduleReveal()
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(screenIndex(currentKey), variant = variant(currentKey)))
    }

    /** The confirm control's look, as on the classic first-open LFO; its tap hint is not ported. */
    private fun bindConfirmChrome(b: ObActivityLanguageBinding) {
        val confirmStyle = OnboardingSdk.requireConfig().language.confirmButtonStyle
        b.obLanguageConfirm.contentDescription = getString(
            if (confirmStyle == LanguageConfirmButtonStyle.TEXT) R.string.ob_language_done else R.string.ob_next,
        )
        b.obLanguageConfirmIcon.isVisible = confirmStyle == LanguageConfirmButtonStyle.CHECK_ICON
        b.obLanguageConfirmText.isVisible = confirmStyle == LanguageConfirmButtonStyle.TEXT
        OnboardingSettings.text("lfo.confirm_button.image_url").takeIf {
            it.isNotBlank() && confirmStyle == LanguageConfirmButtonStyle.CHECK_ICON
        }?.let {
            Glide.with(this).load(it).error(R.drawable.ob_ic_check).into(b.obLanguageConfirmIcon)
        }
        OnboardingSettings.onboardingPrimaryColor()?.let { tint ->
            b.obLanguageConfirmIcon.imageTintList = ColorStateList.valueOf(tint)
            b.obLanguageConfirmText.setTextColor(tint)
        }
    }

    /** Hidden (GONE by default, INVISIBLE under `done_hidden_gone=false`) until revealed. */
    private fun bindConfirm() {
        val b = binding ?: return
        b.obLanguageConfirm.alpha = 1f
        b.obLanguageConfirm.visibility = when {
            confirmRevealed -> View.VISIBLE
            snapshot.doneHiddenGone -> View.GONE
            else -> View.INVISIBLE
        }
    }

    private fun scheduleReveal() {
        if (revealJob != null) return
        val delayMs = snapshot.lfoConfirmRevealDelayMs
        revealJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            confirmRevealed = true
            bindConfirm()
        }
    }

    /**
     * DashDrama's LanguageFirstFragment.h: the slot of a placement refused for this run collapses.
     * A transient refusal keeps it: the pool may still lend another screen's ad.
     */
    private fun claimCurrent() {
        val key = currentKey
        val result = OnboardingPool.claim(key, LoadMode.POLITE)
        if (result.reportsSuccess) ownedKey = key
        binding?.obAdBlock?.isVisible = result !is LoadResult.SkippedByConfig
    }

    private fun onLanguageTapped(language: ObLanguage) {
        if (exitLatched) return
        // The DashDrama preselection is not a pick: only a row the user already tapped is a re-tap.
        val reselected = tapCount > 0 && selectedCode == language.code
        if (tapCount < Int.MAX_VALUE) tapCount++
        selectedCode = language.code
        adapter?.selectedCode = language.code
        OnboardingSdk.emitEvent(OnboardingEvent.LanguageSelected(language.code))
        OnboardingSdk.track(AnalyticsEvent.LanguageSelected(screenIndex(currentKey), language.code))
        claimLanguage2(language.code)
        if (!confirmRevealed) scheduleReveal()
        if (shouldShowConfirmDialog(reselected)) showConfirmDialog(language)
    }

    /**
     * DashDrama requests LFO2 FORCED on every tap; the pool turns a repeat into a no-op. Like its
     * h(2, true), a successful claim opens the ad frame a refused Language1 collapsed.
     */
    private fun claimLanguage2(code: String) {
        val result = OnboardingPool.claim(AdPlacement.Language2, LoadMode.FORCED)
        if (!result.reportsSuccess) return
        ownedKey = AdPlacement.Language2
        binding?.obAdBlock?.isVisible = true
        if (currentKey == AdPlacement.Language2) return
        currentKey = AdPlacement.Language2
        slot?.setPlacement(AdPlacement.Language2)
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(1, code))
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(2, variant = variant(AdPlacement.Language2)))
    }

    /** The classic LFO's rule and keys: from the Nth tap, or a tap on the picked row. */
    private fun shouldShowConfirmDialog(reselected: Boolean): Boolean =
        (reselected || tapCount >= OnboardingSettings.number("lfo.confirm_dialog.show_from_tap")) &&
            OnboardingSdk.requireConfig().language.confirmDialogOnReselectEnabled

    /**
     * The dialog is an owner screen of its own: the LFO's slot freezes behind it so the two never
     * fight over one ad's registration, and the owner returns to the LFO key on any close.
     */
    private fun showConfirmDialog(language: ObLanguage) {
        val activity = activity ?: return
        dialog?.dismiss()
        slot?.setFrozen(true)
        OnboardingPool.claim(AdPlacement.LanguageConfirm, LoadMode.POLITE)
        dialog = PoolConfirmLanguageDialog(
            activity = activity,
            language = language,
            destroyOnTeardown = snapshot.viewDestroyOnTeardown,
            slotMinState = snapshot.screenSlotMinState,
            onConfirmed = ::onDone,
            onClosed = ::onConfirmDialogClosed,
        ).also { it.show() }
    }

    private fun onConfirmDialogClosed() {
        dialog = null
        // The view is going: handing the owner back could override the next stage's claim.
        if (tearingDown) return
        OnboardingPool.setOwner(currentKey)
        ownedKey = currentKey
        slot?.setFrozen(false)
    }

    /**
     * DashDrama LanguageFirstFragment.onResume: refresh the current key without touching the owner,
     * then claim it POLITE. A Language1 the splash already filled is not refreshed on the first
     * resume (DashDrama's LFO1 is still loading there). An open dialog keeps the owner. Once the
     * exit is latched nothing is requested: a refresh would close the gate on the next stage's preload.
     */
    override fun onResume() {
        super.onResume()
        val first = !resumedOnce
        resumedOnce = true
        if (exitLatched) return
        if (dialog?.isShowing == true) {
            OnboardingPool.refresh(AdPlacement.LanguageConfirm)
            OnboardingPool.claim(AdPlacement.LanguageConfirm, LoadMode.POLITE)
            return
        }
        val adoptedUnshown = currentKey == AdPlacement.Language1 &&
            OnboardingPool.engineOrNull()?.isAdoptedUnshown(AdPlacement.Language1.key) == true
        if (!(first && adoptedUnshown)) OnboardingPool.refresh(currentKey)
        claimCurrent()
    }

    private fun onDone() {
        if (exitLatched) return
        val code = selectedCode ?: return
        val host = host ?: return
        exitLatched = true
        revealJob?.cancel()
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(screenIndex(currentKey), code))
        OnboardingSdk.track(AnalyticsEvent.LanguageFlowCompleted(code))
        // After the ad the host saves the pick, warms the next stage's first slot and moves on.
        host.exitWithInterstitial(OnboardingSdk.flags().reuseSplashInter, PoolExit.Language(code))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED, selectedCode)
        outState.putInt(KEY_TAPS, tapCount)
        outState.putBoolean(KEY_ON_LANGUAGE2, currentKey == AdPlacement.Language2)
        outState.putBoolean(KEY_CONFIRM_REVEALED, confirmRevealed)
        outState.putBoolean(KEY_HANDOFF, handoff)
    }

    override fun onDestroyView() {
        tearingDown = true
        revealJob?.cancel()
        revealJob = null
        // DashDrama clears only its own key: the next stage may already own the pool.
        dialog?.let {
            it.dismiss()
            OnboardingPool.clearOwnerIf(AdPlacement.LanguageConfirm)
        }
        dialog = null
        ownedKey?.let(OnboardingPool::clearOwnerIf)
        slot = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun screenIndex(placement: AdPlacement): Int = if (placement == AdPlacement.Language2) 2 else 1

    /** The template this screen's slot renders, so a funnel can slice by it. */
    private fun variant(placement: AdPlacement): String = NativeTemplates.variantFor(placement)

    companion object {
        private const val ARG_RESTORED_LANGUAGE = "ob_pool_restored_language"
        private const val KEY_SELECTED = "ob_pool_selected_language"
        private const val KEY_TAPS = "ob_pool_language_tap_count"
        private const val KEY_ON_LANGUAGE2 = "ob_pool_language2"
        private const val KEY_CONFIRM_REVEALED = "ob_pool_confirm_revealed"
        private const val KEY_HANDOFF = "ob_pool_lfo1_preload_handoff"

        fun newInstance(restoredLanguage: String?): PoolLanguageFragment =
            PoolLanguageFragment().apply { arguments = bundleOf(ARG_RESTORED_LANGUAGE to restoredLanguage) }
    }
}
