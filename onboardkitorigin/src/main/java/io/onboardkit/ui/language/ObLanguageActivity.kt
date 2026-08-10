package io.onboardkit.ui.language

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.ObLanguage
import io.onboardkit.config.ObLanguages
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityLanguageBinding
import io.onboardkit.flow.ExitDecision
import io.onboardkit.flow.FlowNavigator
import io.onboardkit.paywall.PaywallOutcome
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
        binding.obLanguageList.layoutManager = LinearLayoutManager(this)
        binding.obLanguageList.adapter = adapter
        adapter.submitList(languages)

        binding.obLanguageConfirm.alpha = if (selectedCode == null) 0.5f else 1f
        binding.obLanguageConfirm.setOnClickListener { onConfirm() }

        if (mode == LanguageScreenMode.SETTINGS) {
            binding.obAdBlock.visibility = View.GONE
            binding.obAdBlock2.visibility = View.GONE
        } else {
            setupNativeAd(AdPlacement.Language1)
            // Preloads the slot-2 native (and the first content step) while the user reads LFO
            sdk.preload().onLanguageShown(this)
        }

        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(1, adTier = 0))
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
        binding.obLanguageConfirm.alpha = 1f
        OnboardingSdk.emitEvent(OnboardingEvent.LanguageSelected(language.code))

        if (mode != LanguageScreenMode.FIRST_OPEN || secondAdShown) return

        val config = sdk.requireConfig()
        if (!config.language.secondNativeOnSelectEnabled ||
            !sdk.flags().enableLanguageNative2
        ) {
            return
        }

        secondAdShown = true
        OnboardingSdk.track(AnalyticsEvent.LanguageCompleted(1, language.code))
        showSecondNativeSlot()
    }

    /** First tap: slot 1 goes away, slot 2 takes its place. Same screen, fresh impression. */
    private fun showSecondNativeSlot() {
        binding.obAdBlock.visibility = View.INVISIBLE
        binding.obAdBlock2.visibility = View.VISIBLE
        sdk.provider()?.releaseNative(AdPlacement.Language1)
        setupNativeAd(AdPlacement.Language2)
        OnboardingSdk.track(AnalyticsEvent.LanguageViewed(2, adTier = 0))
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
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val unit = if (placement == AdPlacement.Language2) {
            config.ads.languageDupNative ?: config.ads.languageNative
        } else {
            config.ads.languageNative
        }
        if (provider == null || unit == null || !sdk.policy().canShowNative(this, placement)) {
            adBlockFor(placement).visibility = View.GONE
            return
        }
        val template = NativeTemplates.fromRemote(
            sdk.flags().templateLanguage,
            config.ads.languageTemplate,
        )
        val bound = provider.bindNative(
            this,
            placement,
            containerFor(placement),
            shimmerFor(placement),
            object : AdEventListener {
                override fun onLoaded() {
                    runOnUiThread { bindIfPossible(placement) }
                }

                override fun onFailedToLoad() {
                    runOnUiThread { shimmerFor(placement).visibility = View.GONE }
                }
            },
        )
        if (!bound) {
            provider.preloadNative(
                this,
                NativeAdRequest(placement, unit, NativeTemplates.layoutFor(template)),
            )
        }
    }

    private fun bindIfPossible(placement: AdPlacement) {
        if (isFinishing || isDestroyed) return
        // A late slot-1 callback must not repaint the block the user already moved past
        if (secondAdShown && placement == AdPlacement.Language1) return
        sdk.provider()?.bindNative(
            this,
            placement,
            containerFor(placement),
            shimmerFor(placement),
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
        showReusedInterThen { goNextFromLanguage() }
    }

    /** Splash interstitial not yet shown may be reused at LFO's Next, per remote flag. */
    private fun showReusedInterThen(next: () -> Unit) {
        val provider = sdk.provider()
        if (provider != null && sdk.flags().reuseSplashInter &&
            provider.isInterstitialReady(AdPlacement.SplashInterstitial)
        ) {
            provider.showInterstitial(this, AdPlacement.SplashInterstitial) { next() }
        } else {
            next()
        }
    }

    private fun goNextFromLanguage() {
        val config = sdk.requireConfig()
        val enabled = FlowNavigator.enabledSteps(config, sdk.flags())
        if (enabled.isNotEmpty()) {
            ObOnboardingHostActivity.start(this, resumeIndex = 0)
            finish()
            return
        }
        when (FlowNavigator.decideExit(sdk.flags(), config, false, false)) {
            ExitDecision.GoToQuestion -> {
                ObQuestionActivity.start(this, QuestionSource.NEW_USER)
                finish()
            }

            else -> lifecycleScope.launch {
                presentPaywallIfAny()
                OnboardingSdk.completeFlow(this@ObLanguageActivity)
                finish()
            }
        }
    }

    private suspend fun presentPaywallIfAny() {
        val gate = sdk.paywall() ?: return
        if (gate.shouldShow(PaywallPlacement.AFTER_ONBOARDING)) {
            when (gate.present(this, PaywallPlacement.AFTER_ONBOARDING)) {
                PaywallOutcome.Purchased,
                PaywallOutcome.Dismissed,
                PaywallOutcome.ContinueWithAds,
                -> Unit
            }
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
