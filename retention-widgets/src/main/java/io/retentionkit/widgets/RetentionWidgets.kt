package io.retentionkit.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import io.retentionkit.core.*

/** Install this module once in Application.onCreate; all platform effects are serialized on main. */
class RetentionWidgets @JvmOverloads constructor(options: WidgetOptions = WidgetOptions()) : RetentionModule {
    private val options = options.copy(featureIds = options.featureIds.toList())
    override val id: String = "widgets"
    internal lateinit var runtime: RetentionRuntime; private set
    internal lateinit var platform: WidgetPlatform
    internal lateinit var state: WidgetState; private set
    internal lateinit var provider: ComponentName; private set
    internal val main = Handler(Looper.getMainLooper())
    private lateinit var pins: WidgetPinCoordinator
    private lateinit var shortcuts: FeatureShortcuts
    @Volatile internal var closed = true; private set

    override fun validateConfig(config: RetentionConfigSnapshot): List<String> = buildList {
        listOf("widgets.enabled", "widgets.shortcuts.enabled", "widgets.invitation.enabled").forEach { key ->
            if (config.values[key]?.toBooleanStrictOrNull() == null && key in config.values) add("$key must be true or false")
        }
        config.values["widgets.pin.timeout_ms"]?.let {
            if (it.toLongOrNull() !in 1_000L..300_000L) add("widgets.pin.timeout_ms must be 1000..300000")
        }
    }

    override fun attach(runtime: RetentionRuntime) {
        check(closed) { "Widget module is already attached" }
        this.runtime = runtime
        provider = ComponentName(runtime.application, options.providerClass)
        if (!::platform.isInitialized) platform = AndroidWidgetPlatform(runtime.application)
        state = WidgetState(runtime.store)
        pins = WidgetPinCoordinator(this)
        shortcuts = FeatureShortcuts(runtime, options)
        closed = false
        installed = this
        onMain { pins.restore() }
    }

    override fun onSignal(signal: RetentionSignal) = onMain {
        when (signal) {
            RetentionSignal.ProcessBackground -> pins.onBackground()
            RetentionSignal.ProcessForeground -> pins.onForeground()
            is RetentionSignal.ConfigurationChanged -> {
                if (!enabled()) pins.disable()
                else if (!invitationEnabled()) pins.closeInvitation()
            }
            else -> Unit
        }
    }

    override fun reconcile(reason: String) = onMain {
        pins.expire()
        refreshWidgets()
        guard("shortcuts") { shortcuts.reconcile(enabled(), runtime.config.revision) }
    }

    override fun shutdown() {
        closed = true
        if (installed === this) installed = null
        val cleanup = Runnable { if (::pins.isInitialized) pins.shutdown(); main.removeCallbacksAndMessages(null) }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
    }

    /** Re-query before showing a CTA; launcher support can change between foreground visits. */
    fun pinCapability(): RetentionCapability {
        if (closed) return RetentionCapability.Unavailable("not_installed")
        if (!enabled()) return RetentionCapability.Unavailable("disabled")
        return try {
            if (platform.pinSupported()) RetentionCapability.Available else RetentionCapability.Unavailable("launcher_or_api_unsupported")
        } catch (error: Exception) {
            diagnose("pin_capability", error)
            RetentionCapability.Unknown("platform_error")
        }
    }

    /** Main-thread, user-initiated. Requested means accepted by the API, never added to the launcher. */
    fun requestPin(): WidgetPinResult = if (Looper.myLooper() != Looper.getMainLooper()) {
        WidgetPinResult.Failed("main_thread_required")
    } else if (closed) WidgetPinResult.Failed("not_installed") else try { pins.request() }
        catch (error: Exception) { diagnose("request", error); WidgetPinResult.Failed("state_error") }

    /** Built-in localized invitation. Dismissing this dialog is known; system cancellation is unknown. */
    fun showPinInvitation(): WidgetInvitationResult = if (Looper.myLooper() != Looper.getMainLooper()) {
        WidgetInvitationResult.Failed("main_thread_required")
    } else if (closed) WidgetInvitationResult.Failed("not_installed") else pins.showInvitation()

    fun pinStatus(token: String): WidgetPinResult? = if (closed) null else try {
        state.pin(token)?.let { pin ->
            if (pin.status == "pending" && runtime.clock.wallTimeMillis() >= pin.deadline) WidgetPinResult.Unknown(token, "timeout") else pin.result()
        }
    } catch (error: Exception) { diagnose("pin_status", error); null }

    /** Call after an in-app locale change. System locale changes are handled by the receiver. */
    fun refresh() = reconcile("host_refresh")

