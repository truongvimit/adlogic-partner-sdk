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
                    id = "code",
                    isEnable = true,
                    enableUaCheck = true,
                    reloadIntervalSeconds = 11,
                    colorCTA = "#101010",
                    heightCTA = 31,
                    positionCTA = "TOP",
                    components = listOf("body"),
                    ids = listOf("code-high"),
                    appResumeLoadDelayMs = 700L,
                    clickAction = NativeClickAction.AUTO_NEXT,
                ),
            ),
        )
        AdRemoteConfig.update(code, fromRemote = false)
        assertEquals("code", AdRemoteConfig.getInstance().unit("native_lang").id)

        val remote = AdRemoteConfig(
            mapOf(
                "native_lang" to AdUnitConfig(
                    id = "remote",
                    isEnable = false,
                    enableUaCheck = false,
                    reloadIntervalSeconds = 0,
                    colorCTA = "",
                    heightCTA = 0,
                    positionCTA = "",
                    components = emptyList(),
                    ids = emptyList(),
                    appResumeLoadDelayMs = 0L,
                    clickAction = NativeClickAction.NONE,
                ),
            ),
        )
        AdRemoteConfig.applyRemote(remote)
        val active = AdRemoteConfig.getInstance().unit("native_lang")
        assertEquals("remote", active.id)
        assertFalse(active.isEnable)
        assertFalse(active.enableUaCheck)
        assertEquals(0, active.reloadIntervalSeconds)
        assertEquals("", active.colorCTA)
        assertEquals(0, active.heightCTA)
        assertEquals("", active.positionCTA)
        assertEquals(emptyList<String>(), active.components)
        assertEquals(emptyList<String>(), active.ids)
        assertEquals(0L, active.appResumeLoadDelayMs)
        assertEquals(NativeClickAction.NONE, active.clickAction)

        // Firebase's successful {} payload means the whole remote document was removed. The code
        // tier is visible again immediately; a failed fetch would leave the remote instance intact.
        AdRemoteConfig.applyRemote(AdRemoteConfig())
        val restored = AdRemoteConfig.getInstance().unit("native_lang")
        assertEquals("code", restored.id)
        assertTrue(restored.isEnable)
        assertTrue(restored.enableUaCheck)
        assertEquals(11, restored.reloadIntervalSeconds)
        assertEquals("#101010", restored.colorCTA)
        assertEquals(31, restored.heightCTA)
        assertEquals("TOP", restored.positionCTA)
        assertEquals(listOf("body"), restored.components)
        assertEquals(listOf("code-high"), restored.ids)
        assertEquals(700L, restored.appResumeLoadDelayMs)
        assertEquals(NativeClickAction.AUTO_NEXT, restored.clickAction)
    }

    @Test
    fun `remote-only placement disappears when that key is removed`() {
        AdRemoteConfig.update(
            AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "code", isEnable = true))),
            fromRemote = false,
        )
        AdRemoteConfig.applyRemote(
            AdRemoteConfig(
                mapOf(
                    "native_lang" to AdUnitConfig(id = "remote", isEnable = true),
                    "native_welcome1" to AdUnitConfig(id = "remote-welcome", isEnable = true),
                ),
            ),
        )
        assertEquals("remote-welcome", AdRemoteConfig.getInstance().unit("native_welcome1").id)
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig(id = "new", isEnable = true))))
        assertFalse(AdRemoteConfig.getInstance().declares("native_welcome1"))
        assertFalse(AdRemoteConfig.getInstance().isPlacementEnabled("native_welcome1"))
    }
}
