package io.onboardkit.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteConfigSyncerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearCache() {
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `splash and resume overrides survive recreating the syncer`() {
        val snapshot = RemoteFlags(
            adsSplashBanner = false,
            adsSplashInter = false,
            adsAppResume = false,
            splashAdBudgetMs = 12_000,
            splashBannerWaitMs = 750,
        )
        val syncer = newSyncer()

        syncer.applySnapshot(snapshot)

        assertEquals(snapshot, syncer.flags.value)
        assertEquals(snapshot, newSyncer().flags.value)
    }

    @Test
    fun `every remote key survives cache independently and in a complete snapshot`() {
        val values = ObRemoteKeys.ALL.associate { key ->
            key.key to when (key) {
                is RemoteKey.BoolKey -> (!key.default).toString()
                is RemoteKey.LongKey -> "987654321"
                is RemoteKey.StringKey -> "value for ${key.key}: Tiếng Việt"
                is RemoteKey.DoubleKey -> "123.5"
            }
        }
        val syncer = newSyncer()

        for ((key, value) in values) {
            val snapshot = snapshotFrom(mapOf(key to value))
            assertNotEquals("The decoder must read $key", RemoteFlags(), snapshot)

            syncer.applySnapshot(snapshot)

            assertEquals("Cached value for $key", snapshot, newSyncer().flags.value)
        }

        val completeSnapshot = snapshotFrom(values)
        syncer.applySnapshot(completeSnapshot)
        assertEquals(completeSnapshot, newSyncer().flags.value)
    }

    @Test
    fun `zero empty strings whitespace and unicode survive replacing a snapshot`() {
        val syncer = newSyncer()
        syncer.applySnapshot(RemoteFlags(languageSupportedCodes = "en,vi", uiContentJson = "old"))
        val snapshot = RemoteFlags(
            splashMinDisplayMs = 0,
            splashAdBudgetMs = 0,
            splashBannerWaitMs = 0,
            skipButtonDelaySec = 0,
            fullScreenAutoDismissSec = 0,
            languageSupportedCodes = "",
            uiContentJson = "",
            uiDesignTokensJson = " \n\t ",
            questionConfigJson = """{"title":"Chào bạn 👋","items":[]}""",
        )

        syncer.applySnapshot(snapshot)

        assertEquals(snapshot, newSyncer().flags.value)
    }

    @Test
    fun `cached flags remain available when remote provider is unavailable`() = runTest {
        val snapshot = RemoteFlags(adsSplashInter = false, splashAdBudgetMs = 17_000)
        newSyncer().applySnapshot(snapshot)
        val restarted = newSyncer()

        assertEquals(snapshot, restarted.flags.value)
        assertFalse(restarted.fetchAndSync(timeoutMs = 1_000))
        assertEquals(snapshot, restarted.flags.value)
        assertEquals(snapshot, newSyncer().flags.value)
    }

    @Test
    fun `deleted overrides return to defaults with the same or a new config version`() {
        val syncer = newSyncer()
        for (version in listOf(7L, 8L)) {
            syncer.applySnapshot(RemoteFlags(
                adsSplashBanner = false,
                splashAdBudgetMs = 20_000,
                uiContentJson = "old content",
                configVersion = 7,
            ))
            val replacement = snapshotFrom(mapOf(
                ObRemoteKeys.ADS_APP_RESUME.key to "false",
                ObRemoteKeys.CONFIG_VERSION.key to version.toString(),
            ))
            val expected = RemoteFlags(adsAppResume = false, configVersion = version)

            syncer.applySnapshot(replacement)

            assertEquals(expected, syncer.flags.value)
            assertEquals(expected, newSyncer().flags.value)
        }
    }

    @Test
    fun `legacy string cache remains readable with defaults for absent keys`() {
        // Input fixture in the format written by older SDKs, before the five missing keys
        // were persisted. These lost overrides can only be restored by a subsequent sync.
        context.getSharedPreferences("ob_remote_cache", Context.MODE_PRIVATE).edit()
            .putString("ob_enable_all_ads", "false")
            .putString("ob_ui_content", "legacy content")
            .putString("ob_config_version", "3")
            .commit()

        assertEquals(
            RemoteFlags(enableAllAds = false, uiContentJson = "legacy content", configVersion = 3),
            newSyncer().flags.value,
        )
    }

    private fun snapshotFrom(values: Map<String, String>) =
        RemoteFlags.from(object : RemoteValueReader {
            override fun string(key: String): String? = values[key]
        })

    private fun newSyncer() = RemoteConfigSyncer(context, remoteConfigProvider = { null })
}
