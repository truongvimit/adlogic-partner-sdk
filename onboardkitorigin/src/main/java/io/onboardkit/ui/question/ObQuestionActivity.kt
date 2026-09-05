package io.onboardkit.ui.question

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.onboardkit.OnboardingSdk
import io.onboardkit.R
import io.onboardkit.ads.AdPlacement
import io.onboardkit.ads.showInterstitial
import io.onboardkit.ads.showNativeAd
import io.onboardkit.config.QuestionConfig
import io.onboardkit.config.SelectionMode
import io.onboardkit.core.ObLog
import io.onboardkit.core.QuestionAnswer
import io.onboardkit.core.StepId
import io.onboardkit.core.analytics.AnalyticsEvent
import io.onboardkit.core.events.OnboardingEvent
import io.onboardkit.databinding.ObActivityQuestionBinding
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

    override val screenName: String = "ob_question"

    private lateinit var binding: ObActivityQuestionBinding
    private lateinit var adapter: QuestionAdapter
    private var source = QuestionSource.NEW_USER
    private var activeQuestion: QuestionConfig? = null
    private val selectedIds = linkedSetOf<String>()
    private var lastAdRefreshMs = 0L
    private var nativeLoadPending = false
    private var pendingReplacement: FrameLayout? = null

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

        // Warm/join before consuming the initial buffer. Doing this after bind started another
        // native load whose listener could replace the ad without any selection, even when off.
        sdk.preload().preloadQuestion(this)
        setupNativeAd()
    }

    private fun onOptionToggled(optionId: String, selected: Boolean) {
        val question = activeQuestion ?: return
        if (question.selectionMode == SelectionMode.SINGLE) {
            selectedIds.clear()
            if (selected) selectedIds.add(optionId)
        } else {
            if (selected) selectedIds.add(optionId) else selectedIds.remove(optionId)
        }
        // Always mirrored back, both modes: the adapter renders ticks purely from this set, so a
        // multi-select deselect must be published too or the row would stay ticked.
        adapter.selectedIds = selectedIds.toSet()
        OnboardingSdk.track(AnalyticsEvent.QuestionOptionSelected(optionId, selected))

        bindCtaVisibility()

        if (selected && question.refreshAdOnSelect) refreshAdThrottled()
    }

    /**
     * Visible once the selection reaches [QuestionConfig.minSelection].
     *
     * Read from the live selection rather than from the toggle that triggered it: the adapter used
     * to mirror its own per-row flag, so deselecting one of several answers reported the wrong set.
     */
    private fun bindCtaVisibility() {
        val required = activeQuestion?.minSelection ?: 1
        binding.obQuestionCta.visibility =
            if (selectedIds.size >= required) View.VISIBLE else View.INVISIBLE
    }

    /** Keep the 2s attempt throttle; each successful bind also restarts the visible ad's cooldown. */
    private fun refreshAdThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (nativeLoadPending || now - lastAdRefreshMs < AD_REFRESH_THROTTLE_MS) return
        lastAdRefreshMs = now
        nativeLoadPending = true
        // The helper may show its shimmer, so hide its parent while the current native remains.
        val staging = FrameLayout(this).apply { visibility = View.GONE }
        val replacement = FrameLayout(this)
        staging.addView(replacement, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        binding.obAdBlock.addView(staging, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        pendingReplacement = staging
        showNativeAd(
            placement = AdPlacement.QuestionNative,
            unit = sdk.requireConfig().ads.questionNative,
            container = replacement,
            onBound = {
                if (pendingReplacement === staging) {
                    pendingReplacement = null
                    nativeLoadPending = false
                    lastAdRefreshMs = SystemClock.elapsedRealtime()
                    staging.removeView(replacement)
                    binding.obNativeContainer.removeAllViews()
                    binding.obNativeContainer.addView(replacement)
                    binding.obAdBlock.removeView(staging)
                    binding.obNativeContainer.visibility = View.VISIBLE
                    binding.obAdBlock.visibility = View.VISIBLE
                    // The provider already retired the previous native when this bind succeeded.
                }
            },
            onUnavailable = {
                if (pendingReplacement === staging) {
                    pendingReplacement = null
                    nativeLoadPending = false
                    binding.obAdBlock.removeView(staging)
                }
            },
        )
    }

    private fun setupNativeAd() {
        nativeLoadPending = true
        lastAdRefreshMs = SystemClock.elapsedRealtime()
        showNativeAd(
            placement = AdPlacement.QuestionNative,
            unit = sdk.requireConfig().ads.questionNative,
            container = binding.obNativeContainer,
            onBound = {
                nativeLoadPending = false
                lastAdRefreshMs = SystemClock.elapsedRealtime()
            },
            onUnavailable = {
                nativeLoadPending = false
                binding.obAdBlock.visibility = View.GONE
            },
        )
    }

    private fun onCtaClicked() {
        val question = activeQuestion ?: return
        val answers = question.options
            .filter { it.id in selectedIds }
            .map { QuestionAnswer(it.id, it.title?.toString() ?: it.id) }
        OnboardingSdk.persistAnswers(answers)
        OnboardingSdk.emitEvent(OnboardingEvent.QuestionAnswered(answers))
        OnboardingSdk.track(AnalyticsEvent.QuestionCompleted(answers.size))

        // No onNext: whether the flow ends at a paywall or in the host app is only decided after
        // the ad, so there is no destination to start underneath it.
        showInterstitial(AdPlacement.QuestionInterstitial, onFinished = { forwardToNext() })
    }

    private fun forwardWithoutShowing() {
        forwardToNext()
    }

    private fun forwardToNext() {
        ObLog.d(ObLog.Section.NAV, "from=ob_question source=$source")
        val placement = when (source) {
            QuestionSource.NEW_USER -> PaywallPlacement.AFTER_ONBOARDING
            QuestionSource.OLD_USER -> PaywallPlacement.AFTER_QUESTION_OLD_USER
        }
        lifecycleScope.launch {
            // Outcome ignored: the flow completes either way
            sdk.presentPaywall(this@ObQuestionActivity, placement)
            OnboardingSdk.completeFlow(this@ObQuestionActivity)
            finish()
        }
    }

    override fun onDestroy() {
        pendingReplacement = null
        nativeLoadPending = false
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
