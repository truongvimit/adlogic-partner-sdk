package io.retentionkit.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Bounded source-Activity handoff for SDK screens and Store/settings actions. Construction has no
 * effects: register this object in the module's owner map before start(), whose signal can reenter.
 * Widget pin confirmation has a different lifecycle and does not use this scope.
 */
class RetentionHandoffScope @JvmOverloads constructor(
    private val runtime: RetentionRuntime,
    activity: Activity,
    val token: String,
    private val kind: String,
    private val durationMillis: Long = 120_000,
    private val onClosed: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var left = false
    private var closed = false
    private val timeout = Runnable { close() }

    init { require(durationMillis in 1..300_000); require(validId(token)); require(kind.isNotBlank()) }

    fun start() {
        checkMain()
        if (closed || started) return
        started = true
        try {
            runtime.application.registerActivityLifecycleCallbacks(this)
            main.postDelayed(timeout, durationMillis)
            if (!runtime.signal(RetentionSignal.ExternalTransitionStarted(token, kind, durationMillis))) close()
        } catch (error: Exception) { close(); throw error }
    }

    /** Rechecks host state while ignoring only this scope's intentionally installed UI block. */
    fun activity(): Activity? {
        checkMain()
        if (closed || !started || RetentionRuntime.get() !== runtime) return null
        val activity = source.get() ?: return null
        return if (runtime.ui.canContinueHandoff(token, activity) && !closed) activity else null
    }

    /**
     * Signals raised from an existing subscriber are queued. Run after that queue drains, on main,
     * so all synchronous observers can cancel before launch. Expired/closed scopes deliver null.
     * No waiting and no retained Activity; core bounds outstanding continuations to 128.
     */
    fun dispatch(action: (Activity?) -> Unit) {
        checkMain()
        val continuation = {
            val invoke = Runnable {
                try { action(activity()) }
                catch (error: Exception) {
                    close()
                    runtime.diagnostics.record("core.handoff", "Handoff continuation failed", RetentionDiagnosticLevel.ERROR, error)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) invoke.run() else main.post(invoke)
            Unit
        }
        if (!runtime.afterSignalDispatch(continuation)) { close(); continuation() }
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        if (started) runtime.application.unregisterActivityLifecycleCallbacks(this)
        source.clear()
        // Remove owner registration before Finished can synchronously create a replacement scope.
        try { runtime.diagnostics.guard("core.handoff.close") { onClosed() } }
        finally { if (started) runtime.signal(RetentionSignal.ExternalTransitionFinished(token)) }
    }

    override fun onActivityPaused(activity: Activity) { if (source.get() === activity) left = true }
    override fun onActivityResumed(activity: Activity) { if (source.get() === activity && left) close() }
    override fun onActivityDestroyed(activity: Activity) { if (source.get() === activity) close() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Handoff scope requires main thread" } }
}
