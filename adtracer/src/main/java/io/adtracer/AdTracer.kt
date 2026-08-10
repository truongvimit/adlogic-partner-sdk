package io.adtracer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Debug-only ad lifecycle tracker. Standalone: no dependency on any ads SDK — the host app
 * adapts its own ad callbacks into [event] calls. Until [start] runs every call is a no-op,
 * which is how release builds stay at zero overhead.
 */
object AdTracer {

    private const val TAG = "AdTracer"

    @Volatile private var collector: EventCollector? = null

    /** Port the dashboard bound to, or -1. Exposed so a debug screen can display the URL. */
    @Volatile var dashboardPort: Int = -1
        private set

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (collector != null) return
        val appContext = context.applicationContext
        val sessionId = UUID.randomUUID().toString().substring(0, 8)
        val newCollector = EventCollector(appContext, sessionId)
        collector = newCollector
        dashboardPort = DashboardServer(appContext, newCollector).start()
        event("session_start", "_tracer", AdFormat.OTHER, message = "session $sessionId")
        if (dashboardPort > 0) {
            Log.i(TAG, "Dashboard ready: adb forward tcp:$dashboardPort tcp:$dashboardPort  ->  http://localhost:$dashboardPort")
        }
    }

    fun event(
        type: String,
        placement: String,
        format: AdFormat,
        adUnitId: String? = null,
        reason: String? = null,
        code: Int? = null,
        message: String? = null,
        synthetic: Boolean = false,
        approx: Boolean = false,
    ) {
        val target = collector ?: return
        // Runs inside ad callbacks on the main thread — a tracker failure must never propagate
        try {
            // Timestamps captured on the caller thread so queue latency never skews them
            target.emit(
                EventDraft(
                    System.currentTimeMillis(), SystemClock.elapsedRealtime(), type, placement,
                    format.name, adUnitId, reason, code, message, synthetic, approx,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Emit failed: $t")
        }
    }

    fun loadRequested(placement: String, format: AdFormat, adUnitId: String? = null) =
        event("load_requested", placement, format, adUnitId)

    fun loadSkipped(placement: String, format: AdFormat, reason: String) =
        event("load_skipped", placement, format, reason = reason)

    fun loaded(placement: String, format: AdFormat, approx: Boolean = false) =
        event("loaded", placement, format, approx = approx)

    fun loadFailed(placement: String, format: AdFormat, code: Int? = null, message: String? = null) =
        event("load_failed", placement, format, code = code, message = message)

    fun showRequested(placement: String, format: AdFormat) = event("show_requested", placement, format)

    fun showStarted(placement: String, format: AdFormat) = event("show_started", placement, format)

    fun showBlocked(placement: String, format: AdFormat, reason: String) =
        event("show_blocked", placement, format, reason = reason)

    fun shown(placement: String, format: AdFormat) = event("shown", placement, format)

    fun showFailed(placement: String, format: AdFormat, code: Int? = null, message: String? = null, synthetic: Boolean = false) =
        event("show_failed", placement, format, code = code, message = message, synthetic = synthetic)

    fun dismissed(placement: String, format: AdFormat) = event("dismissed", placement, format)

    fun impression(placement: String, format: AdFormat) = event("impression", placement, format)

    fun clicked(placement: String, format: AdFormat) = event("clicked", placement, format)

    fun rendered(placement: String) = event("rendered", placement, AdFormat.NATIVE)

    fun reRendered(placement: String) = event("re_rendered", placement, AdFormat.NATIVE)

    fun discarded(placement: String, format: AdFormat, reason: String) =
        event("discarded", placement, format, reason = reason)
}
