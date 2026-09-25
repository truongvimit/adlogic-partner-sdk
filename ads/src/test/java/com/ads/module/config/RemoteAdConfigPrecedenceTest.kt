package com.ads.module.config

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
            "native_lang" to AdUnitConfig("live_lang", false),
            "native_ob1" to AdUnitConfig("live_ob1", true),
            "native_ob2" to AdUnitConfig("live_ob2", false),
        )))
        val active = AdRemoteConfig.getInstance()
        assertEquals("test_lang", active.ads.getValue("native_lang").id)
        assertFalse("Remote switched the slot off", active.isPlacementEnabled("native_lang"))
        assertTrue("A unit remote never mentioned keeps the shipped test id", active.isPlacementEnabled("inter_splash"))
        assertFalse("No test id exists for a remote-only slot", active.declares("native_ob1"))
        assertFalse("A remote switch-off applies without a test id", active.isPlacementEnabled("native_ob2"))
        assertTrue(AdRemoteConfig.remoteDeclares("native_lang"))
        assertFalse("The shipped key is not remote's", AdRemoteConfig.remoteDeclares("inter_splash"))

        AdRemoteConfig.setAllowRemoteOverrideInDebug(true)
        AdRemoteConfig.applyRemote(AdRemoteConfig(mapOf("native_ob1" to AdUnitConfig("live_ob1", true))))
        assertEquals(listOf("live_ob1"), AdRemoteConfig.getInstance().tiersFor("native_ob1"))
    }
}
