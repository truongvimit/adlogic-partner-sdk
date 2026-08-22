package io.paykit.internal

import android.content.Context
import androidx.core.content.edit
import io.paykit.PaywallPlacement

/** The paywall's whole disk footprint: an impression counter, per-placement timestamps, cache. */
internal class PayKitPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val cachedConfigJson: String? get() = prefs.getString(KEY_CACHED_CONFIG_JSON, null)

    /** How many paywalls this install has seen, across every placement. */
    private val paywallViewCount: Int get() = prefs.getInt(KEY_VIEW_COUNT, 0)

    /** A changed `config_version` drops the previous document before the new one lands. */
    fun cacheConfig(json: String, version: Int) {
        if (version != prefs.getInt(KEY_CACHED_CONFIG_VERSION, NO_VERSION)) clearCachedConfig()
        prefs.edit {
            putString(KEY_CACHED_CONFIG_JSON, json)
            putInt(KEY_CACHED_CONFIG_VERSION, version)
        }
    }

    /** One write per presentation, so the counter and the timestamp can never disagree. */
    fun recordShown(placement: PaywallPlacement, atMs: Long = System.currentTimeMillis()) {
        val next = paywallViewCount + 1
        prefs.edit {
            putInt(KEY_VIEW_COUNT, next)
            putLong(KEY_LAST_SHOWN_PREFIX + placement.key, atMs)
        }
    }

    // Only the two config keys go, never the whole file: the view counter and the last_shown_at_*
    // stamps share paykit_prefs and must survive a routine config_version bump.
    fun clearCachedConfig() {
        prefs.edit {
            remove(KEY_CACHED_CONFIG_JSON)
            remove(KEY_CACHED_CONFIG_VERSION)
        }
    }

    private companion object {
        const val FILE_NAME = "paykit_prefs"
        const val KEY_VIEW_COUNT = "paywall_view_count"
        const val KEY_LAST_SHOWN_PREFIX = "last_shown_at_"
        const val KEY_CACHED_CONFIG_JSON = "cached_config_json"
        const val KEY_CACHED_CONFIG_VERSION = "cached_config_version"
        const val NO_VERSION = Int.MIN_VALUE
    }
}
