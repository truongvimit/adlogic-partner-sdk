package com.itg.template.retention

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.retentionkit.*
import io.retentionkit.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExampleSuccessDeliveryTest {
    private lateinit var app: Application
    private lateinit var failing: FailingStore
    private lateinit var kit: RetentionKit
    private val clock = object : RetentionClock {
        var now = 1000L
        override fun wallTimeMillis() = now
        override fun elapsedRealtimeMillis() = now
    }
    @Before fun installIsolatedCore() {
        RetentionRuntime.uninstallForTests()
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("outbox_core_test", Context.MODE_PRIVATE).edit().clear().commit()
        failing = FailingStore(SharedPreferencesRetentionStore(app, "outbox_core_test"))
        kit = (RetentionKit.install(app, RetentionKitOptions(
            featureProvider = RetentionFeatureProvider.EMPTY,
            router = RetentionRouter { context, _ -> Intent(context, RetentionPlaygroundActivity::class.java) },
            notifications = null, widgets = null, feedback = null, review = null,
            clock = clock, store = failing,
        )) as RetentionKitInstallResult.Installed).kit
    }
    @After fun shutdown() { RetentionRuntime.uninstallForTests() }
    @Test fun failedCoreStateCommitRetainsOutboxDespiteAcceptedQueueBoolean() {
        val data = ExampleDataStore(app)
        data.record("durable-success", "notes", "A useful note")
        clock.now = 2000
        failing.failUserState = true
        RetentionExample.flushSuccesses(app)
        assertEquals("durable-success", data.pendingSuccesses().single().id)
        failing.failUserState = false
        RetentionExample.flushSuccesses(app)
        assertTrue(data.pendingSuccesses().isEmpty())
    }
    @Test fun queuedAndReplayedSubmissionIsAcknowledgedOnlyAfterActualDispatch() {
        val data = ExampleDataStore(app)
        data.record("queued-success", "text_tools", "3 words")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()
        kit.runtime.subscribe("test.blocker") { signal ->
            if (signal is RetentionSignal.ExternalTransitionFinished && signal.token == "hold") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            if (signal is RetentionSignal.BusinessSuccess) received.add(signal.eventId)
        }
        val worker = Thread { kit.runtime.signal(RetentionSignal.ExternalTransitionFinished("hold")) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            RetentionExample.flushSuccesses(app)
            RetentionExample.flushSuccesses(app)
            assertEquals(1, data.pendingSuccesses().size)
            assertTrue(received.isEmpty())
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(listOf("queued-success", "queued-success"), received.toList())
        assertTrue(data.pendingSuccesses().isEmpty())
    }
    private class FailingStore(private val delegate: RetentionStore) : RetentionStore {
        var failUserState = false
        override fun snapshot(namespace: String) = delegate.snapshot(namespace)
        override fun <T> transaction(namespace: String, block: (RetentionTransaction) -> T): T {
            if (failUserState && namespace == "core.user") throw RetentionStorageException("Injected state commit failure")
            return delegate.transaction(namespace, block)
        }
    }
}
