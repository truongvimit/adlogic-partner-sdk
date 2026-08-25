package io.trackkit

/**
 * The event catalog — the single source of truth for the taxonomy.
 *
 * Naming rule, enforced by [io.trackkit.internal.EventValidator] and by `TaxonomyTest`:
 * `<domain>_<object>_<action>`, `snake_case`, ASCII, at most 40 characters.
 *
 * Domains: `ad_`, `fo_` (first open), `iap_`, `consent_`, `app_`.
 *
 * Two rules learned from auditing the previous generation of this pipeline:
 *
 *  1. **Never encode a variable in the event name.** The old SDK emitted `ob1_complete`,
 *     `ob2_complete`, … *and* a second parallel family `complete_ob1`, `complete_ob2`, … which
 *     double-counted every transition on two different clocks. Here there is one
 *     [Fo.StepComplete] carrying a `step` param.
 *  2. **Every ad event carries its placement.** AdMob's paid-event callback only knows the ad unit,
 *     so without a placement the revenue data cannot be attributed to a screen.
 */
object TrackkitEvents {

    // -----------------------------------------------------------------------
    // Param keys
    // -----------------------------------------------------------------------

    const val PARAM_PLACEMENT = "placement"
    const val PARAM_AD_FORMAT = "ad_format"
    const val PARAM_AD_UNIT_ID = "ad_unit_id"
    const val PARAM_AD_PLATFORM = "ad_platform"
    const val PARAM_AD_NETWORK = "ad_network"
    const val PARAM_VALUE = "value"
    const val PARAM_CURRENCY = "currency"
    const val PARAM_PRECISION = "precision"
    const val PARAM_LATENCY_MS = "latency_ms"
    const val PARAM_ERROR_CODE = "error_code"
    const val PARAM_REASON = "reason"
    const val PARAM_STEP = "step"
    const val PARAM_INDEX = "index"
    const val PARAM_VARIANT = "variant"
    const val PARAM_DWELL_MS = "dwell_ms"
    const val PARAM_LANGUAGE = "language"
    const val PARAM_SOURCE = "source"
    const val PARAM_PRODUCT_ID = "product_id"
    const val PARAM_SCREEN_INDEX = "screen_index"
    const val PARAM_STATUS = "status"
    const val PARAM_COUNT = "count"

    /**
     * Steps shown in a completed flow. Deliberately not [PARAM_COUNT]: that key already means
     * "answers picked" on `fo_question_complete`, and one GA4 custom dimension that means two
     * different things makes any cross-event aggregation of it meaningless.
     */
    const val PARAM_STEPS_SHOWN = "steps_shown"
    const val PARAM_EXIT_REASON = "exit_reason"

    /** Parsed out of the Play install referrer; deliberately not the GA4-owned `source`/`campaign`. */
    const val PARAM_REFERRER_SOURCE = "referrer_source"
    const val PARAM_REFERRER_MEDIUM = "referrer_medium"
    const val PARAM_REFERRER_CAMPAIGN = "referrer_campaign"

    // -----------------------------------------------------------------------
    // Event names (referenced by sinks and by the Adjust token map)
    // -----------------------------------------------------------------------

    const val AD_REQUEST = "ad_request"
    const val AD_LOADED = "ad_loaded"
    const val AD_LOAD_FAILED = "ad_load_failed"
    const val AD_SHOW = "ad_show"
    const val AD_SHOW_FAILED = "ad_show_failed"
    const val AD_CLICK = "ad_click"
    const val AD_CLOSED = "ad_closed"
    const val AD_IMPRESSION = "ad_impression"
    const val AD_REWARD_EARNED = "ad_reward_earned"
    const val AD_SKIPPED = "ad_skipped"

    /** Cumulative revenue events emitted by the accumulator, not by a catalog class. */
    const val AD_REVENUE_TOTAL = "ad_revenue_total"
    const val AD_REVENUE_MICRO_FLUSH = "ad_revenue_micro_flush"
    const val AD_REVENUE_D3 = "ad_revenue_d3"
    const val AD_REVENUE_D7 = "ad_revenue_d7"

