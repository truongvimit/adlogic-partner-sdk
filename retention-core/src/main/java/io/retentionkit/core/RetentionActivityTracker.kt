package io.retentionkit.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference

internal class RetentionActivityTracker(
    private val application: Application,
    private val onSignal: (RetentionSignal) -> Unit,
    private val onPause: () -> Unit,
    private val diagnostics: RetentionDiagnostics,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver, ForegroundActivityProvider {
    @Volatile private var resumed = WeakReference<Activity>(null)
    @Volatile private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private var processLifecycle: Lifecycle? = null

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        onMain {
            if (!closed) diagnostics.guard("core.lifecycle.attach") {
                processLifecycle = ProcessLifecycleOwner.get().lifecycle.also { it.addObserver(this) }
            }
        }
    }

    fun stop() {
        closed = true
        resumed.clear()
        application.unregisterActivityLifecycleCallbacks(this)
        onMain { processLifecycle?.removeObserver(this); processLifecycle = null }
    }

    override fun current(): Activity? = resumed.get()?.takeUnless { closed || it.isFinishing || it.isDestroyed }
    override fun onStart(owner: LifecycleOwner) { if (!closed) onSignal(RetentionSignal.ProcessForeground) }
    override fun onStop(owner: LifecycleOwner) { if (!closed) onSignal(RetentionSignal.ProcessBackground) }
    override fun onActivityResumed(activity: Activity) { if (!closed) resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) { resumed.clear(); onPause() }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.get() === activity) { resumed.clear(); onPause() }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block) }
}
