package io.retentionkit.integration

import io.retentionkit.core.RetentionEvent
import io.retentionkit.core.RetentionEventSink
import io.trackkit.Tracker

/** Optional adapter; call only when the host already declares/installs Trackkit and its chosen sinks. */
class TrackkitRetentionEventSink : RetentionEventSink {
    override fun onEvent(event: RetentionEvent) { Tracker.track(event.name, event.attributes) }
}