    const val FO_FLOW_START = "fo_flow_start"
    const val FO_SPLASH_VIEW = "fo_splash_view"
    const val FO_SPLASH_COMPLETE = "fo_splash_complete"
    const val FO_LANGUAGE_VIEW = "fo_language_view"
    const val FO_LANGUAGE_SELECT = "fo_language_select"
    const val FO_LANGUAGE_COMPLETE = "fo_language_complete"
    const val FO_LANGUAGE_FLOW_COMPLETE = "fo_language_flow_complete"
    const val FO_STEP_VIEW = "fo_step_view"
    const val FO_STEP_COMPLETE = "fo_step_complete"
    const val FO_QUESTION_VIEW = "fo_question_view"
    const val FO_QUESTION_ANSWER = "fo_question_answer"
    const val FO_QUESTION_COMPLETE = "fo_question_complete"
    const val FO_FLOW_COMPLETE = "fo_flow_complete"

    const val IAP_PAYWALL_VIEW = "iap_paywall_view"
    const val IAP_PAYWALL_RESULT = "iap_paywall_result"
    const val IAP_CLICK = "iap_click"
    const val IAP_SUCCESS = "iap_success"
    const val IAP_FAIL = "iap_fail"
    const val IAP_DISMISS = "iap_dismiss"

    const val CONSENT_REQUEST = "consent_request"
    const val CONSENT_SHOWN = "consent_shown"
    const val CONSENT_RESULT = "consent_result"

    /**
     * Google Play install referrer, read once per install through the MMP and emitted by `:ads`.
     * Carries the parsed `utm_*` fields rather than the raw referrer string: the raw value routinely
     * exceeds GA4's 100-character param limit and would arrive truncated mid-campaign-name.
     */
    const val APP_INSTALL_REFERRER = "app_install_referrer"

    /**
     * Every name the catalog can emit. Sinks use it to validate their configuration instead of
     * hand-mirroring the list — the previous generation's habit of copying the taxonomy into each
     * wrapper is exactly what let the two copies drift apart.
     */
    @JvmStatic
    fun all(): Set<String> = setOf(
        AD_REQUEST, AD_LOADED, AD_LOAD_FAILED, AD_SHOW, AD_SHOW_FAILED, AD_CLICK, AD_CLOSED,
        AD_IMPRESSION, AD_REWARD_EARNED, AD_SKIPPED,
        AD_REVENUE_TOTAL, AD_REVENUE_MICRO_FLUSH, AD_REVENUE_D3, AD_REVENUE_D7,
        FO_FLOW_START, FO_SPLASH_VIEW, FO_SPLASH_COMPLETE, FO_LANGUAGE_VIEW, FO_LANGUAGE_SELECT,
        FO_LANGUAGE_COMPLETE, FO_LANGUAGE_FLOW_COMPLETE, FO_STEP_VIEW, FO_STEP_COMPLETE,
        FO_QUESTION_VIEW, FO_QUESTION_ANSWER, FO_QUESTION_COMPLETE, FO_FLOW_COMPLETE,
        IAP_PAYWALL_VIEW, IAP_PAYWALL_RESULT, IAP_CLICK, IAP_SUCCESS, IAP_FAIL, IAP_DISMISS,
        CONSENT_REQUEST, CONSENT_SHOWN, CONSENT_RESULT,
        APP_INSTALL_REFERRER,
    )

    /**
     * Names that legitimately carry incremental revenue in `value` + `currency`.
     * `ad_impression` is deliberately absent: paid impressions go to a vendor's dedicated revenue
     * API through [TrackSink.onAdRevenue], and `ad_revenue_total` is a running total — reporting
     * either as an incremental revenue event double-counts the money.
     */
    @JvmStatic
    fun revenueEvents(): Set<String> = setOf(IAP_SUCCESS)

    // -----------------------------------------------------------------------
    // Ads
    // -----------------------------------------------------------------------

    object Ad {

        private fun base(placement: String, format: AdFormat, adUnitId: String?) = mapOf(
            PARAM_PLACEMENT to placement,
            PARAM_AD_FORMAT to format.key,
            PARAM_AD_UNIT_ID to adUnitId,
        )

        /** A load was actually requested from the network. */
        class Request(placement: String, format: AdFormat, adUnitId: String?) :
            SimpleEvent(AD_REQUEST, base(placement, format, adUnitId))

