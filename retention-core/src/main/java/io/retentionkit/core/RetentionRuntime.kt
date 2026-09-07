package io.retentionkit.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/** Initialization is local-only. Configure from Application.onCreate for cold receiver/provider starts. */
data class RetentionOptions @JvmOverloads constructor(
    val modules: List<RetentionModule> = emptyList(),
    val featureProvider: RetentionFeatureProvider = RetentionFeatureProvider.EMPTY,
    val localeProvider: RetentionLocaleProvider = RetentionLocaleProvider.SYSTEM,
    val router: RetentionRouter = RetentionRouter.NONE,
    val eventSink: RetentionEventSink = RetentionEventSink.NONE,
    val clock: RetentionClock = RetentionClock.System,
    val store: RetentionStore? = null,
    val initialUserState: RetentionUserState = RetentionUserState(),
    val initialOverrides: Map<String, String> = emptyMap(),
    val uiHost: RetentionUiHost = RetentionUiHost.NONE,
)

sealed class RetentionInstallResult {
    data class Installed(val runtime: RetentionRuntime, val reused: Boolean = false) : RetentionInstallResult()
    data class Failed(val reasons: List<String>) : RetentionInstallResult()
}

class RetentionRuntime private constructor(val application: Application, private val options: RetentionOptions) {
    val clock: RetentionClock = options.clock
    val store: RetentionStore = options.store ?: SharedPreferencesRetentionStore(application)
    val diagnostics = RetentionDiagnostics(clock)
    val entries = RetentionEntries(store, clock)
    private val stateLock = Any()
    private val signalLock = Any()
    private val signalQueue = ArrayDeque<RetentionSignal>()
    private val afterSignals = ArrayDeque<() -> Unit>()
    private var dispatching = false
    private val modules = CopyOnWriteArrayList<RetentionModule>()
    private data class Listener(val owner: String, val callback: (RetentionSignal) -> Unit)
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var closed = false
    @Volatile var userState: RetentionUserState = RetentionUserState(); private set
    @Volatile var config: RetentionConfigSnapshot = RetentionConfigSnapshot(0, emptyMap()); private set
    @Volatile var isForeground: Boolean = false; private set
    private val tracker = RetentionActivityTracker(application, { signal(it) }, { ui.invalidate() }, diagnostics)
    val activities: ForegroundActivityProvider = tracker
    val ui = RetentionUiCoordinator(clock, activities, { isForeground && !closed }, { userState.onboardingActive }, options.uiHost) {
        diagnostics.record("core.ui_host", "Host UI adapter failed", RetentionDiagnosticLevel.ERROR, it)
    }

    private fun initialize(): List<String> {
        if (options.modules.map { it.id }.distinct().size != options.modules.size) return listOf("Duplicate module ID")
        if (options.modules.any { !validId(it.id) }) return listOf("Invalid module ID")
        val initialErrors = validateValues(options.initialOverrides)
        if (initialErrors.isNotEmpty()) return initialErrors
        userState = store.transaction("core.user") { state ->
            if (state.string("installed") == null) {
                val now = clock.wallTimeMillis()
                val initial = options.initialUserState.copy(
                    installedAtMillis = options.initialUserState.installedAtMillis.takeIf { it > 0 } ?: now,
                    lastActiveAtMillis = options.initialUserState.lastActiveAtMillis.takeIf { it > 0 } ?: now,
                    setupCompletedAtMillis = if (options.initialUserState.setupCompleted) options.initialUserState.setupCompletedAtMillis.takeIf { it > 0 } ?: now else 0,
                )
                writeUser(state, initial)
            }
            val restored = readUser(state).copy(entitlement = options.initialUserState.entitlement)
            writeUser(state, restored)
            restored
        }
        val saved = store.snapshot("core.config")
        config = RetentionConfigSnapshot(saved.long("__revision"),
            if (saved.string("__revision") == null) options.initialOverrides else saved.entries().filterKeys { it != "__revision" })
        val errors = validateConfiguration(config)
        if (errors.isNotEmpty()) return errors
        // Publish cached configuration durably before attach. A receiver never observes default
        // configuration followed by an asynchronous restore from disk/network.
        persistConfiguration(config)
        options.modules.forEach { module ->
            if (diagnostics.guard("${module.id}.attach") { module.attach(this) }) modules.add(module)
            else diagnostics.guard("${module.id}.shutdown_after_failed_attach") { module.shutdown() }
        }
        tracker.start()
        reconcile("install_restored")
        return emptyList()
    }

    /** Guarded and isolated; modules must log post_submitted rather than claiming UI was displayed. */
    fun emit(event: RetentionEvent) { diagnostics.guard("event_sink") { options.eventSink.onEvent(event.copy(attributes = event.attributes.toMap())) } }

