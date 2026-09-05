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

/** Public catalog constructors pass strict Tracker validation without manufacturing correlation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdPresentationSchemaTest {
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        Tracker.resetForTesting()
        Tracker.addSink(object : TrackSink {
            override val id = "presentation-schema"
            override fun onEvent(name: String, params: Map<String, Any?>) { events += name to params }
        })
        Tracker.install(ApplicationProvider.getApplicationContext<Context>(),
            TrackerConfig(strictValidation = true, logLevel = 0))
    }

    @After
    fun tearDown() { Tracker.resetForTesting() }

    @Test
    fun `presentation and terminal events carry the accepted load correlation without changing payloads`() {
        Tracker.track(TrackkitEvents.Ad.Show("app_resume", AdFormat.APP_OPEN, "unit-a", "attempt-a"))
        Tracker.track(TrackkitEvents.Ad.ShowFailed("app_resume", AdFormat.APP_OPEN, "unit-a", 7, "attempt-a"))
        Tracker.track(TrackkitEvents.Ad.Closed("app_resume", AdFormat.APP_OPEN, "unit-a", "attempt-a"))

        assertEquals(listOf("ad_show", "ad_show_failed", "ad_closed"), events.map { it.first })
        events.forEach { (_, params) ->
            assertEquals("app_resume", params["placement"])
            assertEquals("app_open", params["ad_format"])
            assertEquals("unit-a", params["ad_unit_id"])
            assertEquals("attempt-a", params["attempt_id"])
            assertFalse(params.containsKey("latency_ms"))
        }
        assertEquals(7, events[1].second["error_code"])
        assertFalse(events[0].second.containsKey("error_code"))
        assertFalse(events[2].second.containsKey("error_code"))
    }

    @Test
    fun `legacy Kotlin defaults omit correlation unit and error instead of adding null parameters`() {
        Tracker.track(TrackkitEvents.Ad.Show("home", AdFormat.INTERSTITIAL))
        Tracker.track(TrackkitEvents.Ad.ShowFailed("home", AdFormat.INTERSTITIAL))
        Tracker.track(TrackkitEvents.Ad.Closed("home", AdFormat.INTERSTITIAL))

        assertEquals(listOf("ad_show", "ad_show_failed", "ad_closed"), events.map { it.first })
        events.forEach { (_, params) ->
            assertEquals("home", params["placement"])
            assertFalse(params.containsKey("attempt_id"))
            assertFalse(params.containsKey("ad_unit_id"))
            assertFalse(params.containsKey("error_code"))
        }
    }

    @Test
    fun `explicit null correlation has legacy omission semantics`() {
        Tracker.track(TrackkitEvents.Ad.Show("home", AdFormat.INTERSTITIAL, "unit", null))
        Tracker.track(TrackkitEvents.Ad.ShowFailed("home", AdFormat.INTERSTITIAL, "unit", null, null))
        Tracker.track(TrackkitEvents.Ad.Closed("home", AdFormat.INTERSTITIAL, "unit", null))

        assertEquals(3, events.size)
        events.forEach { (_, params) ->
            assertEquals("unit", params["ad_unit_id"])
            assertFalse(params.containsKey("attempt_id"))
            assertFalse(params.containsKey("error_code"))
        }
    }
}
