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
