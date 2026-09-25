package com.ads.module.config.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A validated document: bundled JSON defaults, optional host asset edits, last successful remote,
 * and values a backend delivered under keys that predate the document.
 */
class SettingsDocument(
    val name: String,
    private val defaults: Map<String, Any>,
    private val extraDefault: (String) -> Any? = { null },
) {
    constructor(name: String, defaultJson: String, extraDefault: (String) -> Any? = { null }) :
        this(name, flatten(JSONObject(defaultJson)), extraDefault)

    private val updates = Mutex()
    private var preferences: SharedPreferences? = null
    private var initialized = false
    private val state = MutableStateFlow(SettingsSnapshot(defaults, emptyMap(), emptyMap()))
    val snapshots: StateFlow<SettingsSnapshot> = state.asStateFlow()
    val snapshot: SettingsSnapshot get() = state.value
    @Volatile private var local = SettingsSnapshot(defaults, emptyMap(), emptyMap())
    val localSnapshot: SettingsSnapshot get() = local
    private var lastAcceptedJson: String? = null
    private var hasAcceptedJson = false
    private var revision = 0L
    @Volatile private var prepareSnapshot: (SettingsSnapshot) -> Unit = {}
    @Volatile private var legacy: Map<String, Any> = emptyMap()

    /** Resolve consumer payloads on the parsing dispatcher, before readers see the snapshot. */
    fun prepareBeforePublish(prepare: (SettingsSnapshot) -> Unit) {
        prepareSnapshot = prepare
    }

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        preferences = runCatching { context.applicationContext.getSharedPreferences("adlogic_settings_$name", Context.MODE_PRIVATE) }.getOrNull()
        val assetJson = runCatching { context.assets.open("$name.json").bufferedReader().use { it.readText() } }.getOrNull()
        install(assetJson, runCatching { preferences?.getString("remote", null) }.getOrNull())
    }

    /** Loads the app asset and the persisted remote that [initialize] reads from the Context. */
    @Synchronized internal fun install(assetJson: String?, cachedRemote: String?) {
        val asset = localOverrides(assetJson)
        local = SettingsSnapshot(defaults, asset, emptyMap())
        state.value = SettingsSnapshot(defaults, asset, cachedRemote?.let(::parse).orEmpty(), legacy)
        revision++
    }

    /**
     * Values the backend sent under keys older than this document, already mapped to its paths.
     *
     * They belong to the remote tier: below the document's own remote fields, above the app asset
     * and every host option. Only keys the backend actually delivered belong here; a key it never
     * sent must stay absent, or its default would displace what the app configured.
     */
    @Synchronized fun acceptLegacyRemote(values: Map<String, Any>) {
        val accepted = values.filter { (path, value) -> accepts(path, value) }
        if (accepted == legacy) return
        legacy = accepted
        val current = snapshot
        val next = SettingsSnapshot(defaults, current.asset, current.remoteValues, accepted)
        prepareSnapshot(next)
        state.value = next
    }

    /** A sparse/custom app asset is an explicit assignment, including false/zero SDK values. */
    internal fun localOverrides(json: String?): Map<String, Any> {
        val parsed = json?.let(::parse) ?: return emptyMap()
        return if (sameValue(parsed, defaults)) emptyMap() else parsed
    }

    /** Synchronous host/test entry point; production fetches use [acceptFetched] off main. */
    @Synchronized fun acceptSuccessfulFetch(json: String?): Boolean {
        val update = prepare(json) ?: return false
        persist(update)
        publish(update)
        return true
    }

    /** Serialize updates, deserialize on CPU dispatcher, and keep preference work on IO. */
    internal suspend fun acceptFetched(json: String?): Boolean = updates.withLock {
        val update = withContext(Dispatchers.Default) { synchronized(this@SettingsDocument) { prepare(json) } }
            ?: return@withLock false
        withContext(Dispatchers.IO) {
            synchronized(this@SettingsDocument) {
                // A synchronous host assignment made while parsing/persisting was queued wins.
                if (revision != update.revision) return@synchronized false
                persist(update)
                publish(update)
                true
            }
        }
    }

    private data class Update(val input: String?, val remote: Map<String, Any>, val normalized: String?, val revision: Long, val snapshot: SettingsSnapshot)

    private fun prepare(json: String?): Update? {
        if (hasAcceptedJson && json == lastAcceptedJson) return Update(json, snapshot.remoteValues, null, revision, snapshot)
        val remote = if (json == null) emptyMap() else parse(json, report = true) ?: return null
        val next = if (snapshot.remoteValues == remote) snapshot else SettingsSnapshot(defaults, snapshot.asset, remote, legacy)
        prepareSnapshot(next)
        return Update(json, remote, if (json == null) null else inflate(remote).toString(), revision, next)
    }

    private fun persist(update: Update) {
        if (hasAcceptedJson && update.input == lastAcceptedJson) return
        runCatching {
            preferences?.edit()?.apply {
                if (update.input == null) remove("remote") else putString("remote", update.normalized)
            }?.apply()
        }
    }

    private fun publish(update: Update) {
        if (snapshot.remoteValues != update.remote) {
            // Legacy values accepted while this update was parsing must not be dropped by it.
            state.value = if (update.snapshot.legacyValues == legacy) update.snapshot
                else SettingsSnapshot(defaults, update.snapshot.asset, update.remote, legacy)
        }
        lastAcceptedJson = update.input
        hasAcceptedJson = true
        revision++
    }

    /**
     * Presence is retained separately: a missing remote field never becomes false or zero.
     *
     * A dropped field leaves the app's own value in charge, which from the console looks exactly like
     * remote being ignored, so a fetched document names every field it could not take.
     */
    private fun parse(json: String, report: Boolean = false): Map<String, Any>? {
        val parsed = runCatching {
            val root = JSONObject(json)
            val version = root.opt("schema_version")
            require(version == null || version is Number && version.toDouble() == 1.0) { "unsupported schema_version=$version" }
            flatten(root)
        }.onFailure { if (report) warn("$name rejected, keeping the previous values: ${it.message}") }
            .getOrNull() ?: return null
        val (kept, dropped) = parsed.entries.partition { (path, value) -> accepts(path, value) }
        if (report && dropped.isNotEmpty()) {
            warn("$name ignored ${dropped.joinToString { "${it.key}=${it.value}" }} (unknown path, wrong type or invalid value)")
        }
        return kept.associate { it.key to it.value }
    }

    private fun accepts(path: String, value: Any): Boolean {
        val example = defaults[path] ?: extraDefault(path)
        return example != null && sameType(example, value) && valid(path, value)
    }

    private fun warn(message: String) {
        runCatching { Log.w("AdLogicSettings", message) }
    }

    fun defaultValue(path: String): Any? = defaults[path]

    private fun sameType(a: Any, b: Any): Boolean = when (a) {
        is Boolean -> b is Boolean
        is Number -> b is Number && b.toDouble().isFinite() && b.toDouble() == b.toLong().toDouble()
        is String -> b is String
        is List<*> -> b is List<*>
        is Map<*, *> -> b is Map<*, *>
        else -> false
    }

    // Generated defaults use Long; Android JSONObject uses Integer for small integral literals.
    // Equivalent bundled values must never become an asset override of explicit host options.
    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is List<*> && b is List<*> -> a.size == b.size && a.zip(b).all { (x, y) -> sameValue(x, y) }
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.all { (key, value) -> sameValue(value, b[key]) }
        else -> a == b
    }

    private fun valid(path: String, value: Any): Boolean {
        if (value is Number) {
            val n = value.toLong()
            if (n < 0 || n > Int.MAX_VALUE.toLong()) return false
            if (path.endsWith("max_age_ms")) return n in 1..(if (path.startsWith("app_open")) 14_400_000L else 3_600_000L)
            if (path.endsWith("auto_dismiss_ms")) return n >= 5_000
            if (path.endsWith("inline_max_height_dp")) return n >= 32
            if (path.endsWith("background_delay_ms")) return n <= 86_400_000
            if (path.endsWith("min_count") || path.endsWith("show_from_tap") || path.endsWith("max_background_requests")) return n >= 1
            if (path.contains("banner") && path.endsWith("reload.interval_ms")) return n >= 1_000
            if (path.endsWith("reload.interval_ms")) return n > 0
            if (path.endsWith("wait_timeout_ms")) return true
            if (path.endsWith("timeout_ms") || path.endsWith("min_after_bind_ms") || path.endsWith("min_tick_ms") || path.endsWith("idle_tick_ms") || path.endsWith("refresh_throttle_ms") || path.endsWith("offline_recheck_ms")) return n > 0
        }
        if (value is String) {
            val allowed = when {
                path.endsWith("click.action") -> setOf("auto_next", "none", "reload")
                path.endsWith("ad_strategy") -> setOf("SAME_TIME", "ALTERNATE")
                path.endsWith("slot_format") -> setOf("BANNER", "NATIVE")
                path.endsWith("lfo1_preload_mode") -> setOf("PARALLEL", "SEQUENTIAL")
                path.endsWith("next_screen_timing") -> if (path == "splash.navigation.next_screen_timing") setOf("AUTO", "AFTER_AD", "UNDER_AD") else setOf("AFTER_AD", "UNDER_AD")
                path.endsWith("skip.style") || path.endsWith("fullscreen_skip_style") -> setOf("TEXT", "CLOSE_ICON")
                path.endsWith("skip.position") -> setOf("RIGHT", "LEFT")
                path.endsWith("empty_visibility") -> setOf("GONE", "INVISIBLE")
                path.endsWith("presentation.type") -> setOf("NORMAL", "LARGE_ANCHORED", "COLLAPSIBLE", "INLINE", "INLINE_MAX_HEIGHT", "FIXED")
                path.endsWith("collapsible_gravity") -> setOf("TOP", "BOTTOM")
                path.endsWith("fixed_size") -> setOf("BANNER", "LARGE_BANNER", "MEDIUM_RECTANGLE", "FULL_BANNER", "LEADERBOARD")
                path.endsWith("inline_style") -> setOf("LARGE", "SMALL")
                path.endsWith("selection.mode") -> setOf("SINGLE", "MULTIPLE")
                path.endsWith("initial_content_trigger") -> setOf("SPLASH_HANDOFF", "LFO_SHOWN", "FIRST_LANGUAGE_SELECTION")
                path == "lfo.native2.preload_trigger" -> setOf("LFO_SHOWN", "FIRST_SELECTION")
                path.endsWith("preload_trigger") -> setOf("LFO_SHOWN", "FIRST_SELECTION", "DIALOG_OPEN")
                path.endsWith("template") -> if (path.startsWith("onboarding.steps.")) setOf("", "CTA_TOP", "CTA_BOTTOM", "COMPACT") else setOf("CTA_TOP", "CTA_BOTTOM", "COMPACT", "FULL_SCREEN", "DIALOG")
                else -> null
            }
            if (allowed != null && value !in allowed) return false
        }
        if (value is List<*> && path == "onboarding.order") return value.all { it is String && it.isNotBlank() } && value.distinct().size == value.size
        if (value is List<*> && path.endsWith("failure_backoff_ms")) return value.isNotEmpty() && value.size <= 10 && value.all { it is Number && it.toLong() in 1..3_600_000 && it.toDouble() == it.toLong().toDouble() }
        if (value is List<*> && (path.endsWith("supported_codes") || path.endsWith("excluded_hosts"))) return value.all { it is String }
        return true
    }

    companion object {
        internal fun flatten(root: JSONObject, prefix: String = ""): Map<String, Any> = buildMap {
            root.keys().forEach { key ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (val value = root.opt(key)) {
                    null, JSONObject.NULL -> Unit
                    is JSONObject -> if (value.length() == 0) put(path, emptyMap<String, Any>()) else putAll(flatten(value, path))
                    is JSONArray -> put(path, List(value.length()) { i -> unwrap(value.opt(i)) })
                    else -> put(path, value)
                }
            }
        }
        private fun unwrap(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { unwrap(value.opt(it)) }
            is JSONArray -> List(value.length()) { unwrap(value.opt(it)) }
            else -> value
        }
        internal fun inflate(values: Map<String, Any>): JSONObject {
            val root = JSONObject()
            values.forEach { (path, value) ->
                val segments = path.split('.')
                var node = root
                segments.dropLast(1).forEach { key ->
                    node = node.optJSONObject(key) ?: JSONObject().also { node.put(key, it) }
                }
                node.put(segments.last(), JSONObject.wrap(value))
            }
            return root
        }
    }
}

