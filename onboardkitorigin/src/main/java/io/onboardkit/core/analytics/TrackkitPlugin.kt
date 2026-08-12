package io.onboardkit.core.analytics

import io.trackkit.Tracker
import io.trackkit.TrackkitEvents

/**
 * Translates the SDK's internal [AnalyticsEvent] catalog into the canonical Trackkit taxonomy
 * and forwards it to [Tracker].
 *
 * Registered by `OnboardingSdk.install` unless the builder opted out, so a partner that only
 * wires `Tracker.install` + `Tracker.addSink` gets the whole first-open funnel for free.
 *
 * The internal catalog keeps its legacy names (`ob_lfo1_view`, `ob_step_viewed`, …) for plugins
 * partners already wrote; the canonical name is produced here, once, so a screen never has to
 * know two taxonomies. The `when` below is exhaustive over the sealed catalog and has no `else`:
 * adding an event without a canonical counterpart fails to compile instead of going unreported.
 *
 * A singleton because [AnalyticsHub] de-duplicates by identity: auto-registration plus a manual
 * `addAnalyticsPlugin(TrackkitPlugin)` still results in one forward per event.
 */
object TrackkitPlugin : AnalyticsPlugin {

    override fun execute(event: AnalyticsEvent) {
        when (event) {
            is AnalyticsEvent.FlowStarted ->
                Tracker.track(TrackkitEvents.Fo.FlowStart())

            is AnalyticsEvent.SplashViewed ->
                Tracker.track(TrackkitEvents.Fo.SplashView())

            is AnalyticsEvent.SplashCompleted ->
                Tracker.track(TrackkitEvents.Fo.SplashComplete(event.dwellMs))

            is AnalyticsEvent.LanguageViewed ->
                Tracker.track(TrackkitEvents.Fo.LanguageView(event.screenIndex, event.variant))

            is AnalyticsEvent.LanguageSelected ->
                Tracker.track(TrackkitEvents.Fo.LanguageSelect(event.screenIndex, event.code))

            is AnalyticsEvent.LanguageCompleted ->
                Tracker.track(TrackkitEvents.Fo.LanguageComplete(event.screenIndex, event.code))

            is AnalyticsEvent.LanguageFlowCompleted ->
                Tracker.track(TrackkitEvents.Fo.LanguageFlowComplete(event.code))

            is AnalyticsEvent.StepViewed -> Tracker.track(
                TrackkitEvents.Fo.StepView(event.stepId.value, event.index, event.variant),
            )

            is AnalyticsEvent.StepCompleted -> Tracker.track(
                TrackkitEvents.Fo.StepComplete(
                    event.stepId.value,
                    event.index,
                    event.dwellMs,
                    event.exitReason,
                ),
            )

            is AnalyticsEvent.QuestionViewed ->
                Tracker.track(TrackkitEvents.Fo.QuestionView(event.source))

            is AnalyticsEvent.QuestionOptionSelected ->
                Tracker.track(TrackkitEvents.Fo.QuestionAnswer(event.optionId, event.selected))

            is AnalyticsEvent.QuestionCompleted ->
                Tracker.track(TrackkitEvents.Fo.QuestionComplete(event.count))

            is AnalyticsEvent.FlowCompleted ->
                Tracker.track(TrackkitEvents.Fo.FlowComplete(event.stepsShown, event.dwellMs))

            // Ads. The ad unit id belongs to the provider, so only placement + format are known
            // here; revenue and clicks arrive separately through the paid-event bridge in :ads.
            is AnalyticsEvent.AdRequested ->
                Tracker.track(TrackkitEvents.Ad.Request(event.placementName, event.format, null))

            is AnalyticsEvent.AdImpression ->
                Tracker.track(TrackkitEvents.Ad.Show(event.placementName, event.format))

            is AnalyticsEvent.AdFailed ->
                Tracker.track(TrackkitEvents.Ad.LoadFailed(event.placementName, event.format, null))

            is AnalyticsEvent.AdSkipped -> Tracker.track(
                TrackkitEvents.Ad.Skipped(event.placementName, event.format, event.reason),
            )

            // The paywall sheet the flow presented. Every view gets a result, including the
            // purchase — the earlier version emitted nothing on that branch, so the funnel never
            // closed and paywall conversion was uncomputable. Revenue is not repeated here: the
            // billing layer owns `iap_success`.
            is AnalyticsEvent.PaywallViewed ->
                Tracker.track(TrackkitEvents.Iap.PaywallView(event.placementName))

            is AnalyticsEvent.PaywallResolved ->
                Tracker.track(TrackkitEvents.Iap.PaywallResult(event.placementName, event.outcome))
        }
    }
}
