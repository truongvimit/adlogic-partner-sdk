package com.itg.template.retention

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import io.retentionkit.core.*

/** Release has no QA profile, overrides, clock offset or test entry point. */
object ExampleQa {
    fun entryAdSelected(intent: android.content.Intent, key: String) = Unit
    fun options(standard: io.retentionkit.RetentionKitOptions) = standard
    fun nativeEvent(placement: String, phase: String) = Unit
    fun clock(context: Context): RetentionClock = RetentionClock.System
    fun store(context: Context): RetentionStore? = null
    fun attach(activity: Activity, parent: LinearLayout) = Unit
    fun refresh(activity: Activity) = Unit
    fun route(activity: Activity, text: String) = Unit
}
