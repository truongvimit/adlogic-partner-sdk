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
        "rewarded" -> path in setOf("load.tier_timeout_ms", "cache.max_age_ms", "buffer.after_close")
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
    fun values(format: String, placement: String? = null, screen: SettingsSnapshot? = null, screenPath: String? = null, screenBasePath: String? = null) =
        BehaviorValues(document.snapshot, format, placement, screen, screenPath, screenBasePath)
}

class BehaviorValues internal constructor(
    private val values: SettingsSnapshot, private val format: String, private val placement: String?,
    private val screen: SettingsSnapshot?, private val screenPath: String?, private val screenBasePath: String?,
) {
    private fun override(path: String): Any? = screenPath?.let { screen?.overrideValue("$it.$path") }
        ?: screenBasePath?.let { screen?.overrideValue("$it.$path") }
        ?: placement?.let { values.overrideValue("placement_overrides.$it.$format.$path") }
        ?: values.overrideValue("$format.$path")
    fun hasOverride(path: String): Boolean = override(path) != null
    fun boolean(path: String, fallback: Boolean): Boolean = override(path) as? Boolean ?: fallback
    fun long(path: String, fallback: Long): Long = (override(path) as? Number)?.toLong() ?: fallback
    fun string(path: String, fallback: String): String = override(path) as? String ?: fallback
}
