package io.onboardkit.remote

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.flow.NativeFlowType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The splash reads the type before any fetch, so a delivered alias must come back from disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeFlowAliasColdStartTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = ObRemoteKeys.NATIVE_REUSE_ENABLED.key

    @Before fun clearCache() {
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).edit().clear().commit()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
    }

    @After fun clearLegacy() {
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).edit().clear().commit()
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
    }

    @Test fun `a delivered alias is cached and maps again on the next cold start`() {
        RemoteConfigSyncer(context) { null }.applySnapshot(RemoteFlags(nativeReuseEnabled = true, supplied = setOf(key)))
        assertEquals("true", context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).getString(key, null))
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())

        val restarted = RemoteConfigSyncer(context) { null }
        assertTrue(restarted.flags.value.nativeReuseEnabled)
        assertEquals(setOf(key), restarted.flags.value.supplied)
        assertEquals(NativeFlowType.SHARED_POOL, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    @Test fun `a delivered false survives as an explicit classic assignment`() {
        RemoteConfigSyncer(context) { null }.applySnapshot(RemoteFlags(nativeReuseEnabled = false, supplied = setOf(key)))
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))

        val restarted = RemoteConfigSyncer(context) { null }
        assertFalse(restarted.flags.value.nativeReuseEnabled)
        assertEquals("PER_SCREEN", OnboardingSettings.values.remoteValue("native_flow.type"))
    }

    @Test fun `a cached snapshot that never carried the alias maps nothing`() {
        RemoteConfigSyncer(context) { null }.applySnapshot(RemoteFlags(showLanguageTapHint = false,
            supplied = setOf(ObRemoteKeys.SHOW_LANGUAGE_TAP_HINT.key)))
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).edit().remove(key).commit()
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))

        val restarted = RemoteConfigSyncer(context) { null }
        assertFalse(restarted.flags.value.nativeReuseEnabled)
        assertNull(OnboardingSettings.values.remoteValue("native_flow.type"))
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())
    }

    @Test fun `the cached grouped field still outranks the cached alias`() {
        RemoteConfigSyncer(context) { null }.applySnapshot(RemoteFlags(nativeReuseEnabled = true, supplied = setOf(key)))
        OnboardingSettings.document.acceptSuccessfulFetch("""{"native_flow":{"type":"PER_SCREEN"}}""")
        RemoteConfigSyncer(context) { null }
        assertEquals(NativeFlowType.PER_SCREEN, OnboardingSettings.nativeFlowTypeOrDefault())
    }
}
