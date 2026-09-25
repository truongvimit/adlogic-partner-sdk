package io.onboardkit.remote

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteConfigSnapshotTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clearCache() {
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun clearLegacy() {
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
    }

    @Test
    fun `the record of delivered keys survives reloading the disk snapshot`() {
        val delivered = RemoteFlags(showLanguageTapHint = true, supplied = setOf("ob_show_language_tap_hint"))
        RemoteConfigSyncer(context) { null }.applySnapshot(delivered)
        assertEquals(delivered, RemoteConfigSyncer(context) { null }.flags.value)
        assertEquals(true, OnboardingSettings.values.remoteValue("lfo.tap_hint.enabled"))
    }

    @Test
    fun `custom tap hint delay survives reloading the disk snapshot`() {
        val expected = RemoteFlags(languageTapHintDelaySec = 7)
        RemoteConfigSyncer(context) { null }.applySnapshot(expected)
        assertEquals(expected, RemoteConfigSyncer(context) { null }.flags.value)
    }

    @Test fun `failed fetch keeps the persisted parallel assignment and settle delay`() {
        val expected = RemoteFlags(splashLfoParallelPreloadEnabled = true, splashNotificationSettleMs = 800)
        RemoteConfigSyncer(context) { null }.applySnapshot(expected)
        val firebase = mock(FirebaseRemoteConfig::class.java)
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forException(IllegalStateException("offline")))
        val reader = RemoteConfigSyncer(context) { firebase }
        var fetched: Boolean? = null
        val job = CoroutineScope(Dispatchers.Main).launch { fetched = reader.fetchAndSync(1_000) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, fetched)
        assertEquals(expected, reader.flags.value)
        assertEquals(expected, RemoteConfigSyncer(context) { null }.flags.value)
        job.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `standalone timeout keeps cache and does not duplicate unfinished Firebase work`() = runTest {
        val expected = RemoteFlags(splashLfoParallelPreloadEnabled = true, skipButtonDelaySec = 9)
        val firebase = mock(FirebaseRemoteConfig::class.java)
        val result = TaskCompletionSource<Boolean>()
        `when`(firebase.fetchAndActivate()).thenReturn(result.task)
        val reader = RemoteConfigSyncer(context) { firebase }
        reader.applySnapshot(expected)

        val impatient = async { reader.fetchAndSync(100) }
        val patient = async { reader.fetchAndSync(1_000) }
        runCurrent()
        assertFalse(impatient.await())
        assertFalse(patient.isCompleted)
        result.setException(IllegalStateException("offline"))
        assertFalse(patient.await())
        assertEquals(expected, reader.flags.value)
        assertEquals(expected, RemoteConfigSyncer(context) { null }.flags.value)
        verify(firebase, times(1)).fetchAndActivate()
    }
}
