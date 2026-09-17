package com.ads.module.update

import org.json.JSONObject

/** minVersionCode is a versionCode threshold, never a versionName or SDK version. */
data class ForceUpdateConfig(
    val icon: String = "",
    val title: String = "",
    val description: String = "",
    val storeLink: String = "",
    val minVersionCode: Long = 0,
    val force: Boolean = false,
    val enabled: Boolean = false,
) {
    fun needsUpdate(installedVersionCode: Long): Boolean =
        enabled && minVersionCode > 0 && installedVersionCode < minVersionCode

    fun isRequired(installedVersionCode: Long): Boolean = force && needsUpdate(installedVersionCode)

    companion object {
        const val REMOTE_KEY = "force_update_config"
        const val ASSET_FILE = "force_update_config.json"

        /** Invalid documents are rejected, rather than converting typos into a blocking rule. */
        @JvmStatic
        fun fromJson(json: String): ForceUpdateConfig? = runCatching {
            val obj = JSONObject(json)
            val threshold = if (obj.has("minVersionCode")) obj.get("minVersionCode") else 0L
            require(threshold is Int || threshold is Long)
            val version = (threshold as Number).toLong()
            require(version >= 0)
            val force = if (obj.has("force")) obj.get("force") else false
            require(force is Boolean)
            val enabled = if (obj.has("enabled")) obj.get("enabled") else false
            require(enabled is Boolean)
            fun string(key: String): String = if (obj.has(key)) {
                (obj.get(key) as? String) ?: error("$key must be a string")
            } else ""
            ForceUpdateConfig(string("icon"), string("title"), string("description"),
                string("storeLink"), version, force, enabled)
        }.getOrNull()
    }
}
