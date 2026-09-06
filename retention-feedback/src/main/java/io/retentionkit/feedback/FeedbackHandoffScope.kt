package io.retentionkit.feedback

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.core.RetentionSignal
import java.lang.ref.WeakReference

internal class FeedbackHandoffScope(
    private val runtime: RetentionRuntime, activity: Activity, val token: String,
    private val handler: Handler, private val onClosed: () -> Unit,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private var left = false
    private var closed = false
    private val timeout = Runnable { close() }
    init {
        runtime.application.registerActivityLifecycleCallbacks(this)
        handler.postDelayed(timeout, 120_000)
        runtime.signal(RetentionSignal.ExternalTransitionStarted(token, "feedback_handoff", 120_000))
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        runtime.application.unregisterActivityLifecycleCallbacks(this)
        source.clear()
        runtime.signal(RetentionSignal.ExternalTransitionFinished(token))
        onClosed()
    }
    override fun onActivityPaused(activity: Activity) { if (source.get() === activity) left = true }
    override fun onActivityResumed(activity: Activity) { if (source.get() === activity && left) close() }
    override fun onActivityDestroyed(activity: Activity) { if (source.get() === activity) close() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
