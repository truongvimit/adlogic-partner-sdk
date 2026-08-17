package io.onboardkit.ui.language

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.AdSkipReason
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.showNativeAd
import io.onboardkit.ads.trackSkipped
import io.onboardkit.config.ObLanguage
import io.onboardkit.config.ObLanguages
import io.onboardkit.core.ObLog
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityLanguageBinding
import io.onboardkit.flow.ExitDecision
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.ui.base.BaseOnboardActivity
import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import io.onboardkit.ui.question.ObQuestionActivity
import io.onboardkit.ui.question.QuestionSource
import kotlinx.coroutines.launch

/**
 * One class for every language screen: the first-open LFO and a SETTINGS mode with no ads and
 * a real back button.
 *
 * The LFO carries two native slots on a single Activity (no duplicated screen, no re-inflate):
 * slot 1 is preloaded from Splash and shown on entry, slot 2 is preloaded as soon as the LFO
 * appears and swapped in on the first language tap — a second impression without the user ever
 * leaving the screen, so selection and scroll position are naturally preserved.
 */
class ObLanguageActivity : BaseOnboardActivity() {

    override val screenName: String = "ob_language"

    private lateinit var binding: ObActivityLanguageBinding
    private lateinit var adapter: LanguageAdapter

    private var mode = LanguageScreenMode.FIRST_OPEN
    private var selectedCode: String? = null
    private var languages: List<ObLanguage> = emptyList()

    /** True once the first tap swapped slot 1 out for slot 2. */
    private var secondAdShown = false

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityLanguageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { runCatching { LanguageScreenMode.valueOf(it) }.getOrNull() }
            ?: LanguageScreenMode.FIRST_OPEN
        selectedCode = sdk.configOrNull()?.language?.defaultCode

        languages = resolveLanguages()

        adapter = LanguageAdapter(::onLanguageTapped)
        adapter.selectedCode = selectedCode
        adapter.hintCode = resolveHintCode()
        binding.obLanguageList.layoutManager = LinearLayoutManager(this)
        binding.obLanguageList.adapter = adapter
        adapter.submitList(languages)

        bindConfirmVisibility()
        binding.obLanguageConfirm.setOnClickListener { onConfirm() }

        if (mode == LanguageScreenMode.SETTINGS) {
            binding.obAdBlock.visibility = View.GONE
            binding.obAdBlock2.visibility = View.GONE
        } else {
            setupNativeAd(AdPlacement.Language1)
            // Preloads the slot-2 native (and the first content step) while the user reads LFO
            sdk.preload().onLanguageShown(this)
        }

