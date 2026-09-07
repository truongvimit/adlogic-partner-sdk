package io.retentionkit.feedback

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.retentionkit.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

class RetentionFeedbackModule @JvmOverloads constructor(internal val options: FeedbackOptions = FeedbackOptions()) : RetentionModule {
    override val id = "feedback"
    private val main = Handler(Looper.getMainLooper())
    @Volatile internal var runtime: RetentionRuntime? = null; private set
    private var visible = WeakReference<Activity>(null)
    private var launchPending: String? = null
    private val scopes = mutableMapOf<String, RetentionHandoffScope>()

    override fun validateConfig(config: RetentionConfigSnapshot): List<String> = buildList {
        for (key in listOf("feedback.enabled", "feedback.shortcut_enabled", "feedback.show_reasons")) {
            config.values[key]?.let { if (it.toBooleanStrictOrNull() == null) add("$key must be boolean") }
        }
        if (options.featureIds.size > 10 || options.featureIds.any { !ID_PATTERN.matches(it) }) add("Invalid featureIds")
    }
    override fun attach(runtime: RetentionRuntime) { this.runtime = runtime; attached = this }
    override fun reconcile(reason: String) = onMain { updateShortcut() }
    override fun onSignal(signal: RetentionSignal) {
        if (signal is RetentionSignal.ConfigurationChanged) onMain {
            if (!enabled()) {
                runtime?.store?.transaction(STATE) { state ->
                    state.string("active")?.let { token -> decode(state.string(token))?.let { save(state, it.copy(phase = FeedbackPhase.CANCELLED)) } }
                    state.remove("active")
                }
                launchPending = null
                visible.get()?.finish()
                scopes.values.toList().forEach { it.close() }
            }
        }
    }

