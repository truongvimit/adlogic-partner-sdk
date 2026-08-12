package io.trackkit.sink

import android.util.Log
import io.trackkit.AdImpression
import io.trackkit.Consent
import io.trackkit.TrackSink

/**
 * Logcat sink for development. Add it in debug builds only:
 * ```
 * if (BuildConfig.DEBUG) Tracker.addSink(ConsoleSink())
 * ```
 * The previous SDK shipped a console plugin too — but its whole mediator was dead code, so nobody
 * ever saw an event. This one is wired through the same path as every production sink, which is
 * exactly what makes it useful: what you see here is what Firebase receives.
 */
class ConsoleSink(
    private val tag: String = "Trackkit/Console",
    private val ringSize: Int = 100,
) : TrackSink {

    override val id: String = "console"

    private val ring = ArrayDeque<String>(ringSize)

    /** Last N lines, newest last — handy to dump into a debug screen or a bug report. */
    val history: List<String> get() = synchronized(ring) { ring.toList() }

    override fun onEvent(name: String, params: Map<String, Any?>) {
        record("EVENT $name ${format(params)}")
    }

    override fun onScreen(name: String, screenClass: String?) {
        record("SCREEN $name${screenClass?.let { " ($it)" } ?: ""}")
    }

    override fun onUserProperty(key: String, value: String?) {
        record("USER_PROP $key = $value")
    }

    override fun onUserId(id: String?) {
        record("USER_ID $id")
    }

    override fun onConsent(consent: Consent) {
        record("CONSENT analytics=${consent.analytics} ads=${consent.ads}")
    }

    override fun onAdRevenue(impression: AdImpression) {
        record(
            "REVENUE ${impression.placement}/${impression.format.key} " +
                    "${impression.value} ${impression.currency} " +
                    "via ${impression.adPlatform}/${impression.network ?: "-"}"
        )
    }

    private fun format(params: Map<String, Any?>): String =
        params.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }

    private fun record(line: String) {
        Log.d(tag, line)
        synchronized(ring) {
            if (ring.size >= ringSize) ring.removeFirst()
            ring.addLast(line)
        }
    }
}
