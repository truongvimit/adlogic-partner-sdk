package com.ads.module.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.RestrictTo
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

    /** Fields explicitly present in each source document. Programmatic configs are complete. */
    @Transient
    internal var declaredFields: Map<String, Set<String>> = emptyMap()

    internal fun fieldsFor(key: String): Set<String> =
        declaredFields[key] ?: ALL_FIELDS

    internal fun isSparse(): Boolean = declaredFields.isNotEmpty()

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

        internal val ALL_FIELDS: Set<String> = setOf(
            "ids", "isEnable", "enable_ua_check", "reloadIntervalSeconds", "colorCTA", "colorBackground",
            "colorAdBadge", "colorAdBadgeText",
            "heightCTA", "components", "app_resume_load_delay_ms", "click_action",
            "templateId",
        )

        private fun defaultUnit() = AdUnitConfig(ids = emptyList(), isEnable = false)

        /** Drop placement objects whose every field was invalid; they must not shadow code/assets. */
        private fun validPatch(source: AdRemoteConfig): AdRemoteConfig {
            val kept = source.ads.filterKeys { source.fieldsFor(it).isNotEmpty() }
            return if (kept.size == source.ads.size) source else AdRemoteConfig(kept).also { result ->
                result.declaredFields = kept.keys.associateWith { source.fieldsFor(it) }
            }
        }

        /** Merge one source over another by field presence, retaining explicit false/0/[] values. */
        internal fun merge(lower: AdRemoteConfig, upper: AdRemoteConfig): AdRemoteConfig {
            if (upper.ads.isEmpty()) return lower
            val keys = LinkedHashSet<String>().apply { addAll(lower.ads.keys); addAll(upper.ads.keys) }
            val merged = keys.associateWith { key ->
                val base = lower.ads[key] ?: defaultUnit()
                val top = upper.ads[key]
                if (top == null) base else mergeUnit(base, top, upper.fieldsFor(key))
            }
            return AdRemoteConfig(merged).also { it.declaredFields = merged.keys.associateWith { ALL_FIELDS } }
        }

        /** Overlay a sparse app-code patch without turning omitted fields into assignments. */
        private fun overlayPatch(lower: AdRemoteConfig, upper: AdRemoteConfig): AdRemoteConfig {
            if (upper.ads.isEmpty()) return lower
            val keys = LinkedHashSet<String>().apply { addAll(lower.ads.keys); addAll(upper.ads.keys) }
            val merged = keys.associateWith { key ->
                val base = lower.ads[key] ?: defaultUnit()
                val top = upper.ads[key]
                if (top == null) base else mergeUnit(base, top, upper.fieldsFor(key))
            }
            return AdRemoteConfig(merged).also { result ->
                result.declaredFields = keys.associateWith { key ->
                    (lower.declaredFields[key].orEmpty() + upper.declaredFields[key].orEmpty()).ifEmpty { ALL_FIELDS }
                }
            }
        }

        private fun mergeUnit(base: AdUnitConfig, top: AdUnitConfig, fields: Set<String>): AdUnitConfig = base.copy(
            ids = if ("ids" in fields) top.ids else base.ids,
            isEnable = if ("isEnable" in fields) top.isEnable else base.isEnable,
            enableUaCheck = if ("enable_ua_check" in fields) top.enableUaCheck else base.enableUaCheck,
            reloadIntervalSeconds = if ("reloadIntervalSeconds" in fields) top.reloadIntervalSeconds else base.reloadIntervalSeconds,
            colorCTA = if ("colorCTA" in fields) top.colorCTA else base.colorCTA,
            colorBackground = if ("colorBackground" in fields) top.colorBackground else base.colorBackground,
            colorAdBadge = if ("colorAdBadge" in fields) top.colorAdBadge else base.colorAdBadge,
            colorAdBadgeText = if ("colorAdBadgeText" in fields) top.colorAdBadgeText else base.colorAdBadgeText,
            heightCTA = if ("heightCTA" in fields) top.heightCTA else base.heightCTA,
            components = if ("components" in fields) top.components else base.components,
            appResumeLoadDelayMs = if ("app_resume_load_delay_ms" in fields) top.appResumeLoadDelayMs else base.appResumeLoadDelayMs,
            clickAction = if ("click_action" in fields) top.clickAction else base.clickAction,
            templateId = if ("templateId" in fields) top.templateId else base.templateId,
        )

        @Volatile
        private var instance: AdRemoteConfig? = null

        /**
         * The test `ids` of each placement, from `ad_config_debug.json`, while a debuggable build
         * runs on it; null otherwise.
         *
         * While it stands every resolved key requests its test ids, whatever ad_config or the
         * backend say about ids. A debug run that took the real ids would spend them, which is
         * invalid traffic on the app's own account.
         */
        @Volatile
        private var debugTestIds: Map<String, List<String>>? = null

        @Volatile
        private var reportedMissingTestIds: Set<String>? = null

        @Volatile
        private var allowRemoteOverrideInDebug = false

        @Volatile
        private var remoteDocument = false

        /** Lower tiers retained so a sparse remote payload never erases app configuration. */
        @Volatile private var assetConfig: AdRemoteConfig = AdRemoteConfig()
        @Volatile private var codeConfig: AdRemoteConfig = AdRemoteConfig()
        @Volatile private var remotePatch: AdRemoteConfig? = null

        @Volatile
        private var remoteKeys: Set<String> = emptySet()

        /** True when the active document came from the backend rather than the app's own assets. */
        @JvmStatic
        fun isFromRemote(): Boolean = remoteDocument

        /** True when the backend's document, not the app's assets, declares [key]. */
        @JvmStatic
        fun remoteDeclares(key: String): Boolean = key in remoteKeys

        /**
         * True when the backend or the app's `ad_config.json` declares [key]. Either outranks an
         * ad unit the app set in code, which is then only the fallback.
         */
        @JvmStatic
        fun declaredAboveCode(key: String): Boolean = remoteDeclares(key) || key in assetConfig.ads

        /** True only when the active remote patch supplied this field for [key]. */
        @JvmStatic
        fun remoteDeclaresField(key: String, field: String): Boolean =
            key in remoteKeys && remotePatch?.fieldsFor(key)?.contains(field) == true

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
         * Loads `assets/ad_config.json` for every build type. A debuggable build also loads
         * `assets/ad_config_debug.json`, whose test `ids` replace each placement's waterfall, so a
         * debug run never spends real ad units.
         */
        @JvmStatic
        fun initializeFromAssets(context: Context) {
            com.ads.module.config.settings.AdBehavior.initialize(context)
            val debug = isDebuggable(context)
            val release = fromAssets(context, RELEASE_FILE_NAME)
            val debugFile = if (debug) fromAssets(context, DEBUG_FILE_NAME) else null
            if (release == null && debugFile == null) {
                Log.e(
                    TAG,
                    "No ad config found. Ship assets/$RELEASE_FILE_NAME in your app, " +
                        "or call initializeFromJson() yourself.",
                )
                return
            }
            if (debug && debugFile == null) {
                Log.w(TAG, "Debuggable build without assets/$DEBUG_FILE_NAME requests the release ad unit ids")
            }
            installAssets(release, debugFile)
        }

        /**
         * [release] is the app's settings tier; [debugFile], loaded only on a debuggable build,
         * supplies the test ids. A debug-only app runs on its debug file for both.
         */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun installAssets(release: AdRemoteConfig?, debugFile: AdRemoteConfig?) {
            val loaded = release ?: debugFile ?: return
            Log.i(
                TAG,
                "Loaded ${if (release != null) RELEASE_FILE_NAME else DEBUG_FILE_NAME} with " +
                    "${loaded.ads.size} placements (test ids=${debugFile != null})",
            )
            assetConfig = loaded
            pinTestIds(debugFile)
            // The shipped file never replaces a document the backend already delivered; that
            // document is re-applied so a debuggable build takes its test ids from here on.
            if (isFromRemote()) applyRemote(remotePatch ?: AdRemoteConfig()) else publishResolved(false)
        }

        /** True when a debuggable build requests `ad_config_debug.json` test ids, not remote ones. */
        @JvmStatic
        fun isRemoteOverrideBlocked(): Boolean = debugTestIds != null && !allowRemoteOverrideInDebug

        /**
         * Lets a debuggable build take the ad unit ids the backend declares, for testing a live
         * configuration. Keys the backend gives no id keep their test id. Off by default.
         */
        @JvmStatic
        fun setAllowRemoteOverrideInDebug(allow: Boolean) {
            allowRemoteOverrideInDebug = allow
        }

        /**
         * [resolved] with each key's `ids` replaced by its debug test ids (none when
         * `ad_config_debug.json` lists none). Every other field stays as resolved. Outside a
         * debuggable build with a debug file, [resolved] as is.
         */
        internal fun withTestIds(resolved: AdRemoteConfig): AdRemoteConfig {
            val testIds = debugTestIds ?: return resolved
            val remote = remotePatch.takeIf { allowRemoteOverrideInDebug }
            val merged = resolved.ads.mapValues { (key, unit) ->
                val remoteFields = remote?.takeIf { key in it.ads }?.fieldsFor(key).orEmpty()
                if ("ids" in remoteFields) unit else unit.copy(ids = testIds[key].orEmpty())
            }
            val missing = merged.filter { (_, unit) -> unit.isEnable && unit.waterfallIds.isEmpty() }
                .keys.toSortedSet()
            if (missing != reportedMissingTestIds) {
                reportedMissingTestIds = missing
                Log.w(
                    TAG,
                    "Debuggable build: settings from $RELEASE_FILE_NAME/remote, ids from $DEBUG_FILE_NAME." +
                        if (missing.isEmpty()) "" else " No test id, so no ad, for: $missing",
                )
            }
            return AdRemoteConfig(merged).also { it.declaredFields = resolved.declaredFields }
        }

        /** [debugFile] is the `ad_config_debug.json` a debuggable build loaded; null unpins. */
        internal fun pinTestIds(debugFile: AdRemoteConfig?) {
            reportedMissingTestIds = null
            debugTestIds = debugFile?.let { file ->
                val ignored = file.ads.keys.filter { key -> file.fieldsFor(key).any { it != "ids" } }
                if (ignored.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "$DEBUG_FILE_NAME only supplies \"ids\"; other fields come from " +
                            "$RELEASE_FILE_NAME. Ignored in: $ignored",
                    )
                }
                file.ads.mapValues { (_, unit) -> unit.ids }
            }
        }

        /** Applies a document the backend delivered, pinning a debuggable build's ids. Main thread. */
        internal fun applyRemote(remote: AdRemoteConfig) {
            remotePatch = validPatch(remote)
            val applied = withTestIds(resolveSources())
            publish(applied, fromRemote = true, keys = remotePatch?.ads?.keys.orEmpty())
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
            if (fromRemote) applyRemote(newConfig) else {
                codeConfig = newConfig
                // A code edit is a lower tier. It must never erase an already
                // accepted remote snapshot; deletion is represented by an empty
                // remote document instead.
                publishResolved(remoteDocument)
            }

        /**
         * Applies a sparse host-code patch below the app asset and remote document layers.
         * Integrations use this for live debug/admin controls instead of copying the resolved
         * remote snapshot back into code (which would resurrect deleted remote fields).
         */
        @JvmStatic
        fun updateCodeFromJson(json: String) {
            val parsed = fromJson(json)?.let(::validPatch) ?: return
            codeConfig = overlayPatch(codeConfig, parsed)
            publishResolved(remoteDocument)
        }

        /** Resolve remote > app asset > app code > SDK defaults, preserving field presence. */
        private fun resolveSources(): AdRemoteConfig {
            val sdk = AdRemoteConfig()
            val code = merge(sdk, codeConfig)
            val asset = merge(code, assetConfig)
            return remotePatch?.let { merge(asset, it) } ?: asset
        }

        private fun publishResolved(fromRemote: Boolean) {
            val resolved = withTestIds(resolveSources())
            publish(resolved, fromRemote, if (fromRemote) remotePatch?.ads?.keys.orEmpty() else emptySet())
        }

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
                assetConfig = AdRemoteConfig()
                codeConfig = AdRemoteConfig()
                remotePatch = null
            }
            debugTestIds = null
            reportedMissingTestIds = null
            allowRemoteOverrideInDebug = false
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
            return AdUnitConfig(ids = emptyList(), isEnable = false)
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
     * The ad unit ids for [key], highest floor first: the enabled floors of its `ids` while the
     * key is switched on; otherwise empty.
     *
     * ```
     * tiersFor("inter_splash")  // ["…/2floor", "…/allprice"]
     * tiersFor("banner_home")   // ["…/banner"] — a one-floor ids, still valid
     * ```
     */
    fun tiersFor(key: String): List<String> = ads[key]?.takeIf { it.isUsable }?.waterfallIds.orEmpty()

    /** True when the payload declares [key]. */
    fun declares(key: String): Boolean = ads.containsKey(key)

    /** True when [key] resolves to at least one requestable ad unit id. */
    fun isPlacementEnabled(key: String): Boolean = tiersFor(key).isNotEmpty()
}
