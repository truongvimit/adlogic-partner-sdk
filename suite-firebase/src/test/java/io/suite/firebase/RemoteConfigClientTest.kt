package io.suite.firebase

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteConfigClientTest {
    @After fun reset() {
        RemoteConfigClient.reset()
        RemoteConfigClient.provider = { null }
    }

    @Test fun `an exhausted caller budget does not start a network fetch`() = runTest {
        val firebase = mock(FirebaseRemoteConfig::class.java)
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)
        assertFalse(RemoteConfigClient.fetchOnce(0))
        assertFalse(RemoteConfigClient.fetchAndActivate(-1))
        runCurrent()
        verify(firebase, times(0)).fetchAndActivate()
    }

    @Test fun `a short caller timeout does not end another callers fetch`() = runTest {
        val firebase = mock(FirebaseRemoteConfig::class.java)
        val result = TaskCompletionSource<Boolean>()
        `when`(firebase.fetchAndActivate()).thenReturn(result.task)
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        val short = async { RemoteConfigClient.fetchOnce(100) }
        val patient = async { RemoteConfigClient.fetchOnce(1_000) }
        runCurrent()
        assertFalse(short.await())
        assertFalse(patient.isCompleted)
        result.setResult(false)
        assertTrue(patient.await())
        verify(firebase, times(1)).fetchAndActivate()
    }

    @Test fun `cancelling one waiter leaves the shared fetch available to another`() = runTest {
        val firebase = mock(FirebaseRemoteConfig::class.java)
        val result = TaskCompletionSource<Boolean>()
        `when`(firebase.fetchAndActivate()).thenReturn(result.task)
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        val cancelled = async { RemoteConfigClient.fetchOnce(1_000) }
        val patient = async { RemoteConfigClient.fetchAndActivate(1_000) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        result.setResult(true)
        assertTrue(patient.await())
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
        verify(firebase, times(1)).fetchAndActivate()
    }

    @Test fun `a failed fetch can retry and unchanged activation still caches success`() = runTest {
        val firebase = mock(FirebaseRemoteConfig::class.java)
        `when`(firebase.fetchAndActivate()).thenReturn(
            Tasks.forException(IllegalStateException("offline")),
            Tasks.forResult(false),
        )
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        assertFalse(RemoteConfigClient.fetchOnce(1_000))
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
        verify(firebase, times(2)).fetchAndActivate()
    }

    @Test fun `explicit refresh requests another fetch after a completed success`() = runTest {
        val firebase = mock(FirebaseRemoteConfig::class.java)
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forResult(false))
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        assertTrue(RemoteConfigClient.fetchOnce(1_000))
        assertTrue(RemoteConfigClient.fetchAndActivate(1_000))
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
        verify(firebase, times(2)).fetchAndActivate()
    }

    @Test fun `Firebase initialized after an unavailable attempt can still fetch`() = runTest {
        RemoteConfigClient.provider = { null }
        RemoteConfigClient.reset(backgroundScope)
        assertFalse(RemoteConfigClient.fetchOnce(1_000))

        val firebase = mock(FirebaseRemoteConfig::class.java)
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forResult(true))
        RemoteConfigClient.provider = { firebase }
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
    }
}
