package com.ads.module.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.InputStream

/**
 * The ad units this build may request, keyed by placement.
 *
 * Loaded from the host app's `assets/ad_config.json` by convention — a partner ships that file and
 * calls nothing — and replaced at runtime when remote config delivers a newer document.
 */
data class AdRemoteConfig(
    val ads: Map<String, AdUnitConfig> = emptyMap(),
) {

    companion object {
        private const val TAG = "AdRemoteConfig"

        const val RELEASE_FILE_NAME = "ad_config.json"
        const val DEBUG_FILE_NAME = "ad_config_debug.json"

        /** How deep the numbered rungs go: `_high1`…`_high9`. */
        private const val MAX_NUMBERED_FLOORS = 9

        /**
         * Every floor key a placement may declare, in request order:
         * `_high`, `_high1`…`_high9`, then the bare key.
         *
         * One named rung and a number is the whole vocabulary — a separate `_medium` would only
         * be `_high1` under another name, and two ways to spell the same floor is how a payload
         * ends up declaring both. The bare key is always the all-price floor and always last.
         *
         * Need more than ten floors, or an order that is not high→low? Put the ids straight into
         * one key's `ids` array — that list is taken as the waterfall, verbatim and unlimited.
         */
        private val FLOOR_SUFFIXES: List<String> =
            listOf("_high") + (1..MAX_NUMBERED_FLOORS).map { "_high$it" } + ""

        @Volatile
        private var instance: AdRemoteConfig? = null

        /**
         * Set by [initializeFromAssets] on a debuggable build.
         *
         * While it stands, a remote refresh is ignored — the rule the handwritten flow enforced
         * with `if (BuildConfig.DEBUG) fromAssets(DEBUG_FILE_NAME)`, which read the remote
         * document only on a release build. Without it a debug run fetches the live document and
         * spends the real ad unit ids, which is invalid traffic on the app's own account.
         */
        @Volatile
        private var debugAssetsPinned = false

        @Volatile
        private var allowRemoteOverrideInDebug = false

        /**
         * The active configuration, or an empty one if nothing has loaded yet.
         *
         * Empty rather than throwing: a placement that asks before the config lands should report
         * "no ad unit" and move on, not take the screen down.
         */
        @JvmStatic
        fun getInstance(): AdRemoteConfig = instance ?: AdRemoteConfig()

        @JvmStatic
        fun isInitialized(): Boolean = instance != null

        /**
         * Loads `assets/ad_config.json`, or `assets/ad_config_debug.json` on a debuggable build so
         * a debug run never spends real ad units.
         */
        @JvmStatic
        fun initializeFromAssets(context: Context) {
            val debug = isDebuggable(context)
            val fileName = if (debug) DEBUG_FILE_NAME else RELEASE_FILE_NAME
            val loaded = fromAssets(context, fileName)
                // A debug build with no debug config falls back rather than starting up empty.
                ?: if (debug) fromAssets(context, RELEASE_FILE_NAME) else null
            if (loaded == null) {
                Log.e(
                    TAG,
                    "No ad config found. Ship assets/$RELEASE_FILE_NAME in your app, " +
                        "or call initializeFromJson() yourself.",
                )
                return
            }
            Log.i(TAG, "Loaded $fileName with ${loaded.ads.size} placements (debug=$debug)")
            debugAssetsPinned = debug
            update(loaded)
        }

        /**
         * True when a remote document must not replace what the assets loaded.
         *
         * Read by [com.ads.module.config.AdConfig.refresh]; a host that deliberately injects a
         * document through [initializeFromJson] is not gated by it.
         */
        @JvmStatic
        fun isRemoteOverrideBlocked(): Boolean = debugAssetsPinned && !allowRemoteOverrideInDebug

        /**
         * Lets a debuggable build take remote ad units after all, for testing a live
         * configuration. Off by default, so a debug run keeps spending test ids.
         */
        @JvmStatic
        fun setAllowRemoteOverrideInDebug(allow: Boolean) {
            allowRemoteOverrideInDebug = allow
        }

        /** Replaces the active configuration, e.g. after remote config delivers a new document. */
        @JvmStatic
        fun initializeFromJson(json: String) {
            val parsed = fromJson(json)
            if (parsed == null) {
                Log.w(TAG, "Ignoring unparsable ad config; keeping the previous one")
                return
            }
            update(parsed)
        }

        @JvmStatic
        fun update(newConfig: AdRemoteConfig) {
            synchronized(this) { instance = newConfig }
            // Bind every id to its placement before anything can load: the paid-event bridge reads
            // the placement back by ad unit id, and an unregistered unit reports as "unknown".
            AdPlacements.registerAll(newConfig)
        }

        @JvmStatic
        fun reset() {
            synchronized(this) { instance = null }
            debugAssetsPinned = false
        }

        @JvmStatic
        fun fromJson(json: String): AdRemoteConfig? {
            if (json.isBlank()) return null
            return runCatching { AdRemoteConfig(AdConfigParser.parse(json.reader())) }
                .onFailure { Log.w(TAG, "Ad config parse failed: ${it.message}") }
                .getOrNull()
        }

        @JvmStatic
        fun fromInputStream(inputStream: InputStream): AdRemoteConfig? =
            runCatching {
                inputStream.bufferedReader().use { AdRemoteConfig(AdConfigParser.parse(it)) }
            }.onFailure { Log.w(TAG, "Ad config parse failed: ${it.message}") }.getOrNull()

        @JvmStatic
        @JvmOverloads
        fun fromAssets(context: Context, fileName: String = RELEASE_FILE_NAME): AdRemoteConfig? =
            runCatching { context.assets.open(fileName).use { fromInputStream(it) } }
                .onFailure { Log.d(TAG, "No asset $fileName: ${it.message}") }
                .getOrNull()

        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * The unit configured for [key], or a disabled placeholder when the payload has no such key.
     *
     * Never null: a missing placement is a configuration mistake that should skip one ad, not
     * crash the screen that asked for it.
     */
    fun unit(key: String): AdUnitConfig {
        val unit = ads[key]
        if (unit == null) {
            Log.w(TAG, "Ad unit '$key' not found in configuration")
            return AdUnitConfig(id = "", isEnable = false)
        }
        return unit
    }

    /**
     * The placement that declared [adUnitId], or null when no key in the payload lists it.
     *
     * The reverse of [unit]: a caller holding only an ad unit id — a provider binding an ad it was
     * handed, a paid-event callback — can still reach the placement's `components`, CTA colour and
     * height. Without it those settings apply only where the call site happened to know the key.
     */
    fun unitForAdId(adUnitId: String): AdUnitConfig? {
        if (adUnitId.isBlank()) return null
        return ads.values.firstOrNull { adUnitId in it.waterfallIds }
    }

    /**
     * The ad unit ids for [baseKey], highest floor first.
     *
     * Remote config spells a waterfall as one key per floor — `<key>_high`, `<key>_high1`, …,
     * `<key>` — rather than a list inside one key, so this is what turns that convention into
     * request order. A placement uses however many floors it actually declares: missing or
     * disabled ones are simply absent, and one with no usable floor returns an empty list.
     *
     * ```
     * tiersFor("inter_splash")  // [inter_splash_high, inter_splash_high1, inter_splash]
     * tiersFor("banner_home")   // [banner_home] — single floor, still valid
     * ```
     */
    fun tiersFor(baseKey: String): List<String> =
        FLOOR_SUFFIXES
            .mapNotNull { suffix -> ads[baseKey + suffix] }
            .filter { it.isUsable }
            .flatMap { it.waterfallIds }
            .distinct()
}