    fun features(): List<RetentionFeature> {
        var result: List<RetentionFeature> = emptyList()
        diagnostics.guard("feature_provider") {
            val localized = options.localeProvider.localizedContext(application)
            val supplied = options.featureProvider.features(localized).toList()
            require(supplied.size <= 100) { "Feature catalogue exceeds 100 items" }
            require(supplied.all { validId(it.id) && it.label.isNotBlank() && it.label.length <= 200 && it.iconRes != 0 }) { "Invalid feature catalogue" }
            require(supplied.map { it.id }.distinct().size == supplied.size) { "Duplicate feature ID" }
            result = supplied
        }
        return result
    }

    fun localizedContext(): Context {
        var context: Context = application
        diagnostics.guard("locale_provider") { context = options.localeProvider.localizedContext(application) }
        return context
    }

    /** Uses a category digest for PendingIntent identity without overwriting host data/flags. */
    fun createEntryIntent(entry: RetentionEntry): Intent? {
        var result: Intent? = null
        diagnostics.guard("entry_router") {
            require(RetentionEntryCodec.validate(entry) == null) { "Invalid retention entry" }
            val intent = options.router.createIntent(application, entry) ?: return@guard
            require(intent.component?.packageName == application.packageName) { "Retention entry must target an explicit Activity in the host package" }
            result = RetentionEntryCodec.write(Intent(intent), entry)
        }
        return result
    }

    /** Subscription changes during callbacks affect the next dispatch, not the active snapshot. */
    fun subscribe(owner: String, listener: (RetentionSignal) -> Unit): RetentionSubscription {
        require(validId(owner))
        val subscription = Listener(owner, listener)
        synchronized(signalLock) { if (!closed) listeners.add(subscription) }
        return RetentionSubscription { listeners.remove(subscription) }
    }

    /** A single drainer serializes callbacks without holding the queue lock while invoking clients. */
    fun signal(signal: RetentionSignal): Boolean {
        synchronized(signalLock) {
            if (closed) return false
            signalQueue.addLast(signal)
            if (dispatching) return true
            dispatching = true
        }
        try {
            while (true) {
                var continuations: List<() -> Unit> = emptyList()
                val next = synchronized(signalLock) {
                    if (closed || signalQueue.isEmpty()) {
                        dispatching = false
                        continuations = afterSignals.toList()
                        afterSignals.clear()
                        null
                    } else signalQueue.removeFirst()
                }
                if (next == null) {
                    continuations.forEach { diagnostics.guard("core.after_signal") { it() } }
                    return true
                }
                if (!diagnostics.guard("core.signal_state") { applySignal(next) }) continue
                val moduleSnapshot = modules.toList()
                val listenerSnapshot = listeners.toList()
                moduleSnapshot.forEach { module -> if (!closed) diagnostics.guard("${module.id}.signal") { module.onSignal(next) } }
                listenerSnapshot.forEach { listener -> if (!closed) diagnostics.guard("${listener.owner}.listener") { listener.callback(next) } }
                if (next is RetentionSignal.ConfigurationChanged || next is RetentionSignal.EntitlementChanged ||
                    next == RetentionSignal.SetupCompleted || next == RetentionSignal.ProcessForeground) reconcile(next.javaClass.simpleName)
            }
        } catch (fatal: Throwable) {
            // Release the queue if a VM/programmer Error escapes; do not swallow the error.
            synchronized(signalLock) { dispatching = false; signalQueue.clear(); afterSignals.clear() }
            throw fatal
        }
    }

    /** Internal handoff barrier; never waits for, or calls clients under, the signal lock. */
    internal fun afterSignalDispatch(action: () -> Unit): Boolean {
        val deferred = synchronized(signalLock) {
            if (closed || afterSignals.size >= 128) return false
            if (dispatching) { afterSignals.addLast(action); true } else false
        }
        if (!deferred) action()
        return true
    }

    fun reconcile(reason: String) {
        if (closed) return
        modules.toList().forEach { module -> if (!closed) diagnostics.guard("${module.id}.reconcile") { module.reconcile(reason) } }
    }

    /** Atomic PATCH semantics. Removing a key explicitly restores the module's built-in default. */
    @JvmOverloads fun updateConfig(overrides: Map<String, String>, removeKeys: Set<String> = emptySet()): RetentionConfigResult =
        updateConfigInternal(overrides, removeKeys, null)

    internal fun updateConfigAtRevision(overrides: Map<String, String>, removeKeys: Set<String>, expectedRevision: Long): RetentionConfigResult =
        updateConfigInternal(overrides, removeKeys, expectedRevision)

