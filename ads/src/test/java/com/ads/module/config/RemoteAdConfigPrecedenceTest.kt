package com.ads.module.config

import com.ads.module.helper.adnative.NativeClickAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RemoteAdConfigPrecedenceTest {

    @After fun clear() {
        AdRemoteConfig.setAllowRemoteOverrideInDebug(false)
        AdRemoteConfig.reset()
    }

    @Test fun `only keys the backend delivered count as remote`() {
        AdRemoteConfig.update(AdRemoteConfig(mapOf("native_lang" to AdUnitConfig("asset", true))), fromRemote = false)
        assertFalse(AdRemoteConfig.remoteDeclares("native_lang"))
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf("native_lang_high" to AdUnitConfig("remote", true))))
        assertTrue(AdRemoteConfig.remoteDeclares("native_lang"))
        assertFalse(AdRemoteConfig.remoteDeclares("inter_splash"))
        AdRemoteConfig.update(AdRemoteConfig.getInstance().copy())
        assertTrue("An edit of a remote document keeps its origin", AdRemoteConfig.isFromRemote())
    }

    @Test fun `a debuggable build keeps its test ids and takes every other field from remote`() {
        AdRemoteConfig.pinAssets(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig("test_lang", true),
            "inter_splash" to AdUnitConfig("test_inter", true),
        )), AdRemoteConfig.DEBUG_FILE_NAME)
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf(
            "native_lang" to AdUnitConfig("live_lang", false, colorCTA = "#00ff00", positionCTA = "TOP"),
            "native_ob1" to AdUnitConfig("live_ob1", true),
            "native_ob2" to AdUnitConfig("live_ob2", false),
        )))
        val active = AdRemoteConfig.getInstance()
        assertEquals("test_lang", active.ads.getValue("native_lang").id)
        assertFalse("Remote switched the slot off", active.isPlacementEnabled("native_lang"))
        assertEquals("#00ff00", active.ads.getValue("native_lang").colorCTA)
        assertEquals("TOP", active.ads.getValue("native_lang").positionCTA)
        assertTrue("A unit remote never mentioned keeps the shipped test id", active.isPlacementEnabled("inter_splash"))
        assertTrue("Remote behavior remains declared even without a debug id", active.declares("native_ob1"))
        assertFalse("No test id exists for a remote-only slot", active.isPlacementEnabled("native_ob1"))
        assertFalse("A remote switch-off applies without a test id", active.isPlacementEnabled("native_ob2"))
        assertTrue(AdRemoteConfig.remoteDeclares("native_lang"))
        assertFalse("The shipped key is not remote's", AdRemoteConfig.remoteDeclares("inter_splash"))

        AdRemoteConfig.setAllowRemoteOverrideInDebug(true)
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf("native_ob1" to AdUnitConfig("live_ob1", true))))
        assertEquals(listOf("live_ob1"), AdRemoteConfig.getInstance().tiersFor("native_ob1"))
    }

    @Test fun `a remote click action patches the asset one and a silent remote keeps it`() {
        AdRemoteConfig.pinAssets(checkNotNull(AdRemoteConfig.fromJson("""
            {"native_ob1":{"id":"test_ob1","isEnable":true,"click_action":"auto_next"},
             "native_lang":{"id":"test_lang","isEnable":true,"click_action":"reload"}}
        """)), AdRemoteConfig.DEBUG_FILE_NAME)
        AdRemoteConfig.initializeFromJson("""{"native_ob1":{"id":"live_ob1","click_action":"none"},"native_lang":{"isEnable":true}}""")
        val active = AdRemoteConfig.getInstance()
        assertEquals(NativeClickAction.NONE, active.ads.getValue("native_ob1").clickAction)
        assertEquals("The debug pin keeps only the id", "test_ob1", active.ads.getValue("native_ob1").id)
        assertEquals(NativeClickAction.RELOAD, active.ads.getValue("native_lang").clickAction)

        AdRemoteConfig.initializeFromJson("""{"native_ob1":{"click_action":"typo"}}""")
        assertEquals(NativeClickAction.AUTO_NEXT, AdRemoteConfig.getInstance().ads.getValue("native_ob1").clickAction)
        AdRemoteConfig.initializeFromJson("{}")
        assertEquals(NativeClickAction.AUTO_NEXT, AdRemoteConfig.getInstance().ads.getValue("native_ob1").clickAction)
    }
}
