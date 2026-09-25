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
data class AdRemoteConfig @JvmOverloads constructor(
    val ads: Map<String, AdUnitConfig> = emptyMap(),
) {

    /** Extra wait configured inside open_resume; captured once for each background stay. */
    val appResumeLoadDelayMs: Long
        get() = ads["open_resume"]?.appResumeLoadDelayMs ?: DEFAULT_APP_RESUME_LOAD_DELAY_MS

    companion object {
        private const val TAG = "AdRemoteConfig"

        const val DEFAULT_APP_RESUME_LOAD_DELAY_MS = 2_000L
        const val MAX_APP_RESUME_LOAD_DELAY_MS = 86_400_000L

        @JvmStatic
        fun normalizeAppResumeLoadDelayMs(value: Long): Long =
            value.takeIf { it in 0..MAX_APP_RESUME_LOAD_DELAY_MS }
                ?: DEFAULT_APP_RESUME_LOAD_DELAY_MS

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
         * While it stands, a remote document keeps the ad unit ids the assets shipped and sets
         * everything else. A debug run that took the live document's ids would spend the real
         * units, which is invalid traffic on the app's own account.
         */
        @Volatile
        private var debugAssetsPinned = false

        /** What [initializeFromAssets] loaded on a debuggable build, and from which file. */
        @Volatile
        private var pinnedAssets: AdRemoteConfig? = null

        @Volatile
        private var pinnedFile: String? = null

        @Volatile
        private var pinReported = false

        @Volatile
        private var allowRemoteOverrideInDebug = false

        @Volatile
        private var remoteDocument = false

        @Volatile
        private var remoteKeys: Set<String> = emptySet()

        /** True when the active document came from the backend rather than the app's own assets. */
        @JvmStatic
        fun isFromRemote(): Boolean = remoteDocument

        /**
         * True when the backend's document, not the app's assets, declares [baseKey] or one of its
         * floors. Only then does ad_config outrank a value the app set in code.
         */
        @JvmStatic
        fun remoteDeclares(baseKey: String): Boolean = FLOOR_SUFFIXES.any { (baseKey + it) in remoteKeys }

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
            com.ads.module.config.settings.AdBehavior.initialize(context)
            val debug = isDebuggable(context)
            // A debug build with no debug config falls back rather than starting up empty.
            val candidates = if (debug) listOf(DEBUG_FILE_NAME, RELEASE_FILE_NAME) else listOf(RELEASE_FILE_NAME)
            val (fileName, loaded) = candidates.firstNotNullOfOrNull { name ->
                fromAssets(context, name)?.let { name to it }
            } ?: run {
                Log.e(
                    TAG,
                    "No ad config found. Ship assets/$RELEASE_FILE_NAME in your app, " +
                        "or call initializeFromJson() yourself.",
                )
                return
            }
            Log.i(TAG, "Loaded $fileName with ${loaded.ads.size} placements (debug=$debug)")
            pinAssets(loaded.takeIf { debug }, fileName)
            // The shipped file never replaces a document the backend already delivered; that
            // document is re-applied so a debuggable build pins its ids from here on.
            if (isFromRemote()) applyRemote(getInstance()) else update(loaded, fromRemote = false)
        }

        /**
         * True when a remote document must keep the ad unit ids the assets loaded.
         *
         * Read by [com.ads.module.config.AdConfig]; a host that deliberately injects a document
         * through [initializeFromJson] is not gated by it.
         */
        @JvmStatic
        fun isRemoteOverrideBlocked(): Boolean = debugAssetsPinned && !allowRemoteOverrideInDebug

        /**
         * Lets a debuggable build take remote ad unit ids after all, for testing a live
         * configuration. Off by default, so a debug run keeps spending test ids.
         */
        @JvmStatic
        fun setAllowRemoteOverrideInDebug(allow: Boolean) {
            allowRemoteOverrideInDebug = allow
        }

        /**
         * [remote] as it may apply while [isRemoteOverrideBlocked]: a key the pinned assets also
         * declare keeps their `id`/`ids` and takes every other field from remote, a key only
         * remote declares is dropped unless it switches the placement off (there is no test unit
         * to request for it), and a key only the assets declare stays as shipped. Unpinned,
         * [remote] as is.
         */
        internal fun withPinnedIds(remote: AdRemoteConfig): AdRemoteConfig {
            val assets = pinnedAssets
            if (!isRemoteOverrideBlocked() || assets == null) return remote
            if (!pinReported) {
                pinReported = true
                Log.w(
                    TAG,
                    "Debuggable build: ad unit ids stay on assets/$pinnedFile, remote sets every other " +
                        "field. setAllowRemoteOverrideInDebug(true) takes the remote ids too.",
                )
            }
            val merged = assets.ads.mapValues { (key, unit) ->
                remote.ads[key]?.copy(id = unit.id, ids = unit.ids) ?: unit
            }
            return AdRemoteConfig(merged + remote.ads.filter { (key, unit) -> key !in assets.ads && !unit.isEnable })
        }

        /** [assets] is what a debuggable build loaded, whose ids every remote document keeps; null unpins. */
        internal fun pinAssets(assets: AdRemoteConfig?, fileName: String) {
            debugAssetsPinned = assets != null
            pinnedAssets = assets
            pinnedFile = fileName.takeIf { assets != null }
        }

        /** Applies a document the backend delivered, pinning a debuggable build's ids. Main thread. */
        internal fun applyRemote(remote: AdRemoteConfig) {
            val applied = withPinnedIds(remote)
            publish(applied, fromRemote = true, keys = applied.ads.keys.filterTo(mutableSetOf()) { it in remote.ads })
        }

        /** Replaces the active configuration, e.g. after remote config delivers a new document. */
        @JvmStatic
        fun initializeFromJson(json: String) {
            val parsed = fromJson(json)
            if (parsed == null) {
                Log.w(TAG, "Ignoring unparsable ad config; keeping the previous one")
                return
            }
            update(parsed, fromRemote = true)
        }

        /**
         * @param fromRemote the document came from the backend, so it outranks the app's own
         *   settings. Omitted, an edit of the active document keeps its origin.
         */
        @JvmStatic
        @JvmOverloads
        fun update(newConfig: AdRemoteConfig, fromRemote: Boolean = remoteDocument) =
            publish(newConfig, fromRemote, if (fromRemote) newConfig.ads.keys else emptySet())

        private fun publish(newConfig: AdRemoteConfig, fromRemote: Boolean, keys: Set<String>) {
            synchronized(this) {
                instance = newConfig
                remoteDocument = fromRemote
                remoteKeys = keys
            }
            // Bind every id to its placement before anything can load: the paid-event bridge reads
            // the placement back by ad unit id, and an unregistered unit reports as "unknown".
            AdPlacements.registerAll(newConfig)
            com.ads.module.admob.AppOpenManager.getInstance().applyRemoteConfig()
            com.ads.module.helper.interstitial.InterstitialAutoBuffer.onGateChanged()
        }

        @JvmStatic
        fun reset() {
            synchronized(this) {
                instance = null
                remoteDocument = false
                remoteKeys = emptySet()
            }
            debugAssetsPinned = false
            pinnedAssets = null
            pinnedFile = null
            pinReported = false
        }

        @JvmStatic
        fun fromJson(json: String): AdRemoteConfig? {
            if (json.isBlank()) return null
            return runCatching { AdConfigParser.parseConfig(json.reader()) }
                .onFailure { Log.w(TAG, "Ad config parse failed: ${it.message}") }
                .getOrNull()
        }

        @JvmStatic
        fun fromInputStream(inputStream: InputStream): AdRemoteConfig? =
            runCatching {
                inputStream.bufferedReader().use { AdConfigParser.parseConfig(it) }
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
    fun tiersFor(baseKey: String): List<String> {
        // The base key is the placement's master switch: a declared `isEnable: false` there turns
        // the whole waterfall off, so "disable a slot" is one edit rather than one per floor.
        val base = ads[baseKey]
        if (base != null && !base.isEnable) return emptyList()
        return FLOOR_SUFFIXES
            .mapNotNull { suffix -> ads[baseKey + suffix] }
            .filter { it.isUsable }
            .flatMap { it.waterfallIds }
            .distinct()
    }

    /** True when the payload declares [baseKey] or any of its floors. */
    fun declares(baseKey: String): Boolean = FLOOR_SUFFIXES.any { ads.containsKey(baseKey + it) }

    /** True when [baseKey] resolves to at least one requestable ad unit id. */
    fun isPlacementEnabled(baseKey: String): Boolean = tiersFor(baseKey).isNotEmpty()
}
