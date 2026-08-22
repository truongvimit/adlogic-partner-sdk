package io.paykit

import android.app.Activity
import android.app.Application
import io.paykit.billing.BillingBridge
import io.paykit.config.ConfigOrigin
import io.paykit.config.PaywallConfig
import io.paykit.config.PaywallConfigSnapshot
import io.paykit.config.PaywallConfigSource
import io.paykit.config.PaywallConfigStore
import io.paykit.internal.PayKitLog
import io.paykit.internal.PayKitPrefs
import io.paykit.internal.SingleClick
import io.paykit.ui.PaywallRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PayKit entry point — the only type a host has to touch.
 *
 * ```
 * PayKit.install(app, config)                  // config from payKitConfig { … }
 * PayKit.configSource(FirebaseConfigSource())  // optional, from :paykit-firebase
 * PayKit.sync()                                // from splash, inside a coroutine
 * PayKit.launch(activity, PaywallPlacement.AFTER_ONBOARDING)
 * ```
 */
object PayKit {

    private val installed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<PaywallListener>()

    private val _state = MutableStateFlow<PayKitState>(PayKitState.Idle)
    val state: StateFlow<PayKitState> = _state.asStateFlow()

    @Volatile
    private var application: Application? = null

    @Volatile
    private var config: PayKitConfig? = null

    @Volatile
    private var configStore: PaywallConfigStore? = null

    @Volatile
    private var prefs: PayKitPrefs? = null

    @Volatile
    private var installedSource: PaywallConfigSource? = null

    @Volatile
    private var customRenderer: PaywallRenderer? = null

    /** Keyed by presentation, so a re-created screen finds the listener its launch was given. */
    private val pendingListeners = ConcurrentHashMap<String, PaywallListener>()

    private val presentations = AtomicLong()

    @Volatile
    private var launchGate: SingleClick = SingleClick()

    /** Idempotent, synchronous and offline: no network and no Play call. Call from onCreate. */
    @JvmStatic
    fun install(app: Application, config: PayKitConfig) {
        if (!installed.compareAndSet(false, true)) {
            PayKitLog.w("install() called twice — ignoring")
            return
        }
        PayKitLog.level = config.logLevel
        application = app
        this.config = config
        prefs = PayKitPrefs(app)
        launchGate = singleClick()

        val store = PaywallConfigStore(app, config.fallbackConfigRes)
        configStore = store
        installedSource?.let(store::source)
        adopt(app, store.snapshot.value)
        if (store.origin == ConfigOrigin.BUNDLE && config.fallbackConfigRes == 0) {
            // Sample data: those ids exist in no Play console, so every price is blank and every
            // CTA fails until a fetch lands or fallbackConfigRes names the partner's catalogue.
            PayKitLog.w("no fallbackConfigRes — running on PayKit's own sample catalogue")
        }
        PayKitLog.i("PayKit ${BuildConfig.SDK_VERSION} installed from ${store.origin}")
    }

    /** Vendor-neutral; `:paykit-firebase` ships the only adapter this repo has. */
    @JvmStatic
    fun configSource(source: PaywallConfigSource) {
        installedSource = source
        configStore?.source(source)
    }

    /** The only call that fetches. Returns false on timeout or error, keeping the last snapshot. */
    @JvmStatic
    suspend fun sync(timeoutMs: Long = 5_000): Boolean {
        val app = application
        val store = configStore
        if (app == null || store == null) {
            PayKitLog.w("sync() before install() — ignoring")
            return false
        }
        _state.value = PayKitState.Syncing
        val fetched = runCatching { store.sync(timeoutMs) }
            .onFailure { PayKitLog.e("sync failed: ${it.message}", it) }
            .getOrDefault(false)
        adopt(app, store.snapshot.value)
        return fetched
    }

    /** For a host that runs its own remote config and only hands PayKit the resulting document. */
    @JvmStatic
    fun applySnapshot(json: String) {
        val app = application
        val store = configStore
        if (app == null || store == null) {
            PayKitLog.w("applySnapshot() before install() — ignoring")
            return
        }
        val applied = runCatching { store.applySnapshot(json) }
            .onFailure { PayKitLog.e("applySnapshot failed: ${it.message}", it) }
            .getOrDefault(false)
        if (!applied) PayKitLog.w("applySnapshot rejected the document — previous config kept")
        adopt(app, store.snapshot.value)
    }

