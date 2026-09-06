package io.retentionkit.widgets

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import io.retentionkit.core.ForegroundActivityProvider
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Temporary observer for a launcher overlay that pauses the Activity without stopping the process. */
internal class WidgetPinReturnObserver(
    private val application: Application,
    activity: Activity,
    private val activities: ForegroundActivityProvider,
    private val main: Handler,
    private val terminal: (String) -> Unit,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private val closed = AtomicBoolean()
    private var paused = false
    private val returned = Runnable {
        val activity = source.get()
        if (!closed.get() && paused && activity != null && activities.current() === activity &&
            !activity.isFinishing && !activity.isDestroyed) terminal("returned_without_callback")
    }

    init { application.registerActivityLifecycleCallbacks(this) }

    override fun onActivityPaused(activity: Activity) {
        if (!closed.get() && source.get() === activity) {
            paused = true
            main.removeCallbacks(returned)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (!closed.get() && paused && source.get() === activity) {
            // Let core update its current Activity and let an already queued launcher callback win.
            // A first/self resume without an observed pause must not terminate the request.
            main.removeCallbacks(returned)
            main.post(returned)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (!closed.get() && source.get() === activity) terminal("source_destroyed")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(returned)
        try { application.unregisterActivityLifecycleCallbacks(this) }
        finally { source.clear() }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
