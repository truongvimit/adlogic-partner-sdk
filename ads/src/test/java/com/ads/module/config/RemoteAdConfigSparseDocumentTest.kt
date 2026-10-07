package com.ads.module.config

import com.ads.module.helper.adnative.NativeClickAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-document remote replacement must clear deleted keys while preserving lower tiers. */
class RemoteAdConfigSparseDocumentTest {
    @After
    fun reset() {
        AdRemoteConfig.reset()
    }

    @Test
    fun `remote field values outrank code and a later empty document clears the remote patch`() {
        val code = AdRemoteConfig(
            mapOf(
                "native_lang" to AdUnitConfig(
                    ids = listOf("code-high", "code"),
                    isEnable = true,
                    enableUaCheck = true,
                    reloadIntervalSeconds = 11,
                    colorCTA = "#101010",
                    heightCTA = 31,
                    components = listOf("body"),
                    appResumeLoadDelayMs = 700L,
                    clickAction = NativeClickAction.AUTO_NEXT,
                ),
            ),
        )
        AdRemoteConfig.update(code, fromRemote = false)
        assertEquals(listOf("code-high", "code"), AdRemoteConfig.getInstance().unit("native_lang").ids)

        val remote = AdRemoteConfig(
            mapOf(
                "native_lang" to AdUnitConfig(
                    ids = listOf("remote"),
                    isEnable = false,
                    enableUaCheck = false,
                    reloadIntervalSeconds = 0,
                    colorCTA = "",
                    heightCTA = 0,
                    components = emptyList(),
                    appResumeLoadDelayMs = 0L,
                    clickAction = NativeClickAction.NONE,
                ),
            ),
        )
        AdRemoteConfig.applyRemote(remote)
        val active = AdRemoteConfig.getInstance().unit("native_lang")
        assertEquals(listOf("remote"), active.ids)
        assertFalse(active.isEnable)
        assertFalse(active.enableUaCheck)
        assertEquals(0, active.reloadIntervalSeconds)
        assertEquals("", active.colorCTA)
        assertEquals(0, active.heightCTA)
        assertEquals(emptyList<String>(), active.components)
        assertEquals(0L, active.appResumeLoadDelayMs)
        assertEquals(NativeClickAction.NONE, active.clickAction)

        // Firebase's successful {} payload means the whole remote document was removed. The code
        // tier is visible again immediately; a failed fetch would leave the remote instance intact.
        AdRemoteConfig.applyRemote(AdRemoteConfig())
        val restored = AdRemoteConfig.getInstance().unit("native_lang")
        assertEquals(listOf("code-high", "code"), restored.ids)
        assertTrue(restored.isEnable)
        assertTrue(restored.enableUaCheck)
        assertEquals(11, restored.reloadIntervalSeconds)
        assertEquals("#101010", restored.colorCTA)
        assertEquals(31, restored.heightCTA)
        assertEquals(listOf("body"), restored.components)
        assertEquals(700L, restored.appResumeLoadDelayMs)
        assertEquals(NativeClickAction.AUTO_NEXT, restored.clickAction)
    }

    @Test
    fun `remote-only placement disappears when that key is removed`() {
        AdRemoteConfig.update(
            AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(ids = listOf("code"), isEnable = true))),
            fromRemote = false,
        )
        AdRemoteConfig.applyRemote(
            AdRemoteConfig(
                mapOf(
                    "native_lang" to AdUnitConfig(ids = listOf("remote"), isEnable = true),
                    "native_welcome1" to AdUnitConfig(ids = listOf("remote-welcome"), isEnable = true),
                ),
            ),
        )
        assertEquals(listOf("remote-welcome"), AdRemoteConfig.getInstance().unit("native_welcome1").ids)
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(ids = listOf("new"), isEnable = true))))
        assertFalse(AdRemoteConfig.getInstance().declares("native_welcome1"))
        assertFalse(AdRemoteConfig.getInstance().isPlacementEnabled("native_welcome1"))
    }
}