    /** Independent per-instance feature selection; empty selects the module's default first four. */
    fun configureInstance(appWidgetId: Int, featureIds: List<String>): Boolean {
        if (closed || Looper.myLooper() != Looper.getMainLooper()) return false
        if (featureIds.size > 4 || featureIds.distinct().size != featureIds.size) return false
        return try {
            if (!owns(appWidgetId) || !runtime.features().map { it.id }.containsAll(featureIds)) return false
            val old = state.instance(appWidgetId) ?: WidgetInstance(appWidgetId, emptyList())
            state.save(old.copy(featureIds = featureIds.toList()))
            updateInstance(appWidgetId)
            true
        } catch (error: Exception) { diagnose("configure", error); false }
    }

    fun instances(): List<WidgetInstance> = if (closed) emptyList() else try {
        val ids = platform.ids(provider).toSet()
        state.instances().filter { it.appWidgetId in ids }
    } catch (error: Exception) { diagnose("instances", error); emptyList() }

    internal fun enabled(): Boolean = !closed && runtime.config.boolean("widgets.enabled", true)
    internal fun invitationEnabled(): Boolean = enabled() && runtime.config.boolean("widgets.invitation.enabled", true)
    internal fun current(revision: Long): Boolean = enabled() && runtime.config.revision == revision
    internal fun owns(appWidgetId: Int): Boolean = appWidgetId > 0 && platform.providerFor(appWidgetId) == provider && appWidgetId in platform.ids(provider)
    internal fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) guard("operation", action)
        else main.post { if (!closed) guard("operation", action) }
    }
    internal fun guard(component: String, action: () -> Unit): Boolean = try { action(); true }
        catch (error: Exception) { diagnose(component, error); false }
    internal fun diagnose(component: String, error: Exception) = runtime.diagnostics.record("widgets.$component", error.message ?: "Failure", RetentionDiagnosticLevel.ERROR, error)
    internal fun event(name: String, attributes: Map<String, String> = emptyMap()) = runtime.emit(RetentionEvent("retention_widget_$name", attributes))

    internal fun receivePin(token: String, appWidgetId: Int) = onMain { pins.confirm(token, appWidgetId) }
    internal fun update(ids: IntArray) = onMain { ids.forEach { id -> guard("update_$id") { updateInstance(id) } } }
    internal fun delete(ids: IntArray) = onMain { state.delete(ids); event("deleted", mapOf("count" to ids.size.toString())) }
    internal fun restore(oldIds: IntArray, newIds: IntArray) = onMain {
        require(oldIds.size == newIds.size && newIds.all { it > 0 })
        state.restore(oldIds, newIds)
        newIds.forEach { guard("restore_$it") { updateInstance(it) } }
        event("restored", mapOf("count" to newIds.size.toString()))
    }

    private fun refreshWidgets() {
        // Launcher is authoritative. Do not delete saved instances here: APPWIDGET_RESTORED can
        // follow Application startup with old IDs needed for the restore mapping.
        platform.ids(provider).forEach { guard("render_$it") { updateInstance(it) } }
    }
    private fun updateInstance(id: Int) {
        if (!owns(id)) return
        val revision = runtime.config.revision
        val bundle = platform.options(id)
        val old = state.instance(id) ?: WidgetInstance(id, emptyList())
        val instance = old.copy(
            minWidthDp = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, old.minWidthDp).coerceAtLeast(0),
            minHeightDp = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, old.minHeightDp).coerceAtLeast(0),
        )
        state.save(instance)
        val context = runtime.localizedContext()
        val catalogue = if (enabled()) runtime.features() else emptyList()
        val selected = instance.featureIds.ifEmpty { options.featureIds }
        val features = if (selected.isEmpty()) catalogue.take(4) else selected.mapNotNull { id -> catalogue.firstOrNull { it.id == id } }
        val actions = features.mapNotNull { feature ->
            val entry = RetentionEntry(RetentionEntrySource.WIDGET, feature.id, feature.id,
                instanceId = id.toString(), mode = RetentionEntryMode.REUSABLE, createdAtMillis = runtime.clock.wallTimeMillis())
            val intent = runtime.createEntryIntent(entry) ?: return@mapNotNull null
            WidgetAction(feature, PendingIntent.getActivity(runtime.application, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        val views = try {
            if (enabled()) options.renderer.render(context, instance, actions)
            else StandardWidgetRenderer().render(context, instance, emptyList())
        }
        catch (error: Exception) {
            diagnose("custom_renderer", error)
            StandardWidgetRenderer().render(context, instance, actions)
        }
        // A custom renderer/router may synchronously change configuration. Never publish its stale result.
        if (!closed && runtime.config.revision == revision && owns(id)) platform.update(id, views)
    }

    companion object {
        @Volatile private var installed: RetentionWidgets? = null
        @JvmStatic fun get(): RetentionWidgets? = installed
    }
}
