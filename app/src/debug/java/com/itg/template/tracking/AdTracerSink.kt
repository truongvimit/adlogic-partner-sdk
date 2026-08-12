package com.itg.template.tracking

import android.content.Context
import io.adtracer.AdTracer
import io.trackkit.AdFormat
import io.trackkit.TrackSink
import io.trackkit.TrackkitEvents
import io.adtracer.AdFormat as TracerFormat

/**
 * Debug dashboard destination: mirrors the Trackkit stream into :adtracer.
 *
 * This is a sink, not a decorator. Every reporter (:ads, :onboardkitorigin, the app) already
 * emits into Tracker, so the dashboard needs no call-site wrapping at all — the same reason
 * ExoPlayer exposes addAnalyticsListener instead of asking callers to wrap Player.Listener.
 */
class AdTracerSink : TrackSink {

    override val id: String = "adtracer"

    override fun onInstall(context: Context) = AdTracer.start(context)

    override fun onEvent(name: String, params: Map<String, Any?>) {
        val placement = params[TrackkitEvents.PARAM_PLACEMENT] as? String
        if (placement == null) {
            // Funnel / IAP / consent events: visible on the timeline, excluded from ad aggregates
            AdTracer.event(name, NON_AD_PLACEMENT, TracerFormat.OTHER)
            return
        }
        val format = formatOf(params[TrackkitEvents.PARAM_AD_FORMAT] as? String)
        val adUnitId = params[TrackkitEvents.PARAM_AD_UNIT_ID] as? String
        val code = (params[TrackkitEvents.PARAM_ERROR_CODE] as? Number)?.toInt()
        val reason = params[TrackkitEvents.PARAM_REASON] as? String
        when (name) {
            TrackkitEvents.AD_REQUEST -> AdTracer.loadRequested(placement, format, adUnitId)
            TrackkitEvents.AD_LOADED -> AdTracer.loaded(placement, format)
            TrackkitEvents.AD_LOAD_FAILED -> AdTracer.loadFailed(placement, format, code)
            TrackkitEvents.AD_SHOW -> AdTracer.shown(placement, format)
            TrackkitEvents.AD_SHOW_FAILED -> AdTracer.showFailed(placement, format, code)
            TrackkitEvents.AD_CLICK -> AdTracer.clicked(placement, format)
            TrackkitEvents.AD_CLOSED -> AdTracer.dismissed(placement, format)
            // Paid impressions arrive here as ad_impression; onAdRevenue would report the same
            // money a second time, so it is deliberately not implemented
            TrackkitEvents.AD_IMPRESSION -> AdTracer.impression(placement, format)
            TrackkitEvents.AD_REWARD_EARNED -> AdTracer.event("reward_earned", placement, format)
            TrackkitEvents.AD_SKIPPED -> AdTracer.loadSkipped(
                placement,
                format,
                reason ?: "unknown"
            )

            else -> AdTracer.event(name, placement, format, adUnitId, reason, code)
        }
    }

    private fun formatOf(key: String?): TracerFormat = when (AdFormat.fromKey(key)) {
        AdFormat.BANNER, AdFormat.COLLAPSIBLE_BANNER -> TracerFormat.BANNER
        AdFormat.INTERSTITIAL -> TracerFormat.INTERSTITIAL
        AdFormat.REWARDED, AdFormat.REWARDED_INTERSTITIAL -> TracerFormat.REWARDED
        AdFormat.NATIVE, AdFormat.NATIVE_FULL_SCREEN -> TracerFormat.NATIVE
        AdFormat.APP_OPEN -> TracerFormat.APP_OPEN
        AdFormat.UNKNOWN -> TracerFormat.OTHER
    }

    private companion object {
        // The dashboard keeps this placement out of every request/fill rate
        const val NON_AD_PLACEMENT = "_tracer"
    }
}
