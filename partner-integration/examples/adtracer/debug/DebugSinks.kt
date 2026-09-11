package com.example.app.tracking

import io.trackkit.Tracker

/** The release source set provides the same function as a no-op. */
fun installDebugSinks() {
    Tracker.addSink(AdTracerSink())
}
