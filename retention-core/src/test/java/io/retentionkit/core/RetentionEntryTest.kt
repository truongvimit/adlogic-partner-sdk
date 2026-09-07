package io.retentionkit.core

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionEntryTest {
    private val clock = TestClock()
    private fun entry(destination: String = "notes") = RetentionEntry(RetentionEntrySource.DAILY, destination, "open", createdAtMillis = clock.now)

    @Test fun entrySurvivesRestartSetupAndHasOneAtomicFinalConsumption() {
        val store = testStore()
        val entries = RetentionEntries(store, clock)
        val value = entry()
        val intent = RetentionEntryCodec.write(Intent(), value)
        assertTrue(entries.capture(intent) is RetentionEntryAcceptance.Accepted)
        // Recapturing through an intermediate splash does not consume the entry.
        assertTrue(entries.capture(Intent(intent)) is RetentionEntryAcceptance.Accepted)
        val restored = RetentionEntries(store, clock)
        assertEquals(value, restored.pending(value.token))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..12).map { Callable { restored.consume(value.token) } }).map { it.get() }
            assertEquals(1, results.count { it })
        } finally { executor.shutdownNow() }
        assertNull(restored.pending(value.token))
        assertEquals("already_consumed", (restored.capture(intent) as RetentionEntryAcceptance.Rejected).reason)
    }

    @Test fun reusableWidgetMakesFreshTokenPerDeliveredIntentButRecreationKeepsToken() {
        val entries = RetentionEntries(testStore(), clock)
        val template = RetentionEntryCodec.write(Intent(), entry().copy(source = RetentionEntrySource.WIDGET, instanceId = "23", mode = RetentionEntryMode.REUSABLE))
        val delivered = Intent(template)
        val first = (entries.capture(delivered) as RetentionEntryAcceptance.Accepted).entry
        val recreate = (entries.capture(Intent(delivered)) as RetentionEntryAcceptance.Accepted).entry
        assertEquals(first.token, recreate.token)
        assertTrue(entries.consume(first.token))
        val next = (entries.capture(Intent(template)) as RetentionEntryAcceptance.Accepted).entry
        assertNotEquals(first.token, next.token)
        assertTrue(entries.consume(next.token))
        assertEquals(RetentionEntryMode.REUSABLE, (RetentionEntryCodec.read(template) as RetentionEntryDecodeResult.Valid).entry.mode)
    }

    @Test fun eachActionAndWidgetInstanceHasFilterIdentityAndPreservesHostIntent() {
        val original = Intent("host.action", Uri.parse("partner://feature/notes")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val base = entry().copy(mode = RetentionEntryMode.REUSABLE, instanceId = "1")
        val a = RetentionEntryCodec.write(Intent(original), base)
        val b = RetentionEntryCodec.write(Intent(original), base.copy(destination = "guide", actionId = "guide"))
        val c = RetentionEntryCodec.write(Intent(original), base.copy(instanceId = "2"))
        assertFalse(a.filterEquals(b))
        assertFalse(a.filterEquals(c))
        assertEquals(original.data, a.data)
        assertEquals(original.flags, a.flags)
        val sameReusable = RetentionEntryCodec.write(Intent(original), base.copy(token = "new-token"))
        assertTrue(a.filterEquals(sameReusable))
    }

    @Test fun malformedExternalEnvelopeAndTokenCollisionAreRejected() {
        assertTrue(RetentionEntryCodec.decode("x".repeat(8193)) is RetentionEntryDecodeResult.Invalid)
        assertTrue(RetentionEntryCodec.decode("{\"version\":2}") is RetentionEntryDecodeResult.Invalid)
        assertTrue(RetentionEntryCodec.decode("{\"version\":1}") is RetentionEntryDecodeResult.Invalid)
        assertTrue(RetentionEntryCodec.read(Intent().putExtra(RetentionEntryCodec.EXTRA_ENTRY, 1)) is RetentionEntryDecodeResult.Absent)
        assertEquals("invalid_destination", RetentionEntryCodec.validate(entry("file:///private")))
        val entries = RetentionEntries(testStore(), clock)
        val first = entry()
        entries.stage(first)
        assertEquals("token_collision", (entries.stage(first.copy(destination = "other")) as RetentionEntryAcceptance.Rejected).reason)
        assertEquals(first, entries.pending(first.token))
    }

    @Test fun expiryAndCapacityNeverEvictAStillValidPendingNavigation() {
        val entries = RetentionEntries(testStore(), clock)
        val expires = entry().copy(expiresAtMillis = clock.now + 100)
        entries.stage(expires)
        clock.advance(100)
        assertFalse(entries.consume(expires.token))
        assertTrue(entries.pending().isEmpty())
        repeat(32) { assertTrue(entries.stage(entry()) is RetentionEntryAcceptance.Accepted) }
        assertEquals("pending_capacity", (entries.stage(entry()) as RetentionEntryAcceptance.Rejected).reason)
        assertEquals(32, entries.pending().size)
        clock.advance(7L * 24 * 60 * 60 * 1000)
        assertTrue(entries.pending().isEmpty())
    }
}
