package io.suite.firebase

import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The Firebase adapter is the source of truth for all three SDK JSON documents. */
class FirebaseAdConfigSourceKeysTest {
    private val firebase = mock(FirebaseRemoteConfig::class.java)

    @After
    fun reset() {
        RemoteConfigClient.reset()
        RemoteConfigClient.provider = { null }
    }

    @Test
    fun `settings fetch reads behavior and onboarding documents as raw remote strings`() = runTest {
        val behavior = value("{\"native\":{\"reload\":{\"allowed\":false}}}")
        val onboarding = value("")
        val ad = value("{\"native_lang\":{\"id\":\"remote\",\"isEnable\":true}}")
        `when`(firebase.fetchAndActivate()).thenReturn(Tasks.forResult(true))
        `when`(firebase.getValue("ad_behavior_config")).thenReturn(behavior)
        `when`(firebase.getValue("onboarding_config")).thenReturn(onboarding)
        `when`(firebase.getValue("ad_remote_config")).thenReturn(ad)
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        val source = FirebaseAdConfigSource()
        assertEquals(
            mapOf(
                "ad_remote_config" to "{\"native_lang\":{\"id\":\"remote\",\"isEnable\":true}}",
                "ad_behavior_config" to "{\"native\":{\"reload\":{\"allowed\":false}}}",
                "onboarding_config" to "",
            ),
            source.fetchSettings(1_000),
        )
        // Blank is a successful remote clear and must remain distinguishable from a missing key.
        assertEquals("{\"native_lang\":{\"id\":\"remote\",\"isEnable\":true}}", source.cached())
        assertTrue(RemoteConfigClient.fetchOnce(1_000))
    }

    @Test
    fun `failed fetch still exposes the last activated ad document`() = runTest {
        val ad = value("{\"native_lang\":{\"id\":\"cached\",\"isEnable\":true}}")
        `when`(firebase.fetchAndActivate()).thenReturn(
            com.google.android.gms.tasks.Tasks.forException(IllegalStateException("offline")),
        )
        `when`(firebase.getValue("ad_remote_config")).thenReturn(ad)
        RemoteConfigClient.provider = { firebase }
        RemoteConfigClient.reset(backgroundScope)

        val source = FirebaseAdConfigSource()
        assertEquals("{\"native_lang\":{\"id\":\"cached\",\"isEnable\":true}}", source.fetch(1_000))
    }

    private fun value(raw: String): FirebaseRemoteConfigValue = mock<FirebaseRemoteConfigValue>().also {
        `when`(it.source).thenReturn(FirebaseRemoteConfig.VALUE_SOURCE_REMOTE)
        `when`(it.asString()).thenReturn(raw)
    }
}
