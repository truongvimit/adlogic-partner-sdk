package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import io.retentionkit.core.*
import java.lang.ref.WeakReference

/** Implement only the business UI. The suite owns capture, readiness, saved selection and claim. */
interface RetentionFeatureHost {
    /** Called once after consuming this exact envelope. Retired aliases retain their original ID. */
    fun onRetentionFeature(entry: RetentionEntry, destination: String)
}

/** A final destination has one selected entry, never an implicit selection from the backlog. */
internal class RetentionFeatureHandoff(
    private val kit: RetentionKit,
    activity: Activity,
    saved: Bundle?,
    private val resolveDestination: (String) -> String,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private val main = Handler(Looper.getMainLooper())
    private val view = WeakReference(activity.window.decorView)
    private val observer = WeakReference(activity.window.decorView.viewTreeObserver)
    private var token: String? = null
    private var generation = 0L
    private var resumed = false
    private var closed = false
    private var attempts = 0
    private val retry = Runnable { dispatch() }
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { gained ->
        if (gained && resumed) { attempts = 0; schedule() }
    }

    init {
        kit.runtime.application.registerActivityLifecycleCallbacks(this)
        observer.get()?.addOnWindowFocusChangeListener(focus)
        val restored = RetentionEntryCodec.decode(saved?.getString(STATE_ENTRY))
        if (restored is RetentionEntryDecodeResult.Valid) RetentionEntryCodec.write(activity.intent, restored.entry)
        capture(activity.intent)
    }

    fun onNewIntent(intent: Intent) {
        if (closed) return
        source.get()?.intent = intent
        capture(intent)
        schedule()
    }
    private fun capture(intent: Intent?) {
        val current = ++generation
        token = null
        attempts = 0
        main.removeCallbacks(retry)
        val accepted = kit.capture(intent)
        if (!closed && current == generation && accepted is RetentionEntryAcceptance.Accepted) token = accepted.entry.token
    }
    private fun schedule() { main.removeCallbacks(retry); if (!closed && resumed) main.post(retry) }
    private fun retryLater() {
        if (!closed && resumed && attempts++ < 40) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 250)
        }
    }
    private fun dispatch() {
        val activity = source.get() ?: return close()
        if (closed || !resumed || activity.isFinishing || activity.isDestroyed || RetentionKit.get() !== kit) return
        val selected = token ?: return
        val entry = kit.runtime.entries.pending(selected) ?: run { token = null; return }
        if (kit.runtime.activities.current() !== activity || !kit.runtime.isForeground || !kit.runtime.userState.setupCompleted) return retryLater()
        val lease = (kit.runtime.ui.acquire("entry.feature", 10_000, RetentionUiPurpose.ENTRY)
            as? RetentionUiLeaseResult.Acquired)?.lease ?: return retryLater()
        try {
            val destination = resolveDestination(entry.destination)
            if (kit.runtime.features().none { it.id == destination }) return
            // Resolver and host gate are arbitrary callbacks. Check selection AFTER both return.
            val ready = lease.activity()
            if (ready !== activity || closed || !resumed || token != selected ||
                kit.runtime.entries.pending(selected) != entry) return retryLater()
            val claimed = kit.consume(selected) ?: return
            token = null
            (activity as RetentionFeatureHost).onRetentionFeature(claimed, destination)
        } catch (error: Exception) {
            // A consumed callback is never replayed: business commands need their own durable ID.
            kit.runtime.diagnostics.record("entry.feature", "Feature presentation failed", RetentionDiagnosticLevel.ERROR, error)
        } finally { lease.close() }
    }
    override fun onActivityResumed(activity: Activity) {
        if (source.get() === activity) { resumed = true; attempts = 0; schedule() }
    }
    override fun onActivityPaused(activity: Activity) {
        if (source.get() === activity) { resumed = false; main.removeCallbacks(retry) }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (source.get() === activity) {
            val entry = (RetentionEntryCodec.read(activity.intent) as? RetentionEntryDecodeResult.Valid)?.entry
            entry?.let { outState.putString(STATE_ENTRY, RetentionEntryCodec.encode(it)) }
        }
    }
    override fun onActivityDestroyed(activity: Activity) { if (source.get() === activity) close() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        val live = view.get()?.viewTreeObserver
        live?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        observer.get()?.takeIf { it !== live && it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        kit.runtime.application.unregisterActivityLifecycleCallbacks(this)
        source.clear(); view.clear(); observer.clear()
    }
    private companion object { const val STATE_ENTRY = "retention.feature.entry" }
}
