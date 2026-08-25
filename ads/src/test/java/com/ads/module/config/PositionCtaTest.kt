package com.ads.module.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who decides the order of a native's blocks.
 *
 * A placement that names a `positionCTA` belongs to a screen that ships one layout per position —
 * the onboarding flow does — so the order is already settled by the layout it picks, and
 * `components` is left to say which blocks appear. A placement with no position hands the order to
 * `components` instead, through the `ad_container` reorder.
 *
 * The two must not both apply to one placement: reordering inside a layout chosen *for* its order
 * would undo the choice.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.13 ships framework jars up to 34; the module compiles against 36.
@Config(sdk = [34])
class PositionCtaTest {

    @Test
    fun `absent positionCTA means no opinion, so components decide the order`() {
        val unit = AdConfigParser.parse(
            """{"native_home":{"id":"x","isEnable":true,"components":["cta","body"]}}""".reader(),
        ).getValue("native_home")

        assertNull(unit.positionCTA)
        assertNull("no position -> the styler is free to reorder", unit.toNativeStyle().ctaPosition)
    }

    @Test
    fun `an explicit null reads the same as absent`() {
        val unit = AdConfigParser.parse(
            """{"native_home":{"id":"x","isEnable":true,"positionCTA":null}}""".reader(),
        ).getValue("native_home")

        assertNull(unit.positionCTA)
    }

    @Test
    fun `a blank value is not a position`() {
        val unit = AdConfigParser.parse(
            """{"native_home":{"id":"x","isEnable":true,"positionCTA":"  "}}""".reader(),
        ).getValue("native_home")

        assertNull(unit.positionCTA)
    }

    @Test
    fun `a named position is normalised and carried into the style`() {
        val unit = AdConfigParser.parse(
            """{"native_lang":{"id":"x","isEnable":true,"positionCTA":"top"}}""".reader(),
        ).getValue("native_lang")

        assertEquals("TOP", unit.positionCTA)
        assertEquals("TOP", unit.toNativeStyle().ctaPosition)
    }

    @Test
    fun `the shipped config gives a position to the onboarding placements and none to the rest`() {
        val onboarding = listOf("native_lang", "native_lang_alt", "native_ob1", "native_ob2", "native_ob3", "native_fs")
        val appOwned = listOf("native_home", "native_permission", "native_survey", "native_welcome")

        listOf("../app/src/main/assets/ad_config.json", "../app/src/main/assets/ad_config_debug.json")
            .forEach { path ->
                val units = AdConfigParser.parse(java.io.File(path).reader())
                onboarding.forEach {
                    assertEquals("$path: $it drives an onboarding layout", "BOTTOM", units.getValue(it).positionCTA)
                }
                appOwned.forEach {
                    assertNull("$path: $it orders through components", units.getValue(it).positionCTA)
                }
            }
    }
}
