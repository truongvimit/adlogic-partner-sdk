package com.ads.module.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Debug builds may pin IDs, but all behavioral ad fields must remain release-equivalent. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DebugReleaseAdConfigBehaviorParityTest {
    private val release = "../app/src/main/assets/ad_config.json"
    private val debug = "../app/src/main/assets/ad_config_debug.json"

    @Test
    fun `debug and release differ only in ad unit identifiers`() {
        val releaseUnits = AdConfigParser.parse(File(release).reader())
        val debugUnits = AdConfigParser.parse(File(debug).reader())
        assertEquals(releaseUnits.keys.sorted(), debugUnits.keys.sorted())
        releaseUnits.keys.forEach { key ->
            val r = releaseUnits.getValue(key)
            val d = debugUnits.getValue(key)
            assertEquals("$key isEnable", r.isEnable, d.isEnable)
            assertEquals("$key enable_ua_check", r.enableUaCheck, d.enableUaCheck)
            assertEquals("$key reloadIntervalSeconds", r.reloadIntervalSeconds, d.reloadIntervalSeconds)
            assertEquals("$key app_resume_load_delay_ms", r.appResumeLoadDelayMs, d.appResumeLoadDelayMs)
            assertEquals("$key colorCTA", r.colorCTA, d.colorCTA)
            assertEquals("$key heightCTA", r.heightCTA, d.heightCTA)
            assertEquals("$key positionCTA", r.positionCTA, d.positionCTA)
            assertEquals("$key components", r.components, d.components)
            assertEquals("$key click_action", r.clickAction, d.clickAction)
        }
    }
}
