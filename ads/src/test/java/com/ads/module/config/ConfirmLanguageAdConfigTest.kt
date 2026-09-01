package com.ads.module.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the shipped ad configs must say about the Confirm Language modal — and the whole-file rule
 * the modal's keys had to obey to be added at all.
 *
 * [AdRemoteConfig.initializeFromAssets] picks the debug document on a debuggable build precisely so
 * a debug run never spends a real ad unit; that guarantee is a property of the file's contents, and
 * nothing but a test checks it.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.13 ships framework jars up to 34; the module compiles against 36.
@Config(sdk = [34])
class ConfirmLanguageAdConfigTest {

    private val release = "../app/src/main/assets/ad_config.json"
    private val debug = "../app/src/main/assets/ad_config_debug.json"

    /** Google's sample publisher. Every id under it is a test unit and earns nothing. */
    private val testPublisher = "ca-app-pub-3940256099942544"

    private fun units(path: String): Map<String, AdUnitConfig> =
        AdConfigParser.parse(File(path).reader())

    @Test
    fun `both documents declare both floors of the confirm modal`() {
        listOf(release, debug).forEach { path ->
            val units = units(path)
            listOf("native_popup_lang_high", "native_popup_lang").forEach { key ->
                val unit = units[key]
                assertTrue("$path: $key is missing", unit != null)
                assertTrue("$path: $key is declared but unusable", unit!!.isUsable)
            }
        }
    }

    @Test
    fun `the confirm modal resolves to a usable waterfall, high floor first`() {
        listOf(release, debug).forEach { path ->
            val tiers = AdRemoteConfig(units(path)).tiersFor("native_popup_lang")
            assertTrue("$path: no floor resolved for native_popup_lang", tiers.isNotEmpty())
            assertEquals(
                "$path: the high floor must be requested first",
                units(path).getValue("native_popup_lang_high").id,
                tiers.first(),
            )
        }
    }

    @Test
    fun `a debug build spends only test ad units`() {
        val real = units(debug).filterValues { !it.id.startsWith(testPublisher) }
        assertTrue(
            "debug config spends real ad units, which is invalid traffic on the app's own " +
                "account: ${real.keys.sorted()}",
            real.isEmpty(),
        )
    }

    @Test
    fun `the release confirm slot is not left on a test unit`() {
        listOf("native_popup_lang_high", "native_popup_lang").forEach { key ->
            assertTrue(
                "$key still points at Google's test publisher in the release config",
                !units(release).getValue(key).id.startsWith(testPublisher),
            )
        }
    }

    @Test
    fun `the two documents declare the same placements`() {
        // A key in one file only is a placement that silently has no ad in the other build.
        assertEquals(
            "release and debug ad configs have drifted apart",
            units(release).keys.sorted(),
            units(debug).keys.sorted(),
        )
    }

    @Test
    fun `the confirm slot names no CTA position and keeps the compact button`() {
        listOf(release, debug).forEach { path ->
            listOf("native_popup_lang_high", "native_popup_lang").forEach { key ->
                val unit = units(path).getValue(key)
                // The modal ships one fixed layout (ob_layout_native_dialog), so there is no
                // position to choose between and `components` is read for visibility only.
                assertNull("$path: $key must not name a CTA position", unit.positionCTA)
                // 36dp is the design's compact button and the styler's floor; the 45 the other
                // slots use would push the CTA past the 112dp media it sits beside.
                assertEquals("$path: $key CTA height", 36, unit.toNativeStyle().ctaHeightDp)
            }
        }
    }
}
