package io.trackkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public bound events pass strict validation and arrive independently of presentation/revenue. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdBoundSchemaTest {
    private lateinit var sink: RecordingSink

    @Before
    fun setUp() {
        Tracker.resetForTesting()
        sink = RecordingSink()
        Tracker.addSink(sink)
        Tracker.install(
            ApplicationProvider.getApplicationContext<Context>(),
            TrackerConfig(strictValidation = true, logLevel = 0),
        )
    }

    @After
    fun tearDown() {
        Tracker.resetForTesting()
    }

    @Test
    fun `bound carries native placement and unit without creating show or revenue`() {
        Tracker.track(TrackkitEvents.Ad.Bound("language", AdFormat.NATIVE, "native-unit"))

        val (name, params) = sink.events.single()
        assertEquals("ad_bound", name)
        assertEquals("language", params["placement"])
        assertEquals("native", params["ad_format"])
        assertEquals("native-unit", params["ad_unit_id"])
        assertFalse(params.containsKey("attempt_id"))
        assertFalse(params.containsKey("latency_ms"))
        assertFalse(params.containsKey("value"))
        assertEquals(0, sink.revenueCount)
        assertTrue("ad_bound" in TrackkitEvents.all())
        assertFalse("ad_bound" in TrackkitEvents.revenueEvents())
    }

    @Test
    fun `bound omits an unknown unit and remains separate from actual show`() {
        Tracker.track(TrackkitEvents.Ad.Bound("onboarding", AdFormat.NATIVE_FULL_SCREEN))

        assertEquals(listOf("ad_bound"), sink.events.map { it.first })
        val boundParams = sink.events.single().second
        assertEquals("onboarding", boundParams["placement"])
        assertEquals("native_full_screen", boundParams["ad_format"])
        assertFalse(boundParams.containsKey("ad_unit_id"))

        Tracker.track(TrackkitEvents.Ad.Show("onboarding", AdFormat.NATIVE_FULL_SCREEN))

        assertEquals(listOf("ad_bound", "ad_show"), sink.events.map { it.first })
        assertEquals(0, sink.revenueCount)
    }

    private class RecordingSink : TrackSink {
        override val id: String = "ad-bound-schema"
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var revenueCount = 0

        override fun onEvent(name: String, params: Map<String, Any?>) {
            events += name to params
        }

        override fun onAdRevenue(impression: AdImpression) {
            revenueCount++
        }
    }
}