    /**
     * Whether [placement] may show a paywall.
     *
     * Fail-closed: an empty set shows nothing, so a half-configured partner gets no paywall
     * rather than a broken one.
     */
    @JvmStatic
    fun isEnabled(placement: PaywallPlacement): Boolean {
        val store = configStore
        // Only a fetched document decides where the paywall shows. A bundled one is a catalogue
        // fallback, so letting it name placements would hand that policy to whoever shipped the
        // JSON rather than to the host, defeating the fail-closed default.
        val fetched = store?.origin == ConfigOrigin.REMOTE || store?.origin == ConfigOrigin.CACHE
        val allowed = store?.config?.value?.placements
            ?.takeIf { fetched && it.isNotEmpty() }
            ?: config?.defaultPlacements.orEmpty()
        return placement in allowed
    }

    @JvmStatic
    fun isReady(): Boolean = _state.value is PayKitState.Ready

    @JvmStatic
    fun isPremium(): Boolean = BillingBridge.isPremium.value

    /**
     * Opens the paywall for [placement].
     *
     * A refusal — not installed, already premium, placement off — reaches [listener] as a dismissal
     * rather than being dropped, so no caller waits on a callback that never comes.
     */
    @JvmStatic
    @JvmOverloads
    fun launch(
        activity: Activity,
        placement: PaywallPlacement,
        listener: PaywallListener? = null,
    ) {
        if (application == null) return decline(placement, listener, "PayKit is not installed")
        if (isPremium()) return decline(placement, listener, "already premium")
        if (!isEnabled(placement)) return decline(placement, listener, "placement disabled")
        // Two launches inside one tap window are one mis-fired tap: without this they stack two
        // paywalls and book two impressions for a single decision.
        if (!launchGate.accept()) return decline(placement, listener, "a paywall just opened")

        val token = listener?.let(::registerListener)
        prefs?.recordShown(placement)
        activity.startActivity(PaywallContract.intentFor(activity, placement, token))
    }

    private fun decline(
        placement: PaywallPlacement,
        listener: PaywallListener?,
        reason: String,
    ) {
        PayKitLog.w("launch(${placement.key}) skipped — $reason")
        listener?.onDismissed(placement)
        listener?.onFinished(placement, PaywallResult.Dismissed)
    }

    @JvmStatic
    fun addListener(listener: PaywallListener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    fun removeListener(listener: PaywallListener) {
        listeners.remove(listener)
    }

    /** Escape hatch for a host already on Compose; unset keeps `DefaultPaywallRenderer`. */
    @JvmStatic
    fun renderer(renderer: PaywallRenderer) {
        customRenderer = renderer
    }

    internal fun configOrNull(): PayKitConfig? = config

    internal fun paywallConfig(): PaywallConfig? = configStore?.config?.value

    internal fun rendererOrNull(): PaywallRenderer? = customRenderer

    /** One gate per presentation; every control shares it, see [SingleClick]. */
    internal fun singleClick(): SingleClick =
        SingleClick(config?.singleClickWindowMs ?: SingleClick.DEFAULT_WINDOW_MS)

    private fun registerListener(listener: PaywallListener): String =
        presentations.incrementAndGet().toString().also { pendingListeners[it] = listener }

    /** Global listeners plus the one [launch] was given for this presentation, if any. */
    internal fun listenersFor(token: String?): List<PaywallListener> {
        val oneShot = token?.let(pendingListeners::get) ?: return listeners.toList()
        return listeners + oneShot
    }

    // Dropped when the presentation ends, never when the screen is created: a configuration change
    // re-creates the Activity, which must still resolve the same listener.
    internal fun releaseListener(token: String?) {
        token?.let(pendingListeners::remove)
    }

    // Unconditional on purpose: BillingBridge already skips a catalogue whose ids, plans and
    // offers are unchanged, and duplicating that check here would let the two drift apart.
    private fun adopt(app: Application, snapshot: PaywallConfigSnapshot) {
        val resolved = snapshot.config
        BillingBridge.registerCatalog(app, resolved?.packages.orEmpty())
        PayKitLog.d(
            "config ${snapshot.origin} v${resolved?.configVersion}: " +
                "${resolved?.packages?.size ?: 0} packages, placements ${resolved?.placements}",
        )
        _state.value = if (resolved == null) {
            PayKitState.Error(
                snapshot.notes.joinToString("; ").ifBlank { "No paywall config available" },
            )
        } else {
            PayKitState.Ready(resolved.configVersion, resolved.packages.size)
        }
    }
}