        // SETTINGS is a re-entry, not a first open — counting it would inflate the LFO funnel
        if (mode == LanguageScreenMode.FIRST_OPEN) {
            OnboardingSdk.track(AnalyticsEvent.LanguageViewed(1, variant = adVariant()))
        }
    }

    /** The remote template bucket this screen's native was built with. */
    private fun adVariant(): String = sdk.flags().templateLanguage

    /**
     * Row that gets the animated tap hint, or null for no hint at all.
     *
     * Three ways to end up with no hint: the partner switched it off at build time
     * (`language.tapHintEnabled`), remote switched it off (`ob_show_language_tap_hint`), or there
     * is already a selection — a preselected `defaultCode`, or the SETTINGS screen, where the user
     * came to change a language they have already chosen once.
     */
    private fun resolveHintCode(): String? {
        if (selectedCode != null) return null
        if (!sdk.requireConfig().language.tapHintEnabled) return null
        if (!sdk.flags().showLanguageTapHint) return null
        return DeviceLanguageHint.resolve(languages)
    }

    /**
     * Confirm button state for the current selection.
     *
     * With a selection the button is always solid — whatever the flags say, the screen keeps a way
     * out. Before the first tap it is either dimmed (default) or hidden, per
     * `language.confirmVisibleBeforeSelect` AND `ob_show_language_confirm_before_select`.
     */
    private fun bindConfirmVisibility() {
        val selected = selectedCode != null
        val showBeforeSelect = sdk.requireConfig().language.confirmVisibleBeforeSelect &&
            sdk.flags().showLanguageConfirmBeforeSelect
        binding.obLanguageConfirm.isVisible = selected || showBeforeSelect
        binding.obLanguageConfirm.alpha = if (selected) 1f else 0.5f
    }

    /** Remote CSV filtered against the app's catalog; empty result falls back to the catalog. */
    private fun resolveLanguages(): List<ObLanguage> {
        val configured = sdk.requireConfig().language.languages
        val csv = sdk.flags().languageSupportedCodes
        if (csv.isBlank()) return configured
        val byCode = configured.associateBy { it.code }
        val filtered = csv.split(',').mapNotNull { byCode[it.trim()] }.distinctBy { it.code }
        return filtered.ifEmpty { configured.ifEmpty { ObLanguages.ALL } }
    }

    private fun onLanguageTapped(language: ObLanguage) {
        selectedCode = language.code
        adapter.selectedCode = language.code
        bindConfirmVisibility()
        OnboardingSdk.emitEvent(OnboardingEvent.LanguageSelected(language.code))

        if (mode != LanguageScreenMode.FIRST_OPEN) return

        // The tap itself. The audited SDK had no equivalent — it could not distinguish "picked a
        // language then hesitated" from "never engaged", because the only LFO signal was the exit.
        OnboardingSdk.track(
            AnalyticsEvent.LanguageSelected(if (secondAdShown) 2 else 1, language.code),
        )

        if (secondAdShown) return

        val config = sdk.requireConfig()
        if (!config.language.secondNativeOnSelectEnabled ||
            !sdk.flags().enableLanguageNative2
        ) {
            return
        }

        secondAdShown = true
        // Slot 1 is genuinely finished here, so it reports its own completion — the audited SDK's
        // `lfo1_complete`, which fired on exactly this transition (tap on LFO1 -> open LFO2).
        // Screen 2's completion comes from the Next button, with screen_index=2. Two events with
        // two different indexes is two screens, not a double count.
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(1, language.code))
        showSecondNativeSlot()
    }

    /** First tap: slot 1 goes away, slot 2 takes its place. Same screen, fresh impression. */
    private fun showSecondNativeSlot() {
        binding.obAdBlock.visibility = View.INVISIBLE
        binding.obAdBlock2.visibility = View.VISIBLE
        sdk.provider()?.releaseNative(AdPlacement.Language1)
        setupNativeAd(AdPlacement.Language2)
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(2, variant = adVariant()))
    }

    private fun adBlockFor(placement: AdPlacement): ViewGroup =
        if (placement == AdPlacement.Language2) binding.obAdBlock2 else binding.obAdBlock

    private fun containerFor(placement: AdPlacement): ViewGroup =
        if (placement == AdPlacement.Language2) {
            binding.obNativeContainer2
        } else {
            binding.obNativeContainer
        }

    private fun shimmerFor(placement: AdPlacement): View =
        if (placement == AdPlacement.Language2) {
            binding.obNativeShimmer2.root
        } else {
            binding.obNativeShimmer.root
        }

    private fun setupNativeAd(placement: AdPlacement) {
        showNativeAd(
            placement = placement,
            unit = sdk.requireConfig().ads.nativeUnitFor(placement),
            container = containerFor(placement),
            shimmer = shimmerFor(placement),
            onUnavailable = { adBlockFor(placement).visibility = View.GONE },
        )
    }

    private fun onConfirm() {
        val code = selectedCode ?: return
        OnboardingSdk.persistLanguage(code)

        if (mode == LanguageScreenMode.SETTINGS) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_LANGUAGE_CODE, code))
            finish()
            return
        }

        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(if (secondAdShown) 2 else 1, code))
        OnboardingSdk.track(AnalyticsEvent.LanguageFlowCompleted(code))
        // SDK scope, not lifecycleScope: navigation finishes this Activity right away.
        val store = sdk.stateStoreOrNull()
        if (store != null) sdk.scope().launch { store.markLfoCompleted() }
        leaveLanguage()
    }

    private fun leaveLanguage() {
        val config = sdk.requireConfig()
        val enabled = FlowNavigator.enabledSteps(config, sdk.flags(), sdk.guard().isPremium(this))
        ObLog.d(ObLog.Section.NAV, "ob_language enabledSteps=${enabled.map { it.value }}")
        if (enabled.isNotEmpty()) {
            reuseInterstitialThenLeave { ObOnboardingHostActivity.start(this, resumeIndex = 0) }
            return
        }
        when (FlowNavigator.decideExit(
            sdk.flags(),
            config,
            hasReusableSplashInterstitial = false,
            isOb5NativeReady = false,
        )) {
            ExitDecision.GoToQuestion ->
                reuseInterstitialThenLeave { ObQuestionActivity.start(this, QuestionSource.NEW_USER) }

            else -> endFlow()
        }
    }

    /**
     * A splash interstitial that was loaded but never shown is offered here instead of being
     * thrown away — that is the whole point of `ob_reuse_splash_inter`.
     */
    private fun reuseInterstitialThenLeave(startNext: () -> Unit) {
        if (!sdk.flags().reuseSplashInter) {
            ObLog.d(ObLog.Section.SHOW, "splash_inter reuse off (ob_reuse_splash_inter)")
            AdPlacement.SplashInterstitial.trackSkipped(AdSkipReason.PLACEMENT_OFF_BY_REMOTE)
            startNext()
            finish()
            return
        }
        showInterstitial(
            AdPlacement.SplashInterstitial,
            onNext = startNext,
            onFinished = { finish() },
        )
    }

    /**
     * Nothing starts underneath the ad here: whether the flow ends at a paywall or back in the
     * host app is only decided after it, so there is no destination to warm up.
     */
    private fun endFlow() {
        if (!sdk.flags().reuseSplashInter) {
            completeAndFinish()
            return
        }
        showInterstitial(AdPlacement.SplashInterstitial, onFinished = { completeAndFinish() })
    }

    private fun completeAndFinish() {
        lifecycleScope.launch {
            // Paywall outcome is intentionally ignored: the flow completes either way
            sdk.presentPaywall(this@ObLanguageActivity, PaywallPlacement.AFTER_ONBOARDING)
            OnboardingSdk.completeFlow(this@ObLanguageActivity)
            finish()
        }
    }

    override fun handleBack() {
        if (mode == LanguageScreenMode.SETTINGS) {
            finish()
        } else {
            finishAffinity()
        }
    }

    override fun onDestroy() {
        if (mode != LanguageScreenMode.SETTINGS) {
            sdk.provider()?.releaseNative(AdPlacement.Language1)
            sdk.provider()?.releaseNative(AdPlacement.Language2)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "ob_extra_mode"

        const val RESULT_LANGUAGE_CODE = "ob_result_language_code"

        fun start(context: Context, mode: LanguageScreenMode) {
            context.startActivity(intentFor(context, mode))
        }

        /** For ActivityResult launchers; SETTINGS mode returns [RESULT_LANGUAGE_CODE]. */
        fun intentFor(context: Context, mode: LanguageScreenMode): Intent =
            Intent(context, ObLanguageActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
}
