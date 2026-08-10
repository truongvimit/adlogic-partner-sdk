package io.onboardkit.ui.question

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdEventListener
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.NativeAdRequest
import io.onboardkit.ads.NativeTemplates
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.SelectionMode
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityQuestionBinding
import io.onboardkit.paywall.PaywallOutcome
import io.onboardkit.paywall.PaywallPlacement
import io.onboardkit.remote.uiconfig.RemoteQuestionParser
import io.onboardkit.ui.base.BaseOnboardActivity
import kotlinx.coroutines.launch

enum class QuestionSource { NEW_USER, OLD_USER }

/**
 * Survey screen. Unlike the original — which stored nothing and logged nothing — answers are
 * persisted to DataStore, exposed via events/analytics, and the native refresh-on-tap tactic
 * is opt-in with throttling.
 */
class ObQuestionActivity : BaseOnboardActivity() {

    private lateinit var binding: ObActivityQuestionBinding
    private lateinit var adapter: QuestionAdapter
    private var source = QuestionSource.NEW_USER
    private var activeQuestion: QuestionConfig? = null
    private val selectedIds = linkedSetOf<String>()
    private var lastAdRefreshMs = 0L

    /** Remote JSON fully replaces the option list when valid; otherwise compile-time config. */
    private fun resolveQuestion(): QuestionConfig? {
        val compiled = sdk.requireConfig().question
        val remote = RemoteQuestionParser.parse(sdk.flags().questionConfigJson) ?: return compiled
        val base = compiled ?: QuestionConfig()
        return base.copy(
            title = remote.title ?: base.title,
            options = remote.options,
        )
    }

    override fun onCreateSafe(savedInstanceState: Bundle?) {
        binding = ObActivityQuestionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        source = intent.getStringExtra(EXTRA_SOURCE)
            ?.let { runCatching { QuestionSource.valueOf(it) }.getOrNull() }
            ?: QuestionSource.NEW_USER

        val question = resolveQuestion()
        if (question == null || question.options.isEmpty()) {
            forwardWithoutShowing()
            return
        }
        activeQuestion = question

        OnboardingSdk.session.recordStepShown(StepId.QUESTION)
        OnboardingSdk.track(AnalyticsEvent.QuestionViewed(source.name.lowercase()))

        binding.obQuestionTitle.text = question.title
            ?: question.titleRes.takeIf { it != 0 }?.let(::getString)
            ?: getString(R.string.ob_question_title_default)
        if (question.ctaTextRes != 0) binding.obQuestionCta.setText(question.ctaTextRes)

        adapter = QuestionAdapter(question) { option, selected -> onOptionToggled(option.id, selected) }
        binding.obQuestionList.layoutManager = GridLayoutManager(this, GRID_SPAN)
        binding.obQuestionList.adapter = adapter

        binding.obQuestionCta.setOnClickListener { onCtaClicked() }

        setupNativeAd()
        sdk.preload().preloadQuestion(this)
    }

    private fun onOptionToggled(optionId: String, selected: Boolean) {
        val question = activeQuestion ?: return
        if (question.selectionMode == SelectionMode.SINGLE) {
            selectedIds.clear()
            if (selected) selectedIds.add(optionId)
            adapter.selectedIds = selectedIds.toSet()
        } else {
            if (selected) selectedIds.add(optionId) else selectedIds.remove(optionId)
        }
        OnboardingSdk.track(AnalyticsEvent.QuestionOptionSelected(optionId, selected))

        val enough = selectedIds.size >= question.minSelection
        binding.obQuestionCta.visibility = if (enough) View.VISIBLE else View.INVISIBLE

        if (question.refreshAdOnSelect) refreshAdThrottled()
    }

    /** At most one native refresh per 2s regardless of tap rate. */
    private fun refreshAdThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastAdRefreshMs < AD_REFRESH_THROTTLE_MS) return
        lastAdRefreshMs = now
        sdk.provider()?.releaseNative(AdPlacement.QuestionNative)
        setupNativeAd()
    }

    private fun setupNativeAd() {
        val config = sdk.requireConfig()
        val provider = sdk.provider()
        val unit = config.ads.questionNative
        if (provider == null || unit == null ||
            !sdk.policy().canShowNative(this, AdPlacement.QuestionNative)
        ) {
            binding.obAdBlock.visibility = View.GONE
            return
        }
        val template = NativeTemplates.fromRemote(
            sdk.flags().templateQuestion,
            config.ads.questionTemplate,
        )
        val bound = provider.bindNative(
            this,
            AdPlacement.QuestionNative,
            binding.obNativeContainer,
            binding.obNativeShimmer.root,
            object : AdEventListener {
                override fun onLoaded() {
                    runOnUiThread {
                        if (!isFinishing) {
                            sdk.provider()?.bindNative(
                                this@ObQuestionActivity,
                                AdPlacement.QuestionNative,
                                binding.obNativeContainer,
                                binding.obNativeShimmer.root,
                            )
                        }
                    }
                }

                override fun onFailedToLoad() {
                    runOnUiThread {
                        binding.obNativeShimmer.root.visibility = View.GONE
                    }
                }
            },
        )
        if (!bound) {
            provider.preloadNative(
                this,
                NativeAdRequest(
                    AdPlacement.QuestionNative,
                    unit,
                    NativeTemplates.layoutFor(template),
                ),
            )
        }
    }

    private fun onCtaClicked() {
        val question = activeQuestion ?: return
        val answers = question.options
            .filter { it.id in selectedIds }
            .map { QuestionAnswer(it.id, it.title?.toString() ?: it.id) }
        OnboardingSdk.persistAnswers(answers)
        OnboardingSdk.emitEvent(OnboardingEvent.QuestionAnswered(answers))
        OnboardingSdk.track(AnalyticsEvent.QuestionCompleted(answers.size))

        val provider = sdk.provider()
        if (provider != null &&
            sdk.policy().canShowInterstitial(this, AdPlacement.QuestionInterstitial) &&
            provider.isInterstitialReady(AdPlacement.QuestionInterstitial)
        ) {
            provider.showInterstitial(this, AdPlacement.QuestionInterstitial) { forwardToNext() }
        } else {
            forwardToNext()
        }
    }

    private fun forwardWithoutShowing() {
        forwardToNext()
    }

    private fun forwardToNext() {
        val placement = when (source) {
            QuestionSource.NEW_USER -> PaywallPlacement.AFTER_ONBOARDING
            QuestionSource.OLD_USER -> PaywallPlacement.AFTER_QUESTION_OLD_USER
        }
        lifecycleScope.launch {
            val gate = sdk.paywall()
            if (gate != null && gate.shouldShow(placement)) {
                when (gate.present(this@ObQuestionActivity, placement)) {
                    PaywallOutcome.Purchased,
                    PaywallOutcome.Dismissed,
                    PaywallOutcome.ContinueWithAds,
                    -> Unit
                }
            }
            OnboardingSdk.completeFlow(this@ObQuestionActivity)
            finish()
        }
    }

    override fun onDestroy() {
        sdk.provider()?.releaseNative(AdPlacement.QuestionNative)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "ob_extra_question_source"
        private const val GRID_SPAN = 2
        private const val AD_REFRESH_THROTTLE_MS = 2_000L

        fun start(activity: Activity, source: QuestionSource) {
            activity.startActivity(
                Intent(activity, ObQuestionActivity::class.java)
                    .putExtra(EXTRA_SOURCE, source.name),
            )
        }
    }
}
