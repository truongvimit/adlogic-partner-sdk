package com.itg.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardKitAssetContractTest {
    @Test
    fun standard_onboarding_placements_are_enabled_in_both_local_assets() {
        listOf("src/main/assets/ad_config.json", "src/main/assets/ad_config_debug.json").forEach { path ->
            val json = File(path).readText()
            listOf(
                "native_ob1" to "native_onboarding_1_1",
                "native_ob2" to "native_onboarding_2_1",
                "native_full1" to "native_onboarding_fullscreen_1_3",
                "native_ob3" to "native_onboarding_1_4",
                "native_full2" to "native_onboarding_fullscreen_2_3",
                "native_ob4" to "native_onboarding_2_4",
            ).forEach { (sdkKey, legacyKey) ->
                val sdk = entry(json, sdkKey)
                val legacy = entry(json, legacyKey)
                assertTrue("$path/$sdkKey must be enabled", sdk.enabled)
                assertTrue("$path/$sdkKey must have an id", sdk.id.isNotBlank())
                assertEquals("$path/$sdkKey must use the app's onboarding unit", legacy.id, sdk.id)
                if (sdkKey == "native_full1" || sdkKey == "native_full2") {
                    assertTrue("$path/$sdkKey must not require Adjust attribution in the example", !sdk.uaCheck)
                }
            }
        }
    }

    @Test
    fun every_native_base_key_declares_its_click_action_and_floor_keys_declare_none() {
        val pagerKeys = setOf("native_ob1", "native_ob2", "native_ob3", "native_ob4", "native_full1", "native_full2")
        adConfigAssets.forEach { path ->
            val units = units(File(path).readText())
            assertTrue("$path has no placements", units.isNotEmpty())
            assertTrue("$path lost a pager key", units.keys.containsAll(pagerKeys))
            units.forEach { (key, body) ->
                val action = clickAction.find(body)?.groupValues?.get(1)
                val floor = floorKey.matches(key)
                when {
                    floor || !key.startsWith("native_") -> assertEquals("$path/$key must not declare click_action", null, action)
                    key in pagerKeys -> assertEquals("$path/$key", "auto_next", action)
                    else -> assertEquals("$path/$key", "reload", action)
                }
            }
        }
    }

    @Test
    fun no_other_json_asset_carries_a_click_action() {
        val roots = listOf("src/main/assets", "../ads/src/main/assets", "../onboardkitorigin/src/main/assets",
            "../partner-integration/examples")
        val others = roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "json" }.toList() }
            .filter { file -> adConfigAssets.none { File(it).canonicalFile == file.canonicalFile } }
        assertTrue("no JSON assets found", others.any { it.name == "onboarding_config.json" })
        others.forEach { file ->
            val json = file.readText()
            listOf("\"click\"\\s*:", "click_action", "on_ad_click", "ad_click_return_completes_step").forEach { pattern ->
                assertTrue("${file.path} must not contain $pattern", !Regex(pattern).containsMatchIn(json))
            }
        }
    }

    private val adConfigAssets = listOf(
        "src/main/assets/ad_config.json",
        "src/main/assets/ad_config_debug.json",
        "../partner-integration/examples/ads-onboarding/ad_config.json",
        "../partner-integration/examples/ads-onboarding/ad_config_debug.json",
    )
    private val clickAction = Regex("\\\"click_action\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
    private val floorKey = Regex(".*_high\\d?")

    private fun units(json: String): Map<String, String> =
        Regex("\\\"([A-Za-z0-9_]+)\\\"\\s*:\\s*\\{([^{}]*)\\}").findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private data class Entry(val id: String, val enabled: Boolean, val uaCheck: Boolean)

    private fun entry(json: String, key: String): Entry {
        val body = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: error("missing $key")
        val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(body)?.groupValues?.get(1)
            ?: error("missing id for $key")
        val enabled = Regex("\\\"isEnable\\\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)?.toBoolean()
            ?: error("missing isEnable for $key")
        val uaCheck = Regex("\\\"enable_ua_check\\\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)?.toBoolean()
            ?: error("missing enable_ua_check for $key")
        return Entry(id, enabled, uaCheck)
    }
}
