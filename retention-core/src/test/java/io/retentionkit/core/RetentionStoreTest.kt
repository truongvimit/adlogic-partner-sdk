package io.retentionkit.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionStoreTest {
    @Test fun concurrentWritersAcrossStoreInstancesCommitWholeClaimAndBudget() {
        val app = testApplication()
        val name = "concurrent_${UUID.randomUUID()}"
        val first = SharedPreferencesRetentionStore(app, name)
        val second = SharedPreferencesRetentionStore(app, name)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((0 until 24).map { index -> Callable {
                val store = if (index % 2 == 0) first else second
                store.transaction("notifications.delivery") { state ->
                    val count = state.long("count")
                    if (count >= 3 || state.boolean("claimed:${index % 4}")) false else {
                        state.put("count", count + 1)
                        state.put("claimed:${index % 4}", true)
                        true
                    }
                }
            } }).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(3, results.count { it })
            val restored = SharedPreferencesRetentionStore(app, name).snapshot("notifications.delivery")
            assertEquals(3, restored.long("count"))
            assertEquals(3, restored.entries().count { it.key.startsWith("claimed:") })
        } finally { executor.shutdownNow() }
    }

    @Test fun failedTransactionRollsBackEveryFieldAndSnapshotsAreDetached() {
        val store = testStore()
        store.transaction("state") { it.put("count", 2L); it.put("label", "old") }
        val before = store.snapshot("state")
        assertThrows(IllegalStateException::class.java) {
            store.transaction("state") { it.put("count", 3L); it.remove("label"); error("abort") }
        }
        assertEquals(2, store.snapshot("state").long("count"))
        store.transaction("state") { it.put("count", 4L) }
        assertEquals(2, before.long("count"))
        assertEquals("old", store.snapshot("state").string("label"))
    }

    @Test fun nestedTransactionsAreRejectedWithoutOverwritingAnyState() {
        val store = testStore()
        assertThrows(IllegalStateException::class.java) {
            store.transaction("one") { outer ->
                outer.put("value", "outer")
                store.transaction("two") { it.put("value", "inner") }
            }
        }
        assertNull(store.snapshot("one").string("value"))
        assertNull(store.snapshot("two").string("value"))
    }

    @Test fun corruptPersistedStateFailsRatherThanBecomingEmptyDefaults() {
        val app = testApplication()
        val name = "corrupt_${UUID.randomUUID()}"
        app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("config", "broken").commit()
        assertThrows(RetentionStorageException::class.java) { SharedPreferencesRetentionStore(app, name).snapshot("config") }
        assertEquals("broken", app.getSharedPreferences(name, Context.MODE_PRIVATE).getString("config", null))
    }

    @Test fun namespacesAreIndependentAndTypedDefaultsDoNotMutateState() {
        val store = testStore()
        store.transaction("one") { it.put("active", true); it.put("count", "bad") }
        assertTrue(store.snapshot("one").boolean("active"))
        assertEquals(9, store.snapshot("one").long("count", 9))
        assertFalse(store.snapshot("two").boolean("active"))
        store.transaction("one") { it.clear() }
        assertTrue(store.snapshot("one").entries().isEmpty())
    }
}
