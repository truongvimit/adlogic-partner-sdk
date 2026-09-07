package io.retentionkit.notifications

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NotificationAlarmExecutionTest {
    @Test fun unusedReceiptFinishesImmediatelyAndCannotBeRetainedLater() {
        val deadlines = ManualNotificationDeadlines()
        val finishes = AtomicInteger()
        val owner = NotificationAlarmExecution({ finishes.incrementAndGet() }, deadlines, { deadlines.now })
        owner.finishIfUnused()
        owner.close()
        deadlines.advance(60_000)
        assertEquals(1, finishes.get())
        assertFalse(owner.retain())
        assertNull(owner.acquire(20_000) { fail("Expired receipt cannot acquire"); AutoCloseable {} })
    }

    @Test fun confirmationHasIndependentTwoSecondDeadlineAndCannotRenewItByRetainingAgain() {
        val deadlines = ManualNotificationDeadlines()
        val finishes = AtomicInteger()
        val owner = NotificationAlarmExecution({ finishes.incrementAndGet() }, deadlines, { deadlines.now })
        assertTrue(owner.retain())
        deadlines.advance(1999)
        assertTrue(owner.retain())
        assertEquals(0, finishes.get())
        deadlines.advance(1)
        assertEquals(1, finishes.get())
        assertNull(owner.acquire(20_000) { fail("Late confirmation must not start a raw lease"); AutoCloseable {} })
    }

    @Test fun cancelTimeoutAndOldLeaseCloseReleaseRawBeforeExactlyOneFinish() {
        val deadlines = ManualNotificationDeadlines()
        val order = mutableListOf<String>()
        val owner = NotificationAlarmExecution({ order += "finish" }, deadlines, { deadlines.now })
        owner.retain()
        val lease = owner.acquire(20_000) { AutoCloseable { order += "raw_close" } }!!
        deadlines.advance(19_999)
        assertTrue(order.isEmpty())
        deadlines.advance(1)
        assertEquals(listOf("raw_close", "finish"), order)
        lease.close(); owner.close(); deadlines.advance(60_000)
        assertEquals(listOf("raw_close", "finish"), order)
    }

    @Test fun deadlineDuringAcquireKeepsReceiptUntilReturnedRawHandleIsClosed() {
        val deadlines = ManualNotificationDeadlines()
        val started = CountDownLatch(1)
        val returnRaw = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val result = AtomicReference<AutoCloseable?>()
        val error = AtomicReference<Throwable?>()
        val owner = NotificationAlarmExecution({ order += "finish" }, deadlines, { deadlines.now })
        owner.retain()
        val thread = Thread {
            try {
                result.set(owner.acquire(20_000) {
                    started.countDown()
                    check(returnRaw.await(3, TimeUnit.SECONDS))
                    AutoCloseable { order += "raw_close" }
                })
            } catch (failure: Throwable) { error.set(failure) }
        }
        thread.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            deadlines.advance(20_000)
            assertTrue("Receipt must not finish before a late raw handle can be closed", order.isEmpty())
            assertFalse(owner.isActive())
        } finally { returnRaw.countDown(); thread.join(3000) }
        assertFalse(thread.isAlive)
        assertNull(error.get())
        assertNull(result.get())
        assertEquals(listOf("raw_close", "finish"), order)
        owner.close()
        assertEquals(listOf("raw_close", "finish"), order)
    }

    @Test fun failedRawCloseStillFinishesReceiptAndCannotLaterReportSuccessfulClose() {
        val deadlines = ManualNotificationDeadlines()
        var finishes = 0
        val failure = IllegalStateException("raw release failed")
        val owner = NotificationAlarmExecution({ finishes++ }, deadlines, { deadlines.now })
        owner.retain()
        val lease = owner.acquire(20_000) { AutoCloseable { throw failure } }!!
        assertSame(failure, runCatching { owner.close() }.exceptionOrNull())
        assertEquals(1, finishes)
        assertSame("A delayed controller close must retain the actual cleanup failure", failure,
            runCatching { lease.close() }.exceptionOrNull())
        assertEquals(1, finishes)
    }

    @Test fun oldOwnerCleanupCannotCloseAReplacementOwner() {
        val deadlines = ManualNotificationDeadlines()
        val order = mutableListOf<String>()
        val first = NotificationAlarmExecution({ order += "first_finish" }, deadlines, { deadlines.now })
        first.retain()
        val oldLease = first.acquire(20_000) { AutoCloseable { order += "first_close" } }!!
        first.close()
        val second = NotificationAlarmExecution({ order += "second_finish" }, deadlines, { deadlines.now })
        second.retain()
        second.acquire(20_000) { AutoCloseable { order += "second_close" } }
        oldLease.close()
        assertEquals(listOf("first_close", "first_finish"), order)
        assertTrue(second.isActive())
        second.close()
        assertEquals(listOf("first_close", "first_finish", "second_close", "second_finish"), order)
    }
}

/** Monotonic deadline runner, independent from the module's main-handler confirmation callbacks. */
internal class ManualNotificationDeadlines : NotificationDeadlines {
    @Volatile var now = 0L
        private set
    private data class Task(val at: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    private val work = mutableListOf<Task>()
    override fun post(delayMillis: Long, callback: () -> Unit): AutoCloseable {
        val task = Task(now + delayMillis, callback)
        synchronized(work) { work += task }
        return AutoCloseable { synchronized(work) { task.cancelled = true } }
    }
    fun advance(millis: Long) {
        now += millis
        while (true) {
            val task = synchronized(work) {
                work.filter { !it.cancelled && it.at <= now }.minByOrNull { it.at }?.also { work.remove(it) }
            } ?: return
            task.callback()
        }
    }
}
