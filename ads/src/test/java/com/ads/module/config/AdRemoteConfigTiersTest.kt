package com.ads.module.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The floor ladder: which remote keys form a placement's waterfall, and in what order.
 *
 * This is the rule the whole app monetises through, and it is data-driven — a wrong order here
 * spends the all-price floor before the high one on every request.
 */
class AdRemoteConfigTiersTest {

    @Test
    fun `high then numbered rungs then all-price`() {
        val config = configOf(
            "inter_splash_high" to "high",
            "inter_splash_high1" to "high1",
            "inter_splash" to "allprice",
        )
        assertEquals(listOf("high", "high1", "allprice"), config.tiersFor("inter_splash"))
    }

    @Test
    fun `numbered rungs extend the ladder without a code change`() {
        val config = configOf(
            "native_lang_high" to "h",
            "native_lang_high1" to "h1",
            "native_lang_high2" to "h2",
            "native_lang_high3" to "h3",
            "native_lang" to "all",
        )
        assertEquals(listOf("h", "h1", "h2", "h3", "all"), config.tiersFor("native_lang"))
    }

    @Test
    fun `numbered rungs sort by number, not by string`() {
        // "_high10" sorts before "_high2" alphabetically; the ladder must not
        val config = configOf(
            "native_fs_high2" to "h2",
            "native_fs_high9" to "h9",
            "native_fs_high" to "h",
        )
        assertEquals(listOf("h", "h2", "h9"), config.tiersFor("native_fs"))
    }

    @Test
    fun `a single key is a valid one-floor placement`() {
        assertEquals(listOf("only"), configOf("banner_home" to "only").tiersFor("banner_home"))
    }

    @Test
    fun `missing floors are skipped, order of the rest is kept`() {
        val config = configOf("native_fs_high" to "h", "native_fs" to "all")
        assertEquals(listOf("h", "all"), config.tiersFor("native_fs"))
    }

    @Test
    fun `disabled floor is dropped`() {
        val config = AdRemoteConfig(
            mapOf(
                "native_ob1_high" to AdUnitConfig(id = "h", isEnable = false),
                "native_ob1" to AdUnitConfig(id = "all", isEnable = true),
            ),
        )
        assertEquals(listOf("all"), config.tiersFor("native_ob1"))
    }

    @Test
    fun `repeated id across floors is requested once`() {
        val config = configOf("inter_splash_high" to "same", "inter_splash" to "same")
        assertEquals(listOf("same"), config.tiersFor("inter_splash"))
    }

    @Test
    fun `ids array inside one key is the waterfall verbatim`() {
        // The escape hatch for a placement with more floors than the suffix ladder offers
        val config = AdRemoteConfig(
            mapOf(
                "inter_splash" to AdUnitConfig(
                    id = "allprice",
                    isEnable = true,
                    ids = listOf("a", "b", "c"),
                ),
            ),
        )
        assertEquals(listOf("a", "b", "c", "allprice"), config.tiersFor("inter_splash"))
    }

    @Test
    fun `unknown placement has no floors`() {
        assertEquals(emptyList<String>(), configOf().tiersFor("does_not_exist"))
    }

    private fun configOf(vararg entries: Pair<String, String>) = AdRemoteConfig(
        entries.associate { (key, id) -> key to AdUnitConfig(id = id, isEnable = true) },
    )
}
