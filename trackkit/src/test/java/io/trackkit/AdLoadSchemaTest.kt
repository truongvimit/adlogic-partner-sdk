package io.trackkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the public schema after Tracker validation and transport to an actual sink. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdLoadSchemaTest {

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
    fun `attempt and tier fields survive strict validation and reach the sink`() {
        Tracker.track(TrackkitEvents.Ad.Request("home", AdFormat.NATIVE, "first", "attempt-1"))
        Tracker.track(
            TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, "first", "attempt-1", 1, "load_failed", 3, 20L),
        )
        Tracker.track(
            TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, "winner", "attempt-1", 2, "loaded", latencyMs = 22L),
        )
        Tracker.track(TrackkitEvents.Ad.Loaded("home", AdFormat.NATIVE, "winner", 42L, "attempt-1"))

        assertEquals(listOf("ad_request", "ad_tier_result", "ad_tier_result", "ad_loaded"), sink.names())
        sink.events.forEach { (_, params) ->
            assertEquals("home", params["placement"])
            assertEquals("native", params["ad_format"])
            assertEquals("attempt-1", params["attempt_id"])
        }
        assertEquals(listOf("first", "first", "winner", "winner"), sink.events.map { it.second["ad_unit_id"] })
        assertEquals(1, sink.events[1].second["tier_index"])
        assertEquals("load_failed", sink.events[1].second["outcome"])
        assertEquals(3, sink.events[1].second["error_code"])
        assertEquals(20L, sink.events[1].second["latency_ms"])
        assertEquals(2, sink.events[2].second["tier_index"])
        assertEquals("loaded", sink.events[2].second["outcome"])
        assertEquals(22L, sink.events[2].second["latency_ms"])
        assertEquals(42L, sink.events[3].second["latency_ms"])
        assertFalse(sink.events[3].second.containsKey("outcome"))
    }

    @Test
    fun `failed attempt carries error duration and the same correlation id`() {
        Tracker.track(
            TrackkitEvents.Ad.LoadFailed("resume", AdFormat.APP_OPEN, "last", 2, 123L, "attempt-2"),
        )

        val (name, params) = sink.events.single()
        assertEquals("ad_load_failed", name)
        assertEquals("resume", params["placement"])
        assertEquals("app_open", params["ad_format"])
        assertEquals("last", params["ad_unit_id"])
        assertEquals("attempt-2", params["attempt_id"])
        assertEquals(2, params["error_code"])
        assertEquals(123L, params["latency_ms"])
    }

    @Test
    fun `legacy producers and optional diagnostics omit absent fields at the sink`() {
        Tracker.track(TrackkitEvents.Ad.Request("home", AdFormat.NATIVE, null))
        Tracker.track(TrackkitEvents.Ad.Loaded("home", AdFormat.NATIVE, null))
        Tracker.track(TrackkitEvents.Ad.LoadFailed("home", AdFormat.NATIVE, null))
        Tracker.track(TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, null, "attempt-3", 1, "timeout"))

        sink.events.forEach { (_, params) ->
            assertFalse(params.containsKey("ad_unit_id"))
            assertFalse(params.containsKey("latency_ms"))
            assertFalse(params.containsKey("error_code"))
        }
        sink.events.take(3).forEach { (_, params) -> assertFalse(params.containsKey("attempt_id")) }
        assertEquals("attempt-3", sink.events.last().second["attempt_id"])
        assertEquals("timeout", sink.events.last().second["outcome"])
    }

    @Test
    fun `tier outcomes and configured position gaps remain distinct diagnostics`() {
        listOf("loaded", "load_failed", "timeout").forEachIndexed { index, outcome ->
            Tracker.track(
                TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, "unit", "attempt-4", index * 2 + 1, outcome),
            )
        }

        assertEquals(listOf("ad_tier_result", "ad_tier_result", "ad_tier_result"), sink.names())
        assertEquals(listOf("loaded", "load_failed", "timeout"), sink.events.map { it.second["outcome"] })
        assertEquals(listOf(1, 3, 5), sink.events.map { it.second["tier_index"] })
    }

    private class RecordingSink : TrackSink {
        override val id: String = "ad-load-schema"
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun onEvent(name: String, params: Map<String, Any?>) {
            events.add(name to params)
        }

        fun names(): List<String> = events.map { it.first }
    }
}
