package io.suite.firebase

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseUpdateConfigTest {
    private val firebase = mock(FirebaseRemoteConfig::class.java)
    private val value = mock(FirebaseRemoteConfigValue::class.java)
    private val enabledRule = """{"enabled":true,"minVersionCode":101,"force":true}"""

    @Before fun setup() {
        RemoteConfigClient.reset()
        RemoteConfigClient.provider = { firebase }
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forResult(false))
        `when`(firebase.getValue("force_update_config")).thenReturn(value)
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE)
    }
    @After fun cleanup() {
        RemoteConfigClient.reset()
        RemoteConfigClient.provider = { null }
    }
    @Test fun `missing blank malformed and absent enabled all stay off`() {
        for (json in listOf("", "broken json", "{}", """{"minVersionCode":101,"force":true}""",
            """{"enabled":"true","minVersionCode":101,"force":true}""")) {
            `when`(value.asString()).thenReturn(json)
            assertFalse(json, FirebaseUpdateConfig.activated().needsUpdate(100))
        }
        RemoteConfigClient.reset()
        RemoteConfigClient.provider = { null }
        assertFalse(FirebaseUpdateConfig.activated().needsUpdate(100))
    }
    @Test fun `local defaults can never activate update and reading never fetches`() {
        `when`(value.asString()).thenReturn(enabledRule)
        for (source in listOf(FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT, FirebaseRemoteConfig.VALUE_SOURCE_STATIC)) {
            `when`(value.source).thenReturn(source)
            assertFalse(FirebaseUpdateConfig.activated().needsUpdate(100))
        }
        verify(firebase, never()).fetchAndActivate()
    }
    @Test fun `explicit remote off removal and malformed policy do not reuse an old on policy`() {
        `when`(value.asString()).thenReturn(enabledRule)
        assertTrue(FirebaseUpdateConfig.activated().isRequired(100))
        `when`(value.asString()).thenReturn("""{"enabled":false,"minVersionCode":101,"force":true}""")
        assertFalse(FirebaseUpdateConfig.activated().needsUpdate(100))
        `when`(value.asString()).thenReturn("broken json")
        assertFalse(FirebaseUpdateConfig.activated().needsUpdate(100))
        `when`(value.asString()).thenReturn(enabledRule)
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_STATIC)
        assertFalse(FirebaseUpdateConfig.activated().needsUpdate(100))
    }
    @Test fun `failed fetch can use previously activated remote true but never local defaults`() = runBlocking {
        `when`(value.asString()).thenReturn(enabledRule)
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forException(IllegalStateException("offline")))
        assertTrue(FirebaseUpdateConfig.fetch().isRequired(100))
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT)
        assertFalse(FirebaseUpdateConfig.fetch().needsUpdate(100))
    }
    @Test fun `fetch waits for activation before evaluating update`() = runTest {
        RemoteConfigClient.reset(backgroundScope)
        val pending = TaskCompletionSource<Boolean>()
        `when`(firebase.fetchAndActivate()).thenReturn(pending.task)
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_STATIC)
        val policy = async { FirebaseUpdateConfig.fetch(1_000) }
        runCurrent()
        assertFalse(policy.isCompleted)
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE)
        `when`(value.asString()).thenReturn(enabledRule)
        pending.setResult(true)
        assertTrue(policy.await().isRequired(100))
        verify(firebase, times(1)).fetchAndActivate()
    }
    @Test fun `timeout without activated remote is off and late activation applies on next read`() = runTest {
        RemoteConfigClient.reset(backgroundScope)
        val pending = TaskCompletionSource<Boolean>()
        `when`(firebase.fetchAndActivate()).thenReturn(pending.task)
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_STATIC)
        assertFalse(FirebaseUpdateConfig.fetch(100).needsUpdate(100))
        `when`(value.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE)
        `when`(value.asString()).thenReturn(enabledRule)
        pending.setResult(true)
        runCurrent()
        assertTrue(FirebaseUpdateConfig.activated().isRequired(100))
        verify(firebase, times(1)).fetchAndActivate()
    }
}
