package io.onboardkit.core.analytics

import io.onboardkit.core.StepId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Analytics event catalog. Events carry the step id — never the pager index — so disabling
 * a step can not shift another step onto the wrong event name.
 */
sealed class AnalyticsEvent(val name: String, val params: Map<String, Any> = emptyMap()) {

    class SplashViewed(engagementTimeMs: Long) :
        AnalyticsEvent("ob_splash_view", mapOf(PARAM_ENGAGEMENT to engagementTimeMs))

    class LanguageViewed(screenIndex: Int, adTier: Int) :
        AnalyticsEvent("ob_lfo${screenIndex}_view", mapOf("ad_tier" to adTier))

    class LanguageCompleted(screenIndex: Int, code: String) :
        AnalyticsEvent("ob_complete_lfo$screenIndex", mapOf("language" to code))

    class LanguageFlowCompleted(code: String) :
        AnalyticsEvent("ob_complete_lfo_flow", mapOf("language" to code))

    class StepViewed(stepId: StepId, index: Int) :
        AnalyticsEvent("ob_step_viewed", mapOf("step_id" to stepId.value, "index" to index))

    class StepCompleted(stepId: StepId, dwellMs: Long) :
        AnalyticsEvent(
            "ob_step_completed",
            mapOf("step_id" to stepId.value, PARAM_ENGAGEMENT to dwellMs),
        )

    class QuestionViewed(source: String) :
        AnalyticsEvent("ob_question_view", mapOf("source" to source))

    class QuestionOptionSelected(optionId: String, selected: Boolean) :
        AnalyticsEvent(
            "ob_question_option",
            mapOf("option_id" to optionId, "selected" to selected),
        )

    class QuestionCompleted(count: Int) :
        AnalyticsEvent("ob_question_completed", mapOf("answer_count" to count))

    class FlowCompleted(stepsShown: Int) :
        AnalyticsEvent("ob_complete_flow", mapOf("steps_shown" to stepsShown))

    class AdImpression(placementName: String) :
        AnalyticsEvent("ob_ad_impression", mapOf("placement" to placementName))

    private companion object {
        const val PARAM_ENGAGEMENT = "engagement_time"
    }
}

fun interface AnalyticsPlugin {
    fun execute(event: AnalyticsEvent)
}

/** Fan-out mediator. Plugin list is copy-on-write so a plugin may remove itself mid-dispatch. */
internal object AnalyticsHub {
    private val plugins = CopyOnWriteArrayList<AnalyticsPlugin>()

    fun addPlugin(plugin: AnalyticsPlugin) {
        plugins.addIfAbsent(plugin)
    }

    fun removePlugin(plugin: AnalyticsPlugin) {
        plugins.remove(plugin)
    }

    fun track(event: AnalyticsEvent) {
        plugins.forEach { runCatching { it.execute(event) } }
    }

    fun clear() = plugins.clear()
}
