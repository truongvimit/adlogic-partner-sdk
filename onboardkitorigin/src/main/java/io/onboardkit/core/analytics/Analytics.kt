package io.onboardkit.core.analytics

import io.onboardkit.core.StepId
import io.trackkit.AdFormat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Analytics event catalog. Event identity is the step id — never the pager index — so disabling
 * a step can not shift another step onto the wrong event name; the index rides along as a param.
 *
 * Constructor arguments are exposed as properties so a plugin can read them typed instead of
 * digging strings back out of [params]; [TrackkitPlugin] relies on that to translate this
 * catalog into the canonical Trackkit taxonomy.
 */
sealed class AnalyticsEvent(val name: String, val params: Map<String, Any> = emptyMap()) {

    /**
     * The flow was entered, before it has decided whether to run or skip. Every later rate divides
     * by this, so it is emitted even on the skip path.
     */
    class FlowStarted : AnalyticsEvent("ob_flow_start")

    /** Splash became visible. Pairs with [SplashCompleted] to expose splash drop-off. */
    class SplashViewed : AnalyticsEvent("ob_splash_view")

    /** Splash pipeline resolved and navigation happened. */
    class SplashCompleted(val dwellMs: Long) :
        AnalyticsEvent("ob_splash_complete", mapOf(PARAM_ENGAGEMENT to dwellMs))

    /** [variant] is the remote native-template bucket the screen was built with. */
    class LanguageViewed(val screenIndex: Int, val variant: String? = null) :
        AnalyticsEvent("ob_lfo${screenIndex}_view", paramsOf(PARAM_VARIANT to variant))

    /**
     * A language row was tapped, before any commitment. The audited SDK had no equivalent — its
     * only LFO signal was the exit, so a user who picked a language and then stalled looked the
     * same as one who never touched the list.
     */
    class LanguageSelected(val screenIndex: Int, val code: String) :
        AnalyticsEvent("ob_lfo${screenIndex}_select", mapOf("language" to code))

    /**
     * Language screen [screenIndex] is done. Fires once per screen shown, so a flow with the
     * second native slot enabled legitimately emits it twice with two different indexes — that is
     * two screens, matching the audited SDK's `lfo1_complete` + `lfo2_complete`, not a double count.
     */
    class LanguageCompleted(val screenIndex: Int, val code: String) :
        AnalyticsEvent("ob_complete_lfo$screenIndex", mapOf("language" to code))

    class LanguageFlowCompleted(val code: String) :
        AnalyticsEvent("ob_complete_lfo_flow", mapOf("language" to code))

    /** [variant] is the page's template bucket — the same key the pager hot-swap compares. */
    class StepViewed(val stepId: StepId, val index: Int, val variant: String? = null) :
        AnalyticsEvent(
            "ob_step_viewed",
            paramsOf("step_id" to stepId.value, "index" to index, PARAM_VARIANT to variant),
        )

    /** [exitReason] is how the step was left: the CTA, a skip, auto-next, or an unfilled ad. */
    class StepCompleted(
        val stepId: StepId,
        val index: Int,
        val dwellMs: Long,
        val exitReason: String? = null,
    ) : AnalyticsEvent(
        "ob_step_completed",
        paramsOf(
            "step_id" to stepId.value,
            "index" to index,
            PARAM_ENGAGEMENT to dwellMs,
            PARAM_EXIT_REASON to exitReason,
        ),
    )

    class QuestionViewed(val source: String) :
        AnalyticsEvent("ob_question_view", mapOf("source" to source))

    class QuestionOptionSelected(val optionId: String, val selected: Boolean) :
        AnalyticsEvent(
            "ob_question_option",
            mapOf("option_id" to optionId, "selected" to selected),
        )

    class QuestionCompleted(val count: Int) :
        AnalyticsEvent("ob_question_completed", mapOf("answer_count" to count))

    class FlowCompleted(val stepsShown: Int, val dwellMs: Long? = null) :
        AnalyticsEvent(
            "ob_complete_flow",
            paramsOf("steps_shown" to stepsShown, PARAM_ENGAGEMENT to dwellMs),
        )

