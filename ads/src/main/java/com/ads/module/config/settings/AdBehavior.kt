package com.ads.module.config.settings

import android.content.Context

/** Common behavior, with optional placement and screen-specific overrides. IDs stay in ad_config. */
object AdBehavior {
    @JvmField val document = SettingsDocument("ad_behavior_config", BundledAdBehavior.VALUES, ::extraDefault)
    private fun extraDefault(path: String): Any? {
        if (path.startsWith("placement_overrides.")) {
            val parts = path.split('.')
            val format = parts.getOrNull(2) ?: return null
            val suffix = parts.drop(3).joinToString(".")
            return if (supportsPlacementField(format, suffix)) document.defaultValue("$format.$suffix") else null
        }
        if (path.startsWith("interstitial_auto_buffer.rules.")) return when (path.substringAfterLast('.')) {
            "enabled", "independent_interval" -> false
            "tap_threshold", "interval_ms" -> 0L
            else -> null
        }
        return null
    }
    fun supportsPlacementField(format: String, path: String): Boolean = when (format) {
        "banner" -> path.startsWith("reload.") || path.startsWith("presentation.")
        "native" -> path == "click.action" || path.startsWith("reload.") || path.startsWith("presentation.") || path.startsWith("preload.") || path == "load.tier_timeout_ms"
        "interstitial" -> path in setOf("load.tier_timeout_ms", "load_and_show.wait_timeout_ms", "load_and_show.buffer_wait_timeout_ms", "presentation.loading_enabled", "cache.max_age_ms")
        "rewarded" -> path in setOf("load.tier_timeout_ms", "cache.max_age_ms")
        else -> false
    }
    @JvmStatic fun defaultBool(path: String) = document.localSnapshot.boolean(path)
    @JvmStatic fun defaultNumber(path: String) = document.localSnapshot.long(path)
    @JvmStatic fun defaultText(path: String) = document.localSnapshot.string(path)
    @JvmStatic fun initialize(context: Context) { document.initialize(context); SettingsRegistry.register(document) }
    @JvmStatic fun bool(path: String): Boolean = document.snapshot.boolean(path)
    @JvmStatic fun number(path: String): Long = document.snapshot.long(path)
    @JvmStatic fun text(path: String): String = document.snapshot.string(path)
    @JvmStatic fun bool(path: String, fallback: Boolean): Boolean = document.snapshot.boolean(path, fallback)
    @JvmStatic fun number(path: String, fallback: Long): Long = document.snapshot.long(path, fallback)
    @JvmStatic fun text(path: String, fallback: String): String = document.snapshot.string(path, fallback)
    /** @param screenAliases a screen's own scalar that stands for a behavior field, e.g. its wait. */
    fun values(format: String, placement: String? = null, screen: SettingsSnapshot? = null, screenPath: String? = null, screenBasePath: String? = null, screenAliases: Map<String, String> = emptyMap()) =
        BehaviorValues(document.snapshot, format, placement, screen, screenPath, screenBasePath, screenAliases)
}

/** Scopes rank slot > screen alias > shared group > placement > format; remote at any scope beats the app asset at any scope. */
class BehaviorValues internal constructor(
    private val values: SettingsSnapshot, private val format: String, private val placement: String?,
    private val screen: SettingsSnapshot?, private val screenPath: String?, private val screenBasePath: String?,
    private val screenAliases: Map<String, String> = emptyMap(),
) {
    private fun scopes(path: String): List<Pair<SettingsSnapshot, String>> = buildList {
        if (screen != null) {
            screenPath?.let { add(screen to "$it.$path") }
            screenAliases[path]?.let { add(screen to it) }
            screenBasePath?.let { add(screen to "$it.$path") }
        }
        placement?.let { add(values to "placement_overrides.$it.$format.$path") }
        add(values to "$format.$path")
    }
    private fun override(path: String): Any? = scopes(path).let { scopes ->
        scopes.firstNotNullOfOrNull { (source, key) -> source.remoteValue(key) }
            ?: scopes.firstNotNullOfOrNull { (source, key) -> source.assetValue(key) }
    }
    fun hasOverride(path: String): Boolean = override(path) != null
    fun boolean(path: String, fallback: Boolean): Boolean = override(path) as? Boolean ?: fallback
    fun long(path: String, fallback: Long): Long = (override(path) as? Number)?.toLong() ?: fallback
    fun string(path: String, fallback: String): String = override(path) as? String ?: fallback
}
