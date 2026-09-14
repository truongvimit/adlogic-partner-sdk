package com.ads.module.config.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.AdConfig
import com.ads.module.config.AdConfigSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SettingsFetchTest {
    @After fun clear() { AdBehavior.document.acceptSuccessfulFetch(null) }

    @Test fun `failed fetch leaves last success and does not consume a second timeout`() = runBlocking {
        AdBehavior.initialize(ApplicationProvider.getApplicationContext<Context>())
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"interval_ms":12000}}}""")
        var adFetches = 0
        AdConfig.install(object : AdConfigSource, SettingsConfigSource {
            override val id = "test"
            override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?>? = null
            override suspend fun fetch(timeoutMs: Long): String? { adFetches++; return null }
        })
        assertFalse(AdConfig.refresh(10))
        assertEquals(12000L, AdBehavior.number("native.reload.interval_ms"))
        assertEquals(0, adFetches)
    }

    @Test fun `caller cancellation propagates without replacing the last valid settings`() = runBlocking {
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"on_ad_click":false}}}""")
        AdConfig.install(object : AdConfigSource, SettingsConfigSource {
            override val id = "cancelled"
            override suspend fun fetchSettings(timeoutMs: Long): Map<String, String?>? {
                throw kotlinx.coroutines.CancellationException("screen destroyed")
            }
            override suspend fun fetch(timeoutMs: Long): String? = error("must not fetch after cancellation")
        })
        try {
            AdConfig.refresh(10)
            fail("Cancellation must reach the caller")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertFalse(AdBehavior.bool("native.reload.on_ad_click"))
        }
    }

    @Test fun `registry prepares remote payload before publication on a worker thread`() = runBlocking {
        val document = SettingsDocument("worker_settings", """{"timer_ms":10}""")
        var preparedThread: Thread? = null
        var valueDuringPreparation = 0L
        document.prepareBeforePublish {
            preparedThread = Thread.currentThread()
            valueDuringPreparation = document.snapshot.long("timer_ms")
            assertEquals(20L, it.long("timer_ms"))
        }
        SettingsRegistry.register(document)
        SettingsRegistry.acceptSuccessfulFetch(mapOf("worker_settings" to """{"timer_ms":20}"""))
        assertNotSame(android.os.Looper.getMainLooper().thread, preparedThread)
        assertNotNull(preparedThread)
        assertEquals(10L, valueDuringPreparation)
        assertEquals(20L, document.snapshot.long("timer_ms"))
    }

    @Test fun `successful fetch with removed parameter restores defaults`() = runBlocking {
        AdBehavior.initialize(ApplicationProvider.getApplicationContext<Context>())
        AdBehavior.document.acceptSuccessfulFetch("""{"native":{"reload":{"on_ad_click":false}}}""")
        AdConfig.install(object : AdConfigSource, SettingsConfigSource {
            override val id = "test"
            override suspend fun fetchSettings(timeoutMs: Long) = mapOf("ad_behavior_config" to null)
            override suspend fun fetch(timeoutMs: Long): String? = null
        })
        AdConfig.refresh(10)
        assertTrue(AdBehavior.bool("native.reload.on_ad_click"))
    }
}