    fun handles(entry: RetentionEntry): Boolean = entry.destination == DESTINATION
    fun show(): FeedbackShowResult = showInternal(null)
    /** Partner menu/button entry: always routes through the configured Splash before the survey. */
    fun openViaEntry(): FeedbackShowResult {
        if (runtime == null) return FeedbackShowResult.Unavailable("not_attached")
        onMain {
            val rt = runtime ?: return@onMain
            if (!enabled() || scopes.containsKey(ENTRY_SCOPE)) return@onMain
            val acquired = rt.ui.acquire("feedback.entry", 15_000, RetentionUiPurpose.ENTRY)
                as? RetentionUiLeaseResult.Acquired ?: return@onMain
            var scope: RetentionHandoffScope? = null
            try {
                val activity = acquired.lease.activity() ?: return@onMain
                val revision = rt.config.revision
                val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, DESTINATION, "open_feedback", createdAtMillis = rt.clock.wallTimeMillis())
                val intent = rt.createEntryIntent(entry) ?: return@onMain
                if (acquired.lease.activity() !== activity || runtime !== rt || !enabled() || rt.config.revision != revision) return@onMain
                val owned = createScope(rt, activity, ENTRY_SCOPE, RetentionUiPurpose.ENTRY).also { scope = it }
                owned.start()
                owned.dispatch { current ->
                    if (current == null || runtime !== rt || !enabled() || rt.config.revision != revision) owned.close()
                    else {
                        val launched = try { options.launcher.launch(current, intent) } catch (_: Exception) { false }
                        if (launched) event("entry_requested") else { owned.close(); event("failed", "entry_launch_failed") }
                    }
                }
            } catch (error: Exception) { scope?.close(); diagnostic("entry", error) }
            finally { acquired.lease.close() }
        }
        return FeedbackShowResult.Scheduled
    }
    /** Final feedback route: keep the entry staged until UI gates pass, then consume before launch. */
    fun handleEntry(entry: RetentionEntry): FeedbackShowResult {
        if (!handles(entry) || entry.mode != RetentionEntryMode.ONCE) return FeedbackShowResult.Unavailable("not_a_materialized_feedback_entry")
        val rt = runtime ?: return FeedbackShowResult.Unavailable("not_attached")
        return try {
            if (rt.entries.stage(entry) is RetentionEntryAcceptance.Accepted) showInternal(entry)
            else FeedbackShowResult.Unavailable("entry_rejected")
        } catch (error: Exception) { diagnostic("entry", error); FeedbackShowResult.Unavailable("entry_storage_failed") }
    }
    private fun showInternal(entry: RetentionEntry?): FeedbackShowResult {
        if (runtime == null) return FeedbackShowResult.Unavailable("not_attached")
        onMain {
            val rt = runtime ?: return@onMain
            if (!enabled()) { event("skipped", "disabled"); return@onMain }
            if (launchPending != null || visible.get()?.let { !it.isFinishing && !it.isDestroyed } == true) { event("skipped", "already_open"); return@onMain }
            val purpose = if (entry == null) RetentionUiPurpose.PROMPT else RetentionUiPurpose.ENTRY
            val acquired = rt.ui.acquire("feedback.launch", 15_000, purpose) as? RetentionUiLeaseResult.Acquired
            if (acquired == null) { event("skipped", "ui_blocked"); return@onMain }
            val activity = acquired.lease.activity()
            if (activity == null) { acquired.lease.close(); return@onMain }
            val revision = rt.config.revision
            var scope: RetentionHandoffScope? = null
            var sessionToken: String? = null
            try {
                val session = rt.store.transaction(STATE) { state ->
                    prune(state)
                    val old = state.string("active")?.let { decode(state.string(it)) }
                    if (old?.phase == FeedbackPhase.OPEN) old.copy(uiPurpose = purpose).also { save(state, it) } else {
                        FeedbackSession(UUID.randomUUID().toString(), rt.clock.wallTimeMillis(), FeedbackPhase.OPEN, emptySet(), false, purpose).also {
                            save(state, it); state.put("active", it.token)
                        }
                    }
                }
                sessionToken = session.token
                launchPending = session.token
                val ownedScope = createScope(rt, activity, "feedback.open.${session.token}", purpose)
                scope = ownedScope
                ownedScope.start()
                ownedScope.dispatch { current -> onMain launch@{
                    if (current == null || runtime !== rt || !enabled() || rt.config.revision != revision ||
                        launchPending != session.token || session(session.token)?.phase != FeedbackPhase.OPEN) {
                        cancelLaunch(rt, session.token, ownedScope); event("skipped", "launch_gate_changed"); return@launch
                    }
                    // Consume only after all synchronous handoff observers and the final UI gate.
                    if (entry != null && !rt.entries.consume(entry.token)) {
                        cancelLaunch(rt, session.token, ownedScope); event("skipped", "entry_consumed"); return@launch
                    }
                    val intent = Intent(current, RetentionFeedbackActivity::class.java).putExtra(EXTRA_SESSION, session.token)
                    try {
                        if (!options.launcher.launch(current, intent)) {
                            cancelLaunch(rt, session.token, ownedScope); event("failed", "activity_unavailable")
                        } else {
                            event("requested")
                            main.postDelayed({ if (launchPending == session.token) {
                                cancelLaunch(rt, session.token, ownedScope); event("failed", "activity_start_timeout")
                            } }, 10_000)
                        }
                    } catch (error: Exception) {
                        cancelLaunch(rt, session.token, ownedScope); diagnostic("show", error); event("failed", "activity_exception")
                    }
                } }
            } catch (error: Exception) {
                sessionToken?.let { cancelLaunch(rt, it, scope) } ?: scope?.close()
                diagnostic("show", error)
                event("failed", "activity_exception")
            } finally { acquired.lease.close() }
        }
        return FeedbackShowResult.Scheduled
    }

    fun session(token: String): FeedbackSession? = runtime?.let { rt ->
        val state = decode(rt.store.snapshot(STATE).string(token)) ?: return@let null
        if (rt.clock.wallTimeMillis() >= state.createdAtMillis + SESSION_TTL) null else state
    }
    internal fun content(): FeedbackContent {
        val rt = checkNotNull(runtime)
        val value = options.contentProvider.content(rt.localizedContext())
        require(value.reasons.size <= 10 && value.reasons.all { ID_PATTERN.matches(it.id) && it.label.isNotBlank() })
        require(value.reasons.map { it.id }.distinct().size == value.reasons.size)
        require(listOf(value.title, value.keepLabel, value.continueLabel, value.shortcutLabel).all { it.isNotBlank() })
        return value.copy(reasons = if (rt.config.boolean("feedback.show_reasons", options.showReasons)) value.reasons else emptyList())
    }
    internal fun features(): List<RetentionFeature> = runtime?.features().orEmpty().filter { options.featureIds.isEmpty() || it.id in options.featureIds }.take(4)

    internal fun trackUi(activity: Activity) { visible = WeakReference(activity) }
    internal fun releaseUi(activity: Activity) { if (visible.get() === activity) visible.clear() }

    internal fun cancelReadiness(controller: FeedbackController) {
        controller.pause()
        val rt = runtime ?: return
        cancelLaunch(rt, controller.sessionToken, scopes["feedback.open.${controller.sessionToken}"])
        event("failed", "ui_readiness_timeout")
    }

    internal fun activate(controller: FeedbackController): FeedbackActivation {
        val rt = runtime ?: return FeedbackActivation.TERMINAL
        val activity = controller.activity() ?: return FeedbackActivation.TERMINAL
        scopes["feedback.open.${controller.sessionToken}"]?.close()
        if (launchPending == controller.sessionToken) launchPending = null
        val current = session(controller.sessionToken) ?: return FeedbackActivation.TERMINAL
        if (!enabled() || current.phase != FeedbackPhase.OPEN) return FeedbackActivation.TERMINAL
        if (rt.activities.current() !== activity) return FeedbackActivation.WAITING
        // Readiness signals may arrive while an existing lease is still valid. Do not replace its
        // host resource or recursively acquire another lease just to acknowledge such a signal.
        if (controller.lease?.activity() === activity) return FeedbackActivation.READY
        controller.pause()
        val acquired = rt.ui.acquire("feedback.session", 60_000, current.uiPurpose) as? RetentionUiLeaseResult.Acquired
            ?: return FeedbackActivation.WAITING
        if (runtime !== rt || !enabled() || session(current.token)?.phase != FeedbackPhase.OPEN) {
            acquired.lease.close()
            return FeedbackActivation.TERMINAL
        }
        controller.lease = acquired.lease
        if (!current.shown) {
            rt.store.transaction(STATE) { state -> decode(state.string(current.token))?.let { save(state, it.copy(shown = true)) } }
            event("shown")
        }
        return FeedbackActivation.READY
    }

    internal fun selectReason(controller: FeedbackController, reason: String, selected: Boolean): FeedbackActionResult = action(controller) {
        if (content().reasons.none { it.id == reason }) return@action FeedbackActionResult.Blocked("unknown_reason")
        val rt = checkNotNull(runtime)
        rt.store.transaction(STATE) { state ->
            val old = decode(state.string(controller.sessionToken)) ?: return@transaction
            val values = old.selectedReasons.toMutableSet().apply { if (selected) add(reason) else remove(reason) }
            save(state, old.copy(selectedReasons = values))
        }
        rt.emit(RetentionEvent("retention_feedback_reason", mapOf("reason_id" to reason, "selected" to selected.toString())))
        FeedbackActionResult.Applied
    }
    internal fun keep(controller: FeedbackController): FeedbackActionResult = action(controller) {
        if (!terminal(controller.sessionToken, FeedbackPhase.KEPT)) return@action FeedbackActionResult.Blocked("already_handled")
        event("kept")
        controller.activity()?.finish()
        controller.pause()
        FeedbackActionResult.Applied
    }
    internal fun tryFeature(controller: FeedbackController, featureId: String): FeedbackActionResult = action(controller) {
        val rt = checkNotNull(runtime)
        if (features().none { it.id == featureId }) return@action FeedbackActionResult.Blocked("unknown_feature")
        val entry = RetentionEntry(RetentionEntrySource.FEEDBACK, featureId, "try_feature", instanceId = controller.sessionToken, createdAtMillis = rt.clock.wallTimeMillis())
        val intent = rt.createEntryIntent(entry) ?: return@action FeedbackActionResult.Failed("route_unavailable")
        handoff(controller, intent, FeedbackPhase.FEATURE_HANDOFF, "feature_handoff", finishSource = true)
    }
    internal fun continueToSystem(controller: FeedbackController): FeedbackActionResult = action(controller) {
        val rt = checkNotNull(runtime)
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", rt.application.packageName, null))
        val confirmation = options.systemAction == FeedbackSystemAction.UNINSTALL_CONFIRMATION
        @Suppress("DEPRECATION")
        val intent = if (confirmation) Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", rt.application.packageName, null)) else appInfo
        handoff(controller, intent, FeedbackPhase.SYSTEM_HANDOFF, "system_handoff", finishSource = false,
            fallback = appInfo.takeIf { confirmation && options.appManagementFallback })
    }
    internal fun continueToAppManagement(controller: FeedbackController): FeedbackActionResult = action(controller) {
        val rt = checkNotNull(runtime)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", rt.application.packageName, null))
        handoff(controller, intent, FeedbackPhase.SYSTEM_HANDOFF, "system_handoff", finishSource = false)
    }

    private fun handoff(controller: FeedbackController, intent: Intent, phase: FeedbackPhase, eventName: String, finishSource: Boolean, fallback: Intent? = null): FeedbackActionResult {
        val rt = runtime ?: return FeedbackActionResult.Blocked("not_attached")
        val activity = controller.lease?.activity() ?: return FeedbackActionResult.Blocked("activity_unavailable")
        val revision = rt.config.revision
        if (!terminal(controller.sessionToken, phase)) return FeedbackActionResult.Blocked("already_handled")
        // The final Activity was obtained before this signal intentionally revokes the lease.
        val scope = createScope(rt, activity, "feedback.action.${UUID.randomUUID()}", controller.state()?.uiPurpose ?: RetentionUiPurpose.PROMPT)
        var outcome: FeedbackActionResult = FeedbackActionResult.Applied // Accepted if signal dispatch is still queued.
        scope.start()
        scope.dispatch { current ->
            if (current == null || runtime !== rt || !enabled() || rt.config.revision != revision || session(controller.sessionToken)?.phase != phase) {
                restoreHandoff(rt, controller, phase, scope)
                event("skipped", "handoff_gate_changed")
                outcome = FeedbackActionResult.Blocked("handoff_gate_changed")
            } else {
                var launchedAction = intent.action
                var submitted = try { options.launcher.launch(current, intent) } catch (_: Exception) { false }
                // A failed platform launch may reenter. Fallback still needs this exact scope/session.
                if (!submitted && fallback != null && scope.activity() === current && runtime === rt && enabled() &&
                    rt.config.revision == revision && session(controller.sessionToken)?.phase == phase) {
                    submitted = try { options.launcher.launch(current, fallback) } catch (_: Exception) { false }
                    launchedAction = fallback.action
                }
                if (!submitted) {
                    restoreHandoff(rt, controller, phase, scope)
                    event("failed", "handoff_failed")
                    outcome = FeedbackActionResult.Failed("handoff_failed")
                } else {
                    if (phase == FeedbackPhase.SYSTEM_HANDOFF) rt.emit(RetentionEvent("retention_feedback_system_handoff",
                        mapOf("action" to if (launchedAction == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) "app_management" else "uninstall_confirmation")))
                    else event(eventName)
                    controller.pause()
                    if (finishSource) current.finish()
                }
            }
        }
        return outcome
    }
    private fun cancelLaunch(rt: RetentionRuntime, token: String, scope: RetentionHandoffScope?) {
        try {
            if (runtime !== rt) return
            rt.store.transaction(STATE) { state ->
                decode(state.string(token))?.takeIf { it.phase == FeedbackPhase.OPEN }?.let { save(state, it.copy(phase = FeedbackPhase.CANCELLED)) }
                if (state.string("active") == token) state.remove("active")
            }
        } finally {
            if (launchPending == token) launchPending = null
            scope?.close()
        }
    }
    private fun restoreHandoff(rt: RetentionRuntime, controller: FeedbackController, phase: FeedbackPhase, scope: RetentionHandoffScope) {
        try {
            if (runtime !== rt) return
            rt.store.transaction(STATE) { state ->
                decode(state.string(controller.sessionToken))?.takeIf { it.phase == phase }?.let { old ->
                    val restored = if (runtime === rt && enabled()) FeedbackPhase.OPEN else FeedbackPhase.CANCELLED
                    save(state, old.copy(phase = restored))
                    if (restored == FeedbackPhase.OPEN) state.put("active", old.token)
                }
            }
        } finally { scope.close() }
        if (runtime === rt && enabled()) controller.activate()
    }
    private fun terminal(token: String, phase: FeedbackPhase): Boolean = runtime?.store?.transaction(STATE) { state ->
        val old = decode(state.string(token))
        if (old?.phase != FeedbackPhase.OPEN) false else {
            save(state, old.copy(phase = phase))
            if (state.string("active") == token) state.remove("active")
            true
        }
    } ?: false
    private fun action(controller: FeedbackController, block: () -> FeedbackActionResult): FeedbackActionResult {
        if (Looper.myLooper() != Looper.getMainLooper()) return FeedbackActionResult.Blocked("main_thread_required")
        if (runtime == null || !enabled() || controller.lease?.activity() !== controller.activity() || controller.activity() == null || session(controller.sessionToken)?.phase != FeedbackPhase.OPEN) return FeedbackActionResult.Blocked("inactive_session")
        return try { block() } catch (error: Exception) { diagnostic("action", error); FeedbackActionResult.Failed("action_failed") }
    }

    private fun updateShortcut() {
        val rt = runtime ?: return
        if (Build.VERSION.SDK_INT < 25) return
        val manager = rt.application.getSystemService(ShortcutManager::class.java) ?: return
        if (!enabled() || !rt.config.boolean("feedback.shortcut_enabled", options.shortcutEnabled)) {
            manager.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            manager.disableShortcuts(listOf(SHORTCUT_ID))
            return
        }
        val entry = RetentionEntry(RetentionEntrySource.SHORTCUT, DESTINATION, "open_feedback", campaignId = "feedback", mode = RetentionEntryMode.REUSABLE, createdAtMillis = rt.clock.wallTimeMillis())
        val intent = rt.createEntryIntent(entry)?.apply { if (action == null) action = Intent.ACTION_VIEW } ?: return
        val launcher = rt.application.packageManager.getLaunchIntentForPackage(rt.application.packageName)?.component ?: intent.component ?: return
        val foreign = (manager.dynamicShortcuts + manager.manifestShortcuts).filter { it.id != SHORTCUT_ID && (it.activity == launcher || it.activity == null) }.distinctBy { it.id }
        if (foreign.size >= manager.maxShortcutCountPerActivity) { manager.removeDynamicShortcuts(listOf(SHORTCUT_ID)); event("skipped", "shortcut_quota"); return }
        val shortcut = ShortcutInfo.Builder(rt.application, SHORTCUT_ID).setActivity(launcher)
            .setShortLabel(content().shortcutLabel.take(40))
            .setIcon(Icon.createWithResource(rt.application, options.shortcutIconRes))
            .setIntent(intent).build()
        manager.enableShortcuts(listOf(SHORTCUT_ID))
        if (!manager.addDynamicShortcuts(listOf(shortcut))) event("skipped", "shortcut_rejected")
    }
    private fun createScope(rt: RetentionRuntime, activity: Activity, token: String, purpose: RetentionUiPurpose = RetentionUiPurpose.PROMPT): RetentionHandoffScope {
        lateinit var scope: RetentionHandoffScope
        val closed = { if (scopes[token] === scope) scopes.remove(token); Unit }
        scope = if (purpose == RetentionUiPurpose.ENTRY) RetentionHandoffScope.forEntry(rt, activity, token, "feedback_handoff", onClosed = closed)
            else RetentionHandoffScope(rt, activity, token, "feedback_handoff", onClosed = closed)
        scopes[token] = scope
        return scope
    }
    private fun enabled() = runtime?.config?.boolean("feedback.enabled", options.enabled) == true
    private fun event(suffix: String, reason: String? = null) { runtime?.emit(RetentionEvent("retention_feedback_$suffix", if (reason == null) emptyMap() else mapOf("reason" to reason))) }
    internal fun diagnostic(where: String, error: Exception) { runtime?.diagnostics?.record("feedback.$where", error.message ?: where, RetentionDiagnosticLevel.ERROR, error) }
    private fun onMain(block: () -> Unit) {
        val guarded = Runnable { try { block() } catch (error: Exception) { diagnostic("module", error); launchPending = null } }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else main.post(guarded)
    }
    override fun shutdown() {
        runtime = null
        if (attached === this) attached = null
        onMain {
            main.removeCallbacksAndMessages(null)
            scopes.values.toList().forEach { it.close() }; scopes.clear()
            visible.get()?.finish(); visible.clear(); launchPending = null
        }
    }
    private fun prune(state: RetentionTransaction) {
        val now = checkNotNull(runtime).clock.wallTimeMillis()
        state.entries().filterKeys { it != "active" }.forEach { (key, value) ->
            val session = decode(value)
            if (session == null || now >= session.createdAtMillis + SESSION_TTL) state.remove(key)
        }
        state.string("active")?.let { if (state.string(it) == null) state.remove("active") }
    }
    companion object {
        const val DESTINATION = "retention.feedback"
        const val SHORTCUT_ID = "retention.feedback.open"
        internal const val EXTRA_SESSION = "io.retentionkit.feedback.session"
        private const val ENTRY_SCOPE = "feedback.entry.open"
        private const val STATE = "feedback.sessions.v1"
        private const val SESSION_TTL = 1_800_000L
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        @Volatile private var attached: RetentionFeedbackModule? = null
        @JvmStatic fun get(): RetentionFeedbackModule? = attached
        private fun decode(raw: String?): FeedbackSession? = try {
            if (raw == null) null else JSONObject(raw).let { json ->
                val reasons = json.getJSONArray("reasons")
                FeedbackSession(json.getString("token"), json.getLong("created"), FeedbackPhase.valueOf(json.getString("phase")), (0 until reasons.length()).map { reasons.getString(it) }.toSet(), json.optBoolean("shown", false), runCatching { RetentionUiPurpose.valueOf(json.optString("purpose")) }.getOrDefault(RetentionUiPurpose.PROMPT))
            }
        } catch (_: Exception) { null }
        private fun save(state: RetentionTransaction, value: FeedbackSession) {
            state.put(value.token, JSONObject().put("token", value.token).put("created", value.createdAtMillis).put("phase", value.phase.name).put("reasons", JSONArray(value.selectedReasons.toList())).put("shown", value.shown).put("purpose", value.uiPurpose.name).toString())
        }
    }
}
