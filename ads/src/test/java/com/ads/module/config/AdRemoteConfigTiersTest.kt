package com.ads.module.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The waterfall: one key per placement, its floors in `ids`, highest first.
 *
 * This is the rule the whole app monetises through, and it is data-driven — a wrong order here
 * spends the all-price floor before the high one on every request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AdRemoteConfigTiersTest {

    @After
    fun reset() {
        AdRemoteConfig.reset()
    }

    @Test
    fun `ids is the waterfall in declared order`() {
        val config = parse(placement("inter_splash", floor("high"), floor("high1"), floor("allprice")))
        assertEquals(listOf("high", "high1", "allprice"), config.tiersFor("inter_splash"))
    }

    @Test
    fun `a floor switched off keeps its place and is not requested`() {
        val config = parse(placement("native_ob1", floor("high", false), floor("all")))
        assertEquals(listOf("all"), config.tiersFor("native_ob1"))
    }

    @Test
    fun `a floor without isEnable is on`() {
        val config = parse("""{"native_lang": {"ids": [{"id": "high"}, {"id": "all"}], "isEnable": true}}""")
        assertEquals(listOf("high", "all"), config.tiersFor("native_lang"))
    }

    @Test
    fun `a one-floor ids is a valid single-unit placement`() {
        val config = parse("""{"banner_home": {"ids": [{"id": "only"}], "isEnable": true}}""")
        assertEquals(listOf("only"), config.tiersFor("banner_home"))
    }

    @Test
    fun `the placement isEnable turns every floor off`() {
        val config = parse(placement("native_ob1", floor("h"), floor("all"), enabled = false))
        assertEquals(emptyList<String>(), config.tiersFor("native_ob1"))
        assertFalse(config.isPlacementEnabled("native_ob1"))
    }

    @Test
    fun `every floor off leaves the placement nothing to request`() {
        val config = parse(placement("native_ob1", floor("h", false), floor("all", false)))
        assertEquals(emptyList<String>(), config.tiersFor("native_ob1"))
    }

    @Test
    fun `repeated ids are requested once`() {
        val config = parse(placement("inter_splash", floor("same"), floor("same")))
        assertEquals(listOf("same"), config.tiersFor("inter_splash"))
    }

    @Test
    fun `a _high key is not a floor of its base key`() {
        val config = parse(
            """{"native_lang_high": {"ids": [{"id": "h"}], "isEnable": true}, "native_lang": {"ids": [{"id": "all"}], "isEnable": true}}""",
        )
        assertEquals(listOf("all"), config.tiersFor("native_lang"))
        assertTrue(config.declares("native_lang_high"))
    }

    @Test
    fun `remote ids replaces the asset's whole array`() {
        AdRemoteConfig.installAssets(parse(placement("native_lang", floor("high"), floor("all"))), null)
        AdRemoteConfig.applyRemote(parse("""{"native_lang": {"ids": [{"id": "remote"}]}}"""))
        assertEquals(listOf("remote"), AdRemoteConfig.getInstance().tiersFor("native_lang"))
    }

    @Test
    fun `remote switching a floor off keeps the asset's other fields`() {
        AdRemoteConfig.installAssets(
            parse("""{"native_lang": {"ids": [{"id": "high"}, {"id": "all"}], "isEnable": true, "heightCTA": 50}}"""),
            null,
        )
        AdRemoteConfig.applyRemote(parse(placement("native_lang", floor("high", false), floor("all"))))
        val active = AdRemoteConfig.getInstance()
        assertEquals(listOf("all"), active.tiersFor("native_lang"))
        assertEquals(50, active.unit("native_lang").heightCTA)
    }

    @Test
    fun `the debug file's ids replace the whole waterfall`() {
        AdRemoteConfig.installAssets(
            parse(placement("native_lang", floor("high"), floor("all"))),
            parse("""{"native_lang": {"ids": [{"id": "test"}]}}"""),
        )
        assertEquals(listOf("test"), AdRemoteConfig.getInstance().tiersFor("native_lang"))
    }

    @Test
    fun `unknown placement has no floors`() {
        assertEquals(emptyList<String>(), AdRemoteConfig().tiersFor("does_not_exist"))
    }

    private fun parse(json: String) = AdRemoteConfig.fromJson(json)!!

    private fun floor(id: String, enabled: Boolean = true) = """{"id": "$id", "isEnable": $enabled}"""

    private fun placement(key: String, vararg floors: String, enabled: Boolean = true) =
        """{"$key": {"ids": [${floors.joinToString()}], "isEnable": $enabled}}"""
}
