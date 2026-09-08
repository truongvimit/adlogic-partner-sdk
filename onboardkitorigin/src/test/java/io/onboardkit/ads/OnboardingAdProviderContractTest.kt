package io.onboardkit.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class OnboardingAdProviderContractTest {
    @Test
    fun `every provider must implement load and show instead of inheriting a ready only fallback`() {
        val contract = OnboardingAdProvider::class.java
        val method = contract.declaredMethods.single { it.name == "loadAndShowInterstitial" }
        assertTrue(
            "loadAndShow must be implemented by the provider",
            Modifier.isAbstract(method.modifiers)
        )
        val kotlinDefaults =
            contract.declaredClasses.firstOrNull { it.simpleName == "DefaultImpls" }
        assertFalse(
            "A default body must not silently ignore unit and timeoutMs",
            kotlinDefaults?.declaredMethods?.any { it.name == "loadAndShowInterstitial" } == true)
    }
}