        class Loaded(
            placement: String,
            format: AdFormat,
            adUnitId: String?,
            latencyMs: Long? = null,
        ) : SimpleEvent(
            AD_LOADED,
            base(placement, format, adUnitId) + mapOf(PARAM_LATENCY_MS to latencyMs)
        )

        class LoadFailed(
            placement: String,
            format: AdFormat,
            adUnitId: String?,
            errorCode: Int? = null,
        ) : SimpleEvent(
            AD_LOAD_FAILED,
            base(placement, format, adUnitId) + mapOf(PARAM_ERROR_CODE to errorCode)
        )

        /**
         * The ad was actually displayed. The previous pipeline had no such event at all — only
         * request / matched / paid-impression — so show-rate and show failures were invisible.
         */
        class Show(placement: String, format: AdFormat, adUnitId: String? = null) :
            SimpleEvent(AD_SHOW, base(placement, format, adUnitId))

        class ShowFailed(
            placement: String,
            format: AdFormat,
            adUnitId: String? = null,
            errorCode: Int? = null,
        ) : SimpleEvent(
            AD_SHOW_FAILED,
            base(placement, format, adUnitId) + mapOf(PARAM_ERROR_CODE to errorCode)
        )

        class Click(placement: String, format: AdFormat, adUnitId: String? = null) :
            SimpleEvent(AD_CLICK, base(placement, format, adUnitId))

        class Closed(placement: String, format: AdFormat, adUnitId: String? = null) :
            SimpleEvent(AD_CLOSED, base(placement, format, adUnitId))

        class RewardEarned(placement: String, adUnitId: String? = null) :
            SimpleEvent(AD_REWARD_EARNED, base(placement, AdFormat.REWARDED, adUnitId))

        /** A show opportunity that policy declined — remote flag off, purchased user, no fill. */
        class Skipped(placement: String, format: AdFormat, reason: String) :
            SimpleEvent(
                AD_SKIPPED,
                mapOf(
                    PARAM_PLACEMENT to placement,
                    PARAM_AD_FORMAT to format.key,
                    PARAM_REASON to reason,
                ),
            )
    }

    // -----------------------------------------------------------------------
    // First-open flow
    // -----------------------------------------------------------------------

    object Fo {

        /**
         * The flow was entered. It is the denominator every later `fo_` rate divides by, so it is
         * emitted once per run even when the flow immediately decides to skip — a funnel whose
         * first step is `fo_splash_view` cannot tell "never started" from "started and bailed".
         *
         * Deliberately not `oncePerSession`: a replayed flow (debug menu, "restart tutorial") is a
         * real run, and suppressing it here while `fo_splash_view` still fires would leave the
         * denominator smaller than the funnel it divides.
         */
        class FlowStart : SimpleEvent(FO_FLOW_START)

        class SplashView : SimpleEvent(FO_SPLASH_VIEW)

        class SplashComplete(dwellMs: Long) :
            SimpleEvent(FO_SPLASH_COMPLETE, mapOf(PARAM_DWELL_MS to dwellMs))

        class LanguageView(screenIndex: Int, variant: String? = null) :
            SimpleEvent(
                FO_LANGUAGE_VIEW,
                mapOf(PARAM_SCREEN_INDEX to screenIndex, PARAM_VARIANT to variant),
            )

        class LanguageSelect(screenIndex: Int, language: String) :
            SimpleEvent(
                FO_LANGUAGE_SELECT,
                mapOf(PARAM_SCREEN_INDEX to screenIndex, PARAM_LANGUAGE to language),
            )

        class LanguageComplete(screenIndex: Int, language: String, dwellMs: Long? = null) :
            SimpleEvent(
                FO_LANGUAGE_COMPLETE,
                mapOf(
                    PARAM_SCREEN_INDEX to screenIndex,
                    PARAM_LANGUAGE to language,
                    PARAM_DWELL_MS to dwellMs,
                ),
            )

        /**
         * One event for every onboarding step. [variant] records which ad tier actually won the
         * race for that step, so an A/B-looking difference in the funnel can be attributed.
         */
        class StepView(step: String, index: Int, variant: String? = null) :
            SimpleEvent(
                FO_STEP_VIEW,
                mapOf(PARAM_STEP to step, PARAM_INDEX to index, PARAM_VARIANT to variant),
            )

