package io.retentionkit.review

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import io.retentionkit.core.RetentionRuntime
import io.retentionkit.core.RetentionSignal
import java.lang.ref.WeakReference

/** A temporary system-return observer, not another permanent process lifecycle observer. */
internal class ReviewHandoffScope(
    private val runtime: RetentionRuntime,
    activity: Activity,
    val token: String,
    private val handler: Handler,
    private val onClosed: () -> Unit,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private var paused = false
    private var closed = false
    private val timeout = Runnable { close() }
    init {
        runtime.application.registerActivityLifecycleCallbacks(this)
        handler.postDelayed(timeout, 120_000)
        runtime.signal(RetentionSignal.ExternalTransitionStarted(token, "review_or_store", 120_000))
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
    override fun onActivityPaused(activity: Activity) { if (source.get() === activity) paused = true }
    override fun onActivityResumed(activity: Activity) { if (source.get() === activity && paused) close() }
    override fun onActivityDestroyed(activity: Activity) { if (source.get() === activity) close() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