    private fun updateConfigInternal(overrides: Map<String, String>, removeKeys: Set<String>, expectedRevision: Long?): RetentionConfigResult {
        val patch = overrides.toMap()
        val removals = removeKeys.toSet()
        repeat(16) {
            if (closed) return RetentionConfigResult.Rejected(listOf("Runtime is shut down"))
            val previous = config
            if (expectedRevision != null && expectedRevision != previous.revision) return RetentionConfigResult.Rejected(listOf("stale_revision"))
            val values = previous.values.toMutableMap().apply { removals.forEach { remove(it) }; putAll(patch) }
            val candidate = RetentionConfigSnapshot(previous.revision + 1, values)
            // Validators are partner code: never invoke them under the state lock. They may run
            // again when another writer wins; they must be pure and must not perform effects.
            val errors = validateValues(values) + validateConfiguration(candidate)
            if (errors.isNotEmpty()) return RetentionConfigResult.Rejected(errors)
            val committed = synchronized(stateLock) {
                if (closed) return RetentionConfigResult.Rejected(listOf("Runtime is shut down"))
                if (config.revision != previous.revision) false
                else {
                    try { persistConfiguration(candidate) } catch (error: Exception) {
                        diagnostics.record("core.config", "Configuration persistence failed", RetentionDiagnosticLevel.ERROR, error)
                        return RetentionConfigResult.Rejected(listOf("Configuration persistence failed"))
                    }
                    config = candidate
                    true
                }
            }
            if (committed) {
                signal(RetentionSignal.ConfigurationChanged(candidate.revision))
                return RetentionConfigResult.Applied(candidate.revision)
            }
        }
        return RetentionConfigResult.Rejected(listOf("Configuration changed concurrently; retry the patch"))
    }

    /** Common marketing gates. Modules must also check permission/channel/cap/TTL at commit time. */
    @JvmOverloads fun marketingEligibility(graceMillis: Long = 86_400_000, requireBackground: Boolean = true, phase: RetentionMarketingPhase = RetentionMarketingPhase.AFTER_SETUP): RetentionEligibility {
        val state = userState
        val graceStart = if (phase == RetentionMarketingPhase.ONBOARDING) state.installedAtMillis else state.setupCompletedAtMillis
        val reason = when {
            closed -> RetentionSuppressionReason.NOT_INSTALLED
            phase == RetentionMarketingPhase.AFTER_SETUP && !state.setupCompleted -> RetentionSuppressionReason.SETUP_INCOMPLETE
            phase == RetentionMarketingPhase.AFTER_SETUP && state.onboardingActive -> RetentionSuppressionReason.HOST_UI
            phase == RetentionMarketingPhase.ONBOARDING && (state.setupCompleted || !state.onboardingActive) -> RetentionSuppressionReason.WRONG_PHASE
            state.entitlement == RetentionEntitlement.UNKNOWN -> RetentionSuppressionReason.ENTITLEMENT_UNKNOWN
            state.entitlement == RetentionEntitlement.SUBSCRIBER -> RetentionSuppressionReason.SUBSCRIBER
            clock.wallTimeMillis() < graceStart || clock.wallTimeMillis() - graceStart < graceMillis.coerceAtLeast(0) -> RetentionSuppressionReason.COOLDOWN
            ui.externalTransitionActive() -> RetentionSuppressionReason.EXTERNAL_TRANSITION
            requireBackground && isForeground -> RetentionSuppressionReason.FOREGROUND
            else -> null
        }
        return if (reason == null) RetentionEligibility.Allowed else RetentionEligibility.Blocked(reason)
    }

    private fun applySignal(signal: RetentionSignal) {
        synchronized(stateLock) {
            val changed = when (signal) {
                RetentionSignal.SetupCompleted -> userState.copy(setupCompleted = true, onboardingActive = false,
                    setupCompletedAtMillis = if (userState.setupCompleted) userState.setupCompletedAtMillis else clock.wallTimeMillis())
                is RetentionSignal.OnboardingChanged -> userState.copy(onboardingActive = signal.active)
                is RetentionSignal.EntitlementChanged -> userState.copy(entitlement = signal.entitlement)
                RetentionSignal.ProcessForeground, RetentionSignal.ProcessBackground -> userState.copy(lastActiveAtMillis = maxOf(userState.lastActiveAtMillis, clock.wallTimeMillis()))
                is RetentionSignal.BusinessSuccess -> userState.copy(lastActiveAtMillis = maxOf(userState.lastActiveAtMillis, clock.wallTimeMillis()))
                else -> userState
            }
            if (changed != userState) {
                store.transaction("core.user") { writeUser(it, changed) }
                userState = changed
            }
        }
        when (signal) {
            RetentionSignal.ProcessForeground -> isForeground = true
            RetentionSignal.ProcessBackground -> { isForeground = false; ui.invalidate() }
            is RetentionSignal.OnboardingChanged -> if (signal.active) ui.invalidate()
            is RetentionSignal.ExternalTransitionStarted -> ui.setBlock("external:${signal.token}", RetentionSuppressionReason.EXTERNAL_TRANSITION, signal.durationMillis)
            is RetentionSignal.ExternalTransitionFinished -> ui.removeBlock("external:${signal.token}")
            is RetentionSignal.HostUiChanged -> if (signal.visible) ui.setBlock("host:${signal.owner}", RetentionSuppressionReason.HOST_UI, signal.durationMillis) else ui.removeBlock("host:${signal.owner}")
            else -> Unit
        }
    }