        class StepComplete(
            step: String,
            index: Int,
            dwellMs: Long,
            exitReason: String? = null,
        ) : SimpleEvent(
            FO_STEP_COMPLETE,
            mapOf(
                PARAM_STEP to step,
                PARAM_INDEX to index,
                PARAM_DWELL_MS to dwellMs,
                PARAM_EXIT_REASON to exitReason,
            ),
        )

        class QuestionView(source: String? = null) :
            SimpleEvent(FO_QUESTION_VIEW, mapOf(PARAM_SOURCE to source))

        class QuestionAnswer(optionId: String, selected: Boolean) :
            SimpleEvent(
                FO_QUESTION_ANSWER,
                mapOf("option_id" to optionId, "selected" to selected),
            )

        class QuestionComplete(answerCount: Int) :
            SimpleEvent(FO_QUESTION_COMPLETE, mapOf(PARAM_COUNT to answerCount))

        class FlowComplete(stepsShown: Int, dwellMs: Long? = null) :
            SimpleEvent(
                FO_FLOW_COMPLETE,
                mapOf(PARAM_STEPS_SHOWN to stepsShown, PARAM_DWELL_MS to dwellMs),
            )

        /**
         * The whole language flow is done, as opposed to one language screen.
         *
         * It deliberately does not reuse [LanguageComplete]: the audited SDK emitted
         * `complete_lfo_flow` from both LFO1's and LFO2's Next handler, so a user who passed
         * through both screens could close the flow twice.
         */
        class LanguageFlowComplete(language: String) :
            SimpleEvent(FO_LANGUAGE_FLOW_COMPLETE, mapOf(PARAM_LANGUAGE to language))
    }

    // -----------------------------------------------------------------------
    // In-app purchase
    // -----------------------------------------------------------------------

    object Iap {

        class PaywallView(source: String) :
            SimpleEvent(IAP_PAYWALL_VIEW, mapOf(PARAM_SOURCE to source))

        /**
         * Terminal event of a paywall presentation, whatever the outcome. [status] is one of
         * `purchased`, `dismissed`, `continue_with_ads`.
         *
         * Every [PaywallView] gets exactly one of these, so paywall conversion is computable from
         * the pair alone. It carries no revenue — [Success] is emitted by the billing layer, and
         * putting money on both would double-count it.
         */
        class PaywallResult(source: String, status: String) :
            SimpleEvent(IAP_PAYWALL_RESULT, mapOf(PARAM_SOURCE to source, PARAM_STATUS to status))

        class Click(source: String, productId: String) :
            SimpleEvent(IAP_CLICK, mapOf(PARAM_SOURCE to source, PARAM_PRODUCT_ID to productId))

        /** Never put the purchase token in here — the validator rejects it outright. */
        class Success(productId: String, value: Double, currency: String, source: String? = null) :
            SimpleEvent(
                IAP_SUCCESS,
                mapOf(
                    PARAM_PRODUCT_ID to productId,
                    PARAM_VALUE to value,
                    PARAM_CURRENCY to currency,
                    PARAM_SOURCE to source,
                ),
            )

        class Fail(productId: String?, errorCode: Int?) :
            SimpleEvent(
                IAP_FAIL,
                mapOf(PARAM_PRODUCT_ID to productId, PARAM_ERROR_CODE to errorCode),
            )

        /** [reason] separates "closed the sheet" from "tapped continue with ads". */
        class Dismiss(source: String, reason: String? = null) :
            SimpleEvent(IAP_DISMISS, mapOf(PARAM_SOURCE to source, PARAM_REASON to reason))
    }

    // -----------------------------------------------------------------------
    // Consent (UMP)
    // -----------------------------------------------------------------------

    object ConsentEvents {

        class Requested : SimpleEvent(CONSENT_REQUEST, oncePerSession = true)

        class Shown : SimpleEvent(CONSENT_SHOWN, oncePerSession = true)

        /**
         * [status] is one of `granted`, `denied`, `not_required`, `error`. [source] names the
         * screen that asked, so a funnel can tell the splash prompt from a later one.
         */
        class Result(status: String, errorCode: Int? = null, source: String? = null) :
            SimpleEvent(
                CONSENT_RESULT,
                mapOf(
                    PARAM_STATUS to status,
                    PARAM_ERROR_CODE to errorCode,
                    PARAM_SOURCE to source,
                ),
            )
    }
}
