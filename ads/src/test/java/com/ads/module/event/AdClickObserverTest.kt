package com.ads.module.event

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ads.module.admob.Admob
import com.ads.module.funtion.AdmobHelper
import io.trackkit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdClickObserverTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val handles = mutableListOf<AutoCloseable>()
    @After fun after() { handles.forEach { it.close() }; Tracker.resetForTesting(); Admob.getInstance().setMaxClickAdsPerDay(0) }
    private fun observe(owner: String, observer: AdClickObserver): AutoCloseable = ERainLogEventManager.observeAdClicks(owner, observer).also(handles::add)

    @Test fun replacingOneOwnerAvoidsDuplicatesAndOldHandleCannotCloseReplacementOrOtherOwner() {
        val received = mutableListOf<String>()
        val old = observe("owner") { _, _ -> received.add("old") }
        val current = observe("owner") { _, _ -> received.add("current") }
        observe("other") { _, _ -> received.add("other") }
        old.close()
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(setOf("current", "other"), received.toSet())
        assertEquals(2, received.size)
        current.close(); current.close()
        received.clear()
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(listOf("other"), received)
    }

    @Test fun synchronousThrowingObserverCannotBreakOtherObserversTrackerOrDailyCap() {
        val order = mutableListOf<String>()
        val clickIds = mutableListOf<String>()
        Tracker.install(app, TrackerConfig(strictValidation = true))
        Tracker.addSink(object : TrackSink {
            override val id = "observer-test"
            override fun onEvent(name: String, params: Map<String, Any?>) { if (name == TrackkitEvents.AD_CLICK) order.add("tracker") }
        })
        observe("broken") { _, _ -> throw IllegalStateException("consumer failed") }
        observe("working") { id, _ -> order.add("observer"); clickIds.add(id) }
        Admob.getInstance().setMaxClickAdsPerDay(3)
        val unit = "unit-${UUID.randomUUID()}"
        ERainLogEventManager.logClickAdsEvent(app, unit)
        assertEquals(listOf("observer", "tracker"), order)
        assertEquals(1, AdmobHelper.getNumClickAdsPerDay(app, unit))
        assertEquals(1, clickIds.size)
        assertNotNull(UUID.fromString(clickIds.single()))
    }

    @Test fun closedOrLateObserversNeverReceiveBufferedHistoricalClicks() {
        Tracker.install(app, TrackerConfig(consentPolicy = ConsentPolicy.QUEUE_UNTIL_RESOLVED))
        val observed = mutableListOf<String>()
        val first = observe("first") { id, _ -> observed.add(id) }
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(1, observed.size) // Actual click arrives before unresolved analytics consent.
        first.close()
        observe("late") { id, _ -> observed.add(id) }
        Tracker.setConsent(analytics = true, ads = true)
        Tracker.flushPending()
        assertEquals(1, observed.size)
        ERainLogEventManager.logClickAdsEvent(app, "unit")
        assertEquals(2, observed.size)
        assertNotEquals(observed[0], observed[1])
    }
}
