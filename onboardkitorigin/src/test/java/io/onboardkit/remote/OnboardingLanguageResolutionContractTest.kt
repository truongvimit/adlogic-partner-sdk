package io.onboardkit.remote

import io.onboardkit.config.LanguageConfig
import io.onboardkit.config.ObLanguages
import io.onboardkit.config.onboardKitConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract tests for language resolver presence and configured-default semantics. */
class OnboardingLanguageResolutionContractTest {
    @After
    fun clearRemote() {
        OnboardingSettings.document.acceptSuccessfulFetch(null)
        OnboardingSettings.acceptLegacy(RemoteFlags(supplied = emptySet()))
    }

    @Test
    fun `remote empty default code clears configured and asset defaults`() {
        val config = onboardKitConfig {
            language = LanguageConfig(
                languages = listOf(ObLanguages.find("en-US")!!, ObLanguages.find("es")!!),
                defaultCode = "en-US",
            )
        }.getOrThrow()
        // The module's bundled value and the app's code both provide a fallback. An explicit empty
        // string is still a delivered field and must resolve to no configured default.
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"languages":{"default_code":""}}}""",
        )
        assertNull(OnboardingSettings.resolve(config).language.defaultCode)
    }

    @Test
    fun `missing default code falls back to host value and explicit supported list constrains it`() {
        val config = onboardKitConfig {
            language = LanguageConfig(
                languages = listOf(ObLanguages.find("en-US")!!, ObLanguages.find("es")!!),
                defaultCode = "en-US",
            )
        }.getOrThrow()
        OnboardingSettings.document.acceptSuccessfulFetch("{}")
        assertEquals("en-US", OnboardingSettings.resolve(config).language.defaultCode)

        // A list delivered without default_code is still a valid sparse document. The configured
        // default is hidden when it is no longer in the offered list.
        OnboardingSettings.document.acceptSuccessfulFetch(
            """{"lfo":{"languages":{"supported_codes":["es"]}}}""",
        )
        assertEquals(listOf("es"), OnboardingSettings.resolve(config).language.languages.map { it.code })
        assertNull(OnboardingSettings.resolve(config).language.defaultCode)
    }
}