/**
 * Immutable values for an attempt, screen visit, or load. Local explicit options remain fallbacks.
 *
 * Sources rank remote (the document's own fields, then legacy keys) > app asset > host option >
 * bundled default.
 */
class SettingsSnapshot internal constructor(
    private val defaults: Map<String, Any>,
    internal val asset: Map<String, Any>,
    internal val remoteValues: Map<String, Any>,
    internal val legacyValues: Map<String, Any> = emptyMap(),
) {
    private val jsonValues = ConcurrentHashMap<Pair<String, String?>, String>()

    /** What the backend delivered for [path], or null when it said nothing about it. */
    fun remoteValue(path: String): Any? = remoteValues[path] ?: legacyValues[path]

    /** What the app's own asset assigns to [path], or null. */
    fun assetValue(path: String): Any? = asset[path]

    fun overrideValue(path: String): Any? = remoteValue(path) ?: assetValue(path)
    fun hasRemoteOverride(path: String): Boolean = (remoteValues.keys + legacyValues.keys).any { it == path || it.startsWith("$path.") }
    fun hasOverride(path: String): Boolean = hasRemoteOverride(path) || asset.keys.any { it == path || it.startsWith("$path.") }
    fun boolean(path: String, fallback: Boolean? = null): Boolean =
        (overrideValue(path) ?: fallback ?: defaults[path]) as Boolean
    fun long(path: String, fallback: Long? = null): Long =
        ((overrideValue(path) ?: fallback ?: defaults[path]) as Number).toLong()
    fun string(path: String, fallback: String? = null): String =
        (overrideValue(path) ?: fallback ?: defaults[path]) as String
    fun strings(path: String, fallback: List<String>? = null): List<String> =
        ((overrideValue(path) ?: fallback ?: defaults[path]) as? List<*>)?.filterIsInstance<String>().orEmpty()
    fun longList(path: String, fallback: List<Long> = emptyList()): List<Long> =
        ((overrideValue(path) ?: defaults[path]) as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: fallback

    /** Ordered scopes, most specific first; see [ScopeChain]. */
    fun scoped(vararg paths: String): ScopeChain = ScopeChain(this, paths.asList())

    /** Already validated flat values relative to [path]; no JSON work at a timer/show call site. */
    fun objectEntries(path: String): Map<String, Any> = (defaults + asset + legacyValues + remoteValues)
        .filterKeys { it.startsWith("$path.") }.mapKeys { it.key.removePrefix("$path.") }

    fun json(path: String, fallback: String? = null): String = jsonValues.computeIfAbsent(path to fallback) {
        resolveJson(path, fallback)
    }

    private fun resolveJson(path: String, fallback: String?): String {
        if (!hasOverride(path) && fallback != null) return fallback
        val inherited = fallback?.let { runCatching { SettingsDocument.flatten(JSONObject(it), path) }.getOrNull() }.orEmpty()
        val node = SettingsDocument.inflate(defaults + inherited + asset + legacyValues + remoteValues)
        var current: Any? = node
        path.split('.').forEach { key -> current = (current as? JSONObject)?.opt(key) }
        return current?.toString() ?: "{}"
    }
}

/**
 * One setting read from an ordered list of scopes, most specific first. Source outranks scope: the
 * most specific scope remote answers wins, then the most specific scope the app asset answers, and
 * the host's own value stands when neither does. Walking scope first let an app asset at a narrow
 * scope hide a remote value at a broad one. Stating the order once is the point — a field that spells
 * its own chain out by hand is how two sibling settings end up with different precedence.
 */
class ScopeChain internal constructor(
    private val values: SettingsSnapshot,
    private val paths: List<String>,
) {
    private fun override(): Any? =
        paths.firstNotNullOfOrNull(values::remoteValue) ?: paths.firstNotNullOfOrNull(values::assetValue)
    fun boolean(fallback: Boolean): Boolean = override() as? Boolean ?: fallback
    fun long(fallback: Long): Long = (override() as? Number)?.toLong() ?: fallback
    fun string(fallback: String): String = override() as? String ?: fallback
}
