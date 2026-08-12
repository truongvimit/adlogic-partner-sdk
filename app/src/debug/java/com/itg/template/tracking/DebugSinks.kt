package com.itg.template.tracking

import io.trackkit.Tracker

/**
 * Variant seam for debug-only destinations. :adtracer is a debugImplementation dependency, so the
 * registration cannot live in main; the release twin is one no-op line instead of a mirrored API.
 */
fun installDebugSinks() {
    Tracker.addSink(AdTracerSink())
}
