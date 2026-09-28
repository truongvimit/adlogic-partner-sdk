package io.onboardkit.remote

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ads.module.config.settings.SettingsDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class OnboardingDocumentPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clearCache() {
        context.getSharedPreferences("adlogic_settings_onboarding_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
    }

    @Test
    fun `valid onboarding remote survives a new document instance`() {
        val first = SettingsDocument("onboarding_config", BundledOnboarding.VALUES)
        first.initialize(context)
        assertTrue(first.acceptSuccessfulFetch("""{"lfo":{"languages":{"default_code":"es"}}}"""))

        val restarted = SettingsDocument("onboarding_config", BundledOnboarding.VALUES)
        restarted.initialize(context)
        assertEquals("es", restarted.snapshot.string("lfo.languages.default_code"))
        assertTrue(restarted.snapshot.hasRemoteOverride("lfo.languages.default_code"))
    }

    @Test
    fun `malformed fetch leaves the last valid persisted onboarding snapshot`() {
        val first = SettingsDocument("onboarding_config", BundledOnboarding.VALUES)
        first.initialize(context)
        first.acceptSuccessfulFetch("""{"lfo":{"tap_hint":{"enabled":false}}}""")
        assertFalse(first.acceptSuccessfulFetch("{broken"))

        val restarted = SettingsDocument("onboarding_config", BundledOnboarding.VALUES)
        restarted.initialize(context)
        assertFalse(restarted.snapshot.boolean("lfo.tap_hint.enabled"))
    }
}
