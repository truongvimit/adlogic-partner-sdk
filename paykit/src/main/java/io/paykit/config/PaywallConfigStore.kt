package io.paykit.config

import android.content.Context
import androidx.annotation.RawRes
import io.paykit.R
import io.paykit.internal.PayKitPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which rung of the resolution chain the active config came from. */
enum class ConfigOrigin { REMOTE, CACHE, BUNDLE, NONE }

/** The active config plus how it was reached; [notes] says why any rung above it was skipped. */
data class PaywallConfigSnapshot(
    val config: PaywallConfig?,
    val origin: ConfigOrigin,
    val notes: List<String>,
)

/**
 * Resolves the paywall config: installed source, then the prefs cache, then the bundled JSON.
 *
 * A rung that fails to parse is skipped with its reason recorded rather than crashing the host,
 * and a fetch that fails leaves the snapshot already in place untouched.
 */
internal class PaywallConfigStore(
    context: Context,
    @RawRes fallbackConfigRes: Int = 0,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext
    private val bundledRes = if (fallbackConfigRes != 0) fallbackConfigRes else DEFAULT_CONFIG_RES
    private val prefs = PayKitPrefs(appContext)

    @Volatile
    private var activeSource: PaywallConfigSource? = null

    // Why the chain ended where it did, kept apart from the snapshot so a later fetch failure can
    // append its own reason without either losing that diagnosis or stacking copies of its own.
    @Volatile
    private var seedNotes: List<String> = emptyList()

    private val _snapshot = MutableStateFlow(seed())
    private val _config = MutableStateFlow(_snapshot.value.config)

    val snapshot: StateFlow<PaywallConfigSnapshot> = _snapshot.asStateFlow()

    /** Null only when every rung failed, including the bundled document. */
    val config: StateFlow<PaywallConfig?> = _config.asStateFlow()

    val origin: ConfigOrigin get() = _snapshot.value.origin

    fun source(source: PaywallConfigSource?) {
        activeSource = source
    }

    /** Fetches once. Returns false on timeout, fetch failure or an unusable document. */
    suspend fun sync(timeoutMs: Long): Boolean {
        val active = activeSource ?: return false
        // Guarded even though the SPI forbids throwing: a partner adapter is third-party code.
        // Cancellation is rethrown so the timeout still unwinds instead of being swallowed.
        val raw = withTimeoutOrNull(timeoutMs) {
            try {
                active.fetch(timeoutMs)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                note("source '${active.id}' threw: ${error.message ?: error.javaClass.simpleName}")
                null
            }
        }
        if (raw.isNullOrBlank()) {
            note("source '${active.id}' returned nothing within ${timeoutMs}ms")
            return false
        }
        return withContext(io) { adopt(raw, "source '${active.id}'") }
    }

    /** Entry point for hosts that run their own remote config and hand the JSON over. */
    fun applySnapshot(json: String): Boolean = adopt(json, "applied snapshot")

    private fun adopt(raw: String, label: String): Boolean =
        when (val result = PaywallConfigParser.parse(raw)) {
            is ConfigParseResult.Success -> {
                prefs.cacheConfig(raw, result.config.configVersion)
                publish(
                    PaywallConfigSnapshot(
                        config = result.config,
                        origin = ConfigOrigin.REMOTE,
                        notes = result.config.problems,
                    )
                )
                true
            }

            is ConfigParseResult.Failure -> {
                note("$label rejected: ${result.reason}")
                if (origin == ConfigOrigin.NONE) publish(seed())
                false
            }
        }

    private fun seed(): PaywallConfigSnapshot = resolveSeed().also { seedNotes = it.notes }

    // Runs during construction so PayKit.install stays synchronous and isReady() answers
    // immediately; both rungs read a few KB from disk or from the APK.
    private fun resolveSeed(): PaywallConfigSnapshot {
        val notes = mutableListOf<String>()

        readCache()?.let { cached ->
            when (val result = PaywallConfigParser.parse(cached)) {
                is ConfigParseResult.Success -> return PaywallConfigSnapshot(
                    config = result.config,
                    origin = ConfigOrigin.CACHE,
                    notes = notes + result.config.problems,
                )

                is ConfigParseResult.Failure -> {
                    prefs.clearCachedConfig()
                    notes += "cache rejected: ${result.reason}"
                }
            }
        }

        val bundled = readBundled()
        if (bundled == null) {
            notes += "bundled config unreadable"
            return PaywallConfigSnapshot(null, ConfigOrigin.NONE, notes)
        }

        return when (val result = PaywallConfigParser.parse(bundled)) {
            is ConfigParseResult.Success -> PaywallConfigSnapshot(
                config = result.config,
                origin = ConfigOrigin.BUNDLE,
                notes = notes + result.config.problems,
            )

            is ConfigParseResult.Failure -> {
                notes += "bundled config rejected: ${result.reason}"
                PaywallConfigSnapshot(null, ConfigOrigin.NONE, notes)
            }
        }
    }

    private fun readCache(): String? = prefs.cachedConfigJson

    private fun readBundled(): String? = runCatching {
        appContext.resources.openRawResource(bundledRes).bufferedReader().use { it.readText() }
    }.getOrNull()

    // One writer for both flows, so an observer of config can never see a stale origin.
    private fun publish(next: PaywallConfigSnapshot) {
        _snapshot.value = next
        _config.value = next.config
    }

    // Rebuilt rather than appended, so repeated syncs cannot grow the list. With no config at all
    // the seed notes stay: they carry why every rung failed, which outranks a fetch timeout.
    private fun note(reason: String) {
        val current = _snapshot.value
        _snapshot.value = current.copy(notes = (current.config?.problems ?: seedNotes) + reason)
    }

    private companion object {
        val DEFAULT_CONFIG_RES = R.raw.pw_default_config
    }
}
