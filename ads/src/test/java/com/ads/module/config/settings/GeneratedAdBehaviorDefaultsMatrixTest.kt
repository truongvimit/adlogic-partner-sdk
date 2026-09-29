package com.ads.module.config.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every generated ad-behavior field must remain parseable as a remote override. */
class GeneratedAdBehaviorDefaultsMatrixTest {
    @Test
    fun `generated default map round trips every field with presence`() {
        BundledAdBehavior.VALUES.entries.forEachIndexed { index, (path, value) ->
            val document = SettingsDocument("generated_ad_$index", BundledAdBehavior.VALUES)
            val payload = SettingsDocument.inflate(mapOf(path to value)).toString()
            assertTrue("remote payload rejected for $path: $payload", document.acceptSuccessfulFetch(payload))
            assertTrue("presence lost for generated field $path", document.snapshot.hasRemoteOverride(path))
            assertEquivalent(value, document.snapshot.remoteValue(path), path)
        }
    }

    @Test
    fun `every generated field falls back to an explicit asset when remote omits it`() {
        BundledAdBehavior.VALUES.entries.forEachIndexed { index, (path, value) ->
            val document = SettingsDocument("generated_ad_asset_$index", BundledAdBehavior.VALUES)
            document.install(SettingsDocument.inflate(mapOf(path to value)).toString(), null)
            document.acceptSuccessfulFetch("{}")
            assertTrue("asset presence lost for generated field $path", document.snapshot.hasOverride(path))
            assertTrue("asset incorrectly became remote for $path", !document.snapshot.hasRemoteOverride(path))
            assertEquivalent(value, document.snapshot.assetValue(path), path)
        }
    }

    @Test
    fun `an invalid generated field is dropped while a valid sibling survives`() {
        val fields = BundledAdBehavior.VALUES.entries.filter { it.key != "schema_version" }
        fields.forEachIndexed { index, (path, value) ->
            val sibling = fields.firstOrNull { it.key != path } ?: return@forEachIndexed
            val payload = SettingsDocument.inflate(
                mapOf(path to invalidType(value), sibling.key to sibling.value),
            ).toString()
            val document = SettingsDocument("generated_ad_invalid_$index", BundledAdBehavior.VALUES)
            assertTrue("mixed payload rejected for $path", document.acceptSuccessfulFetch(payload))
            assertTrue("invalid path retained for $path", !document.snapshot.hasRemoteOverride(path))
            assertTrue("valid sibling missing for ${sibling.key}", document.snapshot.hasRemoteOverride(sibling.key))
        }
    }

    private fun assertEquivalent(expected: Any?, actual: Any?, path: String) {
        val equivalent = when {
            expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
            expected is List<*> && actual is List<*> -> expected.size == actual.size &&
                expected.zip(actual).all { (left, right) -> left == right || (left is Number && right is Number && left.toDouble() == right.toDouble()) }
            expected is Map<*, *> && actual is Map<*, *> -> expected == actual
            else -> expected == actual
        }
        assertTrue("generated value changed for $path: expected=$expected actual=$actual", equivalent)
    }

    private fun invalidType(value: Any): Any = when (value) {
        is Boolean -> "not-a-boolean"
        is Number -> "not-a-number"
        is String -> 7L
        is List<*> -> false
        is Map<*, *> -> listOf("not-an-object")
        else -> false
    }
}
