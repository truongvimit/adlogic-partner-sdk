package io.suite.firebase

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteConfigClientTest {
    @After fun after() { RemoteConfigClient.reset() }

    @Test fun concurrentConsumersShareOneFetchAndSuccessIsMemoized() = runBlocking {
        RemoteConfigClient.reset()
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async(Dispatchers.Default) { RemoteConfigClient.fetchShared(5000) {
            calls.incrementAndGet(); start.complete(Unit); release.await(); true
        } }
        start.await()
        val second = async(Dispatchers.Default) { RemoteConfigClient.fetchShared(5000) { calls.incrementAndGet(); true } }
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(RemoteConfigClient.fetchShared(5000) { calls.incrementAndGet(); true })
        assertEquals(1, calls.get())
    }

    @Test fun canceledLeaderUnblocksFollowersAndPermitsRetry() = runBlocking {
        RemoteConfigClient.reset()
        val started = CompletableDeferred<Unit>()
        val leader = async { RemoteConfigClient.fetchShared(5000) { started.complete(Unit); awaitCancellation() } }
        started.await()
        val follower = async(start = CoroutineStart.UNDISPATCHED) { RemoteConfigClient.fetchShared(5000) { fail("duplicate fetch"); false } }
        leader.cancelAndJoin()
        assertFalse(follower.await())
        assertTrue(RemoteConfigClient.fetchShared(5000) { true })
    }

    @Test fun shortFollowerTimeoutDoesNotCancelLongerLeader() = runBlocking {
        RemoteConfigClient.reset()
        val release = CompletableDeferred<Unit>()
        val leader = async(start = CoroutineStart.UNDISPATCHED) { RemoteConfigClient.fetchShared(5000) { release.await(); true } }
        assertFalse(RemoteConfigClient.fetchShared(1) { fail("duplicate fetch"); false })
        release.complete(Unit)
        assertTrue(leader.await())
    }

    @Test fun absentFirebaseDoesNotEraseConsumerCacheAndFutureInitializationMayRetry() = runBlocking {
        val previous = RemoteConfigClient.provider
        try {
            RemoteConfigClient.reset()
            RemoteConfigClient.provider = { null }
            assertFalse(RemoteConfigClient.fetchOnce(100))
            assertNull(RemoteConfigClient.remoteString("retention_config"))
            assertTrue(RemoteConfigClient.fetchShared(1000) { true })
        } finally { RemoteConfigClient.provider = previous }
    }
}
