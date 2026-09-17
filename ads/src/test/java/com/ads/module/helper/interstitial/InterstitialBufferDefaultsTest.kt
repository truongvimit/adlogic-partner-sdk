package com.ads.module.helper.interstitial

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.settings.AdBehavior
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InterstitialBufferDefaultsTest {
    @Before fun reset() {
        InterstitialAutoBuffer.stop()
        AdBehavior.document.acceptSuccessfulFetch(null)
        InterstitialAutoBuffer.configure(InterstitialBufferOptions())
        InterstitialFrequency.reset()
    }

    @After fun cleanup() = reset()

    @Test fun `bundled all and back rules control real clocks and tap gates`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf(ALL, BACK)))
        val options = InterstitialAutoBuffer.options()
        assertEquals(setOf(ALL, BACK), options.placements.toSet())
        assertFalse(AdBehavior.bool("interstitial_auto_buffer.shared_config"))
        assertTrue(options.isPlacementEnabled(ALL))
        assertTrue(options.isPlacementEnabled(BACK))
        InterstitialFrequency.activate()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(30_000L, InterstitialFrequency.remainingMs(context, ALL))
        assertEquals(30_000L, InterstitialFrequency.remainingMs(context, BACK))
        InterstitialFrequency.recordAction(ALL)
        assertFalse(InterstitialFrequency.hasTaps(ALL))
        InterstitialFrequency.recordAction(ALL)
        assertTrue(InterstitialFrequency.hasTaps(ALL))
        assertFalse(InterstitialFrequency.hasTaps(BACK))
        InterstitialFrequency.recordAction(BACK)
        assertTrue(InterstitialFrequency.hasTaps(BACK))
        InterstitialFrequency.onShown(ALL)
        assertFalse(InterstitialFrequency.hasTaps(ALL))
        assertTrue(InterstitialFrequency.hasTaps(BACK))
    }

    @Test fun `host values override bundled rules and remote values override host`() {
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(
            independentIntervalPlacements = setOf(ALL, BACK),
            placements = listOf(ALL, BACK),
            tapThresholds = mapOf(ALL to 0),
            intervalMsByPlacement = mapOf(ALL to 0L),
        ))
        assertTrue(InterstitialFrequency.hasTaps(ALL))
        assertEquals(0L, InterstitialFrequency.intervalMs(ALL))
        assertEquals(30_000L, InterstitialFrequency.intervalMs(BACK))
        AdBehavior.document.acceptSuccessfulFetch("""{
            "interstitial_auto_buffer":{"rules":{
                "inter_all":{"interval_ms":7000,"tap_threshold":1}
            }}
        }""")
        assertEquals(7_000L, InterstitialFrequency.intervalMs(ALL))
        assertFalse(InterstitialFrequency.hasTaps(ALL))
        InterstitialFrequency.recordAction(ALL)
        assertTrue(InterstitialFrequency.hasTaps(ALL))
    }

    @Test fun `bundled rules still require host opt in and respect remote disable`() {
        assertFalse(InterstitialAutoBuffer.owns(ALL))
        assertFalse(InterstitialAutoBuffer.owns(BACK))
        assertFalse(InterstitialAutoBuffer.options().isPlacementEnabled(ALL))
        assertFalse(InterstitialAutoBuffer.isRunning())
        InterstitialAutoBuffer.configure(InterstitialBufferOptions(listOf(ALL, BACK)))
        AdBehavior.document.acceptSuccessfulFetch("""{
            "interstitial_auto_buffer":{"rules":{"inter_back":{"enabled":false}}}
        }""")
        assertTrue(InterstitialAutoBuffer.options().isPlacementEnabled(ALL))
        assertFalse(InterstitialAutoBuffer.options().isPlacementEnabled(BACK))
    }

    private companion object {
        const val ALL = "inter_all"
        const val BACK = "inter_back"
    }
}
