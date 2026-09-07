package io.retentionkit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import io.retentionkit.core.*
import io.retentionkit.feedback.RetentionFeedbackModule
import java.lang.ref.WeakReference

/**
 * Exact-entry continuation at a real resumed Main. Feature forwarding does not consume the token;
 * the final feature owns capture/dispatch. SDK feedback consumes only when its UI launch is ready.
 * Bind from onCreate, forward onNewIntent/onSaveInstanceState, and never select the global backlog.
 */
class RetentionMainHandoff internal constructor(
    private val kit: RetentionKit,
    activity: Activity,
    savedInstanceState: Bundle?,
    featureRouter: RetentionRouter,
    private val uiHost: RetentionUiHost,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val source = WeakReference(activity)
    private var router: RetentionRouter? = featureRouter
    private val main = Handler(Looper.getMainLooper())
    private var selected = savedInstanceState?.getString(STATE_TOKEN)
    private var forwarded = savedInstanceState?.getString(STATE_FORWARDED)
    private var resumed = false
    private var closed = false
    private var attempts = 0
    private var deliveryGeneration = 0L
    private var hostResource: AutoCloseable? = null
    private var resourceToken: String? = null
    private var hostExpiry: Runnable? = null
    private var acquiringHost = false
    private val deferredCloses = mutableListOf<AutoCloseable>()
    private val retry = Runnable { dispatch() }
    private var focusRegistration: WindowFocusRegistration? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus && !closed && resumed && hasPendingEntry) {
            // A dialog may outlive the retry budget without pausing Main. Focus is a new readiness
            // event, so start another bounded attempt window instead of polling indefinitely.
            attempts = 0
            holdHost()
            schedule()
        }
    }

    val hasPendingEntry: Boolean get() = !closed && selected?.let { kit.runtime.entries.pending(it) } != null

    init {
        checkMain()
        try {
            kit.runtime.application.registerActivityLifecycleCallbacks(this)
            focusRegistration = WindowFocusRegistration(activity.window.decorView, focusListener)
            // Saved selection is already materialized. Never capture a restored reusable template again.
            if (selected == null) capture(activity.intent)
            else kit.runtime.entries.pending(selected!!)?.let { RetentionEntryCodec.write(activity.intent, it) }
            holdHost()
        } catch (error: Exception) { close(); throw error }
    }

    fun onNewIntent(intent: Intent) {
        checkMain()
        if (closed) return
        source.get()?.intent = intent
        capture(intent)
        holdHost()
        schedule()
    }

    fun onSaveInstanceState(outState: Bundle) {
        checkMain()
        outState.putString(STATE_TOKEN, selected)
        outState.putString(STATE_FORWARDED, forwarded)
    }

    private fun capture(intent: Intent?) {
        // An ordinary/new invalid launch must not inherit an earlier selected entry.
        val generation = ++deliveryGeneration
        val previousSelected = selected
        val previousForwarded = forwarded
        selected = null
        forwarded = null
        attempts = 0
        main.removeCallbacks(retry)
        releaseHost()
        if (closed || generation != deliveryGeneration) return
        val accepted = kit.capture(intent)
        if (closed || generation != deliveryGeneration) return
        if (accepted is RetentionEntryAcceptance.Accepted) {
            selected = accepted.entry.token
            if (selected == previousSelected && selected == previousForwarded) forwarded = previousForwarded
        }
    }

    private fun dispatch() {
        val activity = source.get() ?: return close()
        if (closed || !resumed || activity.isFinishing || activity.isDestroyed || RetentionKit.get() !== kit) return
        val token = selected ?: return releaseHost()
        val entry = kit.runtime.entries.pending(token) ?: run { selected = null; releaseHost(); return }
        if (forwarded == token) return releaseHost()
        if (kit.runtime.activities.current() !== activity || !kit.runtime.isForeground ||
            !kit.runtime.userState.setupCompleted || kit.runtime.userState.onboardingActive ||
            kit.runtime.ui.eligibility(RetentionUiPurpose.ENTRY) is RetentionEligibility.Blocked) return retryLater()
        if (entry.destination == RetentionFeedbackModule.DESTINATION) {
            // Module owns the final acquire/reentrant handoff checks and exact consumption.
            kit.feedback?.handleEntry(entry)
            if (kit.runtime.entries.pending(token) == null) { selected = null; releaseHost() }
            else retryLater()
            return
        }
        // The final host router validates supported IDs, including explicitly retired aliases.
        val lease = (kit.runtime.ui.acquire("entry.main", 10_000, RetentionUiPurpose.ENTRY)
            as? RetentionUiLeaseResult.Acquired)?.lease ?: return retryLater()
        try {
            val target = router?.createIntent(activity, entry) ?: return retryLater()
            require(target.component?.packageName == activity.packageName) { "Feature router must name a host Activity" }
            require(target.component?.className != activity.javaClass.name) { "Feature router must not loop back to Main" }
            // Router callbacks can reenter/configure/navigate. Recheck every owned condition.
            val permittedActivity = lease.activity() // The host callback can deliver a newer Intent.
            if (permittedActivity !== activity || selected != token || kit.runtime.entries.pending(token) != entry ||
                !resumed || source.get() !== activity) return retryLater()
            activity.startActivity(RetentionEntryCodec.write(Intent(target), entry))
            if (selected == token) forwarded = token // Do not replace a reentrant newer selection.
            kit.runtime.emit(RetentionEvent("retention_entry_forwarded", mapOf("source" to entry.source.name)))
            releaseHostAfterDispatch(token)
        } catch (error: Exception) {
            kit.runtime.diagnostics.record("entry.main", "Feature forwarding failed", RetentionDiagnosticLevel.ERROR, error)
            retryLater()
        } finally { lease.close() }
    }

    private fun schedule() {
        main.removeCallbacks(retry)
        if (!closed && resumed) main.post(retry)
    }
    private fun retryLater() {
        if (!closed && resumed && attempts++ < 40) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 250)
        } else releaseHost()
    }
    private fun holdHost() {
        val token = selected?.takeIf { hasPendingEntry && it != forwarded } ?: return releaseHost()
        if (resourceToken == token || acquiringHost) return
        releaseHost()
        try {
            acquiringHost = true
            val resource = uiHost.onLeaseAcquired("entry.main", token, 15_000)
            if (closed || selected != token || !hasPendingEntry) { closeHost(resource); return }
            hostResource = resource
            resourceToken = token
            val owned = hostResource
            hostExpiry = Runnable { if (hostResource === owned && resourceToken == token) releaseHost() }
                .also { main.postDelayed(it, 15_000) }
        } catch (error: Exception) {
            kit.runtime.diagnostics.record("entry.main", "Host entry resource unavailable", RetentionDiagnosticLevel.ERROR, error)
        } finally { acquiringHost = false }
    }
    private fun releaseHostAfterDispatch(expectedToken: String? = null) {
        if (expectedToken != null && resourceToken != expectedToken) return
        val resource = detachHost() ?: return
        deferredCloses.add(resource)
        main.post { if (deferredCloses.remove(resource)) closeHost(resource) } // Only this dispatch's resource, never a replacement.
    }
    private fun detachHost(): AutoCloseable? {
        hostExpiry?.let(main::removeCallbacks)
        hostExpiry = null
        val resource = hostResource
        hostResource = null
        resourceToken = null
        return resource
    }
    private fun releaseHost() = closeHost(detachHost())
    private fun closeHost(resource: AutoCloseable?) {
        try { resource?.close() } catch (error: Exception) {
            kit.runtime.diagnostics.record("entry.main", "Host entry resource close failed", RetentionDiagnosticLevel.ERROR, error)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (source.get() !== activity) return
        resumed = true
        attempts = 0
        holdHost()
        schedule() // Core's current-Activity callback may run after this callback.
    }
    override fun onActivityPaused(activity: Activity) {
        if (source.get() !== activity) return
        resumed = false
        main.removeCallbacks(retry)
        releaseHostAfterDispatch()
    }
    override fun onActivityDestroyed(activity: Activity) { if (source.get() === activity) close() }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (source.get() === activity) onSaveInstanceState(outState)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        focusRegistration?.close(); focusRegistration = null
        kit.runtime.application.unregisterActivityLifecycleCallbacks(this)
        releaseHost()
        deferredCloses.toList().also { deferredCloses.clear() }.forEach(::closeHost)
        router = null
        source.clear()
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Main handoff requires main thread" } }
    private companion object {
        const val STATE_TOKEN = "io.retentionkit.main.selected"
        const val STATE_FORWARDED = "io.retentionkit.main.forwarded"
    }
}