    // ── Ad slots this SDK paints itself ──
    //
    // Revenue and clicks come from the vendor's own paid-event callback inside :ads. What only this
    // layer knows is the *opportunity*: which screen asked for an ad, and whether it got one. That
    // is the difference between "we earn nothing on OB3" and "we never even request there".

    /** A load was requested for [placementName]. */
    class AdRequested(val placementName: String, val format: AdFormat) :
        AnalyticsEvent("ob_ad_request", mapOf("placement" to placementName))

    /** The ad was actually painted. */
    class AdImpression(
        val placementName: String,
        val format: AdFormat = AdFormat.NATIVE_FULL_SCREEN,
    ) : AnalyticsEvent("ob_ad_impression", mapOf("placement" to placementName))

    /** Every configured tier failed to fill. */
    class AdFailed(val placementName: String, val format: AdFormat) :
        AnalyticsEvent("ob_ad_failed", mapOf("placement" to placementName))

    /**
     * A slot policy declined before any request went out — premium user, remote flag off, or no ad
     * unit configured. Without it an unfilled slot and a disabled slot look identical downstream.
     */
    class AdSkipped(val placementName: String, val format: AdFormat, val reason: String) :
        AnalyticsEvent(
            "ob_ad_skipped",
            mapOf("placement" to placementName, "reason" to reason),
        )

    // ── Paywall checkpoints owned by the flow ──
    //
    // The purchase itself is reported by :ads from the billing callback; these two only cover the
    // sheet the flow put in front of the user, so the two never double-count.

    class PaywallViewed(val placementName: String) :
        AnalyticsEvent("ob_paywall_view", mapOf("placement" to placementName))

    class PaywallResolved(val placementName: String, val outcome: String) :
        AnalyticsEvent(
            "ob_paywall_result",
            mapOf("placement" to placementName, "outcome" to outcome),
        )

    internal companion object {
        const val PARAM_ENGAGEMENT = "engagement_time"
        const val PARAM_VARIANT = "variant"
        const val PARAM_EXIT_REASON = "exit_reason"

        /** Values of [PaywallResolved.outcome]. */
        const val PAYWALL_PURCHASED = "purchased"
        const val PAYWALL_DISMISSED = "dismissed"
        const val PAYWALL_CONTINUE_WITH_ADS = "continue_with_ads"
    }
}

/** Values of [AnalyticsEvent.StepCompleted.exitReason] — how the user left a step. */
internal object StepExit {
    const val CTA = "cta"
    const val SKIP = "skip"
    const val AUTO_NEXT = "auto_next"
    const val AD_FAILED = "ad_failed"

    /** OB5's hard timeout closed the screen with no interaction at all. */
    const val AUTO_DISMISS = "auto_dismiss"
}

/** Values of [AnalyticsEvent.AdSkipped.reason] — why no request was made. */
internal object AdSkipReason {
    /** Premium user, or a remote flag turned this slot off. */
    const val POLICY = "policy"

    /** No ad unit configured for the slot, or no provider installed. */
    const val NO_UNIT = "no_ad_unit"

    /** Allowed and requested, but nothing was buffered by the time the slot needed it. */
    const val NOT_READY = "not_ready"
}

/** Drops null entries so an absent variant never lands in a bundle as the string "null". */
private fun paramsOf(vararg pairs: Pair<String, Any?>): Map<String, Any> =
    pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

fun interface AnalyticsPlugin {
    fun execute(event: AnalyticsEvent)
}

/** Fan-out mediator. Plugin list is copy-on-write so a plugin may remove itself mid-dispatch. */
internal object AnalyticsHub {
    private val plugins = CopyOnWriteArrayList<AnalyticsPlugin>()

    fun addPlugin(plugin: AnalyticsPlugin) {
        plugins.addIfAbsent(plugin)
    }

    fun track(event: AnalyticsEvent) {
        plugins.forEach { runCatching { it.execute(event) } }
    }

    fun clear() = plugins.clear()
}