    private fun validateConfiguration(snapshot: RetentionConfigSnapshot): List<String> = options.modules.flatMap { module ->
        try { module.validateConfig(snapshot).map { "${module.id}: $it" } }
        catch (error: Exception) {
            diagnostics.record("${module.id}.validate", "Validator threw", RetentionDiagnosticLevel.ERROR, error)
            listOf("${module.id}: validator failed")
        }
    }
    private fun persistConfiguration(snapshot: RetentionConfigSnapshot) = store.transaction("core.config") { state ->
        state.clear()
        snapshot.values.forEach { (key, value) -> state.put(key, value) }
        state.put("__revision", snapshot.revision)
    }
    private fun shutdown() {
        val closing = synchronized(signalLock) {
            if (closed) return
            closed = true
            signalQueue.clear()
            afterSignals.clear()
            modules.toList().asReversed().also { modules.clear(); listeners.clear() }
        }
        tracker.stop()
        ui.shutdown()
        closing.forEach { module -> diagnostics.guard("${module.id}.shutdown") { module.shutdown() } }
    }

    companion object {
        private val installLock = Any()
        @Volatile private var installed: RetentionRuntime? = null
        @JvmStatic fun get(): RetentionRuntime? = installed

        /** Repeated install reuses the first runtime/options; publish only after restore and attach. */
        @JvmStatic fun install(application: Application, options: RetentionOptions): RetentionInstallResult = synchronized(installLock) {
            installed?.let { return RetentionInstallResult.Installed(it, reused = true) }
            if (!isMainProcess(application)) return RetentionInstallResult.Failed(listOf("RetentionKit supports the main app process only"))
            var runtime: RetentionRuntime? = null
            try {
                runtime = RetentionRuntime(application, options.copy(modules = options.modules.toList(), initialOverrides = options.initialOverrides.toMap()))
                val errors = runtime.initialize()
                if (errors.isNotEmpty()) { runtime.shutdown(); return RetentionInstallResult.Failed(errors) }
                installed = runtime
                RetentionInstallResult.Installed(runtime)
            } catch (error: Exception) {
                runtime?.shutdown()
                RetentionInstallResult.Failed(listOf("${error.javaClass.simpleName}: ${error.message ?: "Initialization failed"}"))
            }
        }

        /** Does not erase persisted state. For tests only; an app should install once per process. */
        @JvmStatic fun uninstallForTests() = synchronized(installLock) { installed?.shutdown(); installed = null }

        private fun isMainProcess(application: Application): Boolean {
            val expected = application.applicationInfo.processName ?: application.packageName
            val actual = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
                (application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
                    ?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
            return actual == expected
        }
        private fun validateValues(values: Map<String, String>): List<String> = when {
            values.size > 1000 -> listOf("Too many config values")
            values.any { (key, value) -> key.startsWith("__") || !validId(key) || value.length > 8192 } -> listOf("Invalid config key/value")
            else -> emptyList()
        }
        private fun writeUser(state: RetentionTransaction, user: RetentionUserState) {
            state.put("setup", user.setupCompleted)
            state.put("setup_at", user.setupCompletedAtMillis)
            state.put("onboarding", user.onboardingActive)
            state.put("entitlement", user.entitlement.name)
            state.put("installed", user.installedAtMillis)
            state.put("active", user.lastActiveAtMillis)
        }
        private fun readUser(state: RetentionState) = RetentionUserState(
            setupCompleted = state.boolean("setup"), onboardingActive = state.boolean("onboarding"),
            entitlement = state.string("entitlement")?.let { runCatching { RetentionEntitlement.valueOf(it) }.getOrNull() } ?: RetentionEntitlement.UNKNOWN,
            installedAtMillis = state.long("installed"), lastActiveAtMillis = state.long("active"),
            setupCompletedAtMillis = state.long("setup_at", if (state.boolean("setup")) state.long("installed") else 0),
        )
    }
}
