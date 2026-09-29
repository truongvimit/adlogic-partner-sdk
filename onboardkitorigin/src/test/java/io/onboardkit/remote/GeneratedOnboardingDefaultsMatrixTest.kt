package io.onboardkit.remote

import com.ads.module.config.settings.SettingsDocument
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every generated onboarding field must round-trip through the strict document parser. */
class GeneratedOnboardingDefaultsMatrixTest {
    @Test
    fun `generated default map round trips every field with presence`() {
        BundledOnboarding.VALUES.entries.forEachIndexed { index, (path, value) ->
            val document = SettingsDocument("generated_onboarding_$index", BundledOnboarding.VALUES)
            val payload = payloadFor(path, value).toString()
            assertTrue("remote payload rejected for $path: $payload", document.acceptSuccessfulFetch(payload))
            assertTrue("presence lost for generated field $path", document.snapshot.hasRemoteOverride(path))
            assertEquivalent(value, document.snapshot.remoteValue(path), path)
        }
    }

    @Test
    fun `every generated field falls back to the bundled value when remote omits it`() {
        BundledOnboarding.VALUES.entries.forEachIndexed { index, (path, value) ->
            val document = SettingsDocument("generated_onboarding_asset_$index", BundledOnboarding.VALUES)
            document.acceptSuccessfulFetch("{}")
            assertTrue("remote clear lost bundled field $path", !document.snapshot.hasRemoteOverride(path))
            assertEquivalent(value, document.snapshot.remoteValue(path) ?: BundledOnboarding.VALUES[path], path)
        }
    }

    @Test
    fun `an invalid generated field is dropped while a valid sibling survives`() {
        val fields = BundledOnboarding.VALUES.entries.filter { it.key != "schema_version" }
        fields.forEachIndexed { index, (path, value) ->
            val sibling = fields.firstOrNull { it.key != path } ?: return@forEachIndexed
            val payload = payloadForEntries(path to invalidType(value), sibling.key to sibling.value)
            val document = SettingsDocument("generated_onboarding_invalid_$index", BundledOnboarding.VALUES)
            assertTrue("mixed payload rejected for $path", document.acceptSuccessfulFetch(payload))
            assertTrue("invalid path retained for $path", !document.snapshot.hasRemoteOverride(path))
            assertTrue("valid sibling missing for ${sibling.key}", document.snapshot.hasRemoteOverride(sibling.key))
        }
    }

    private fun payloadFor(path: String, value: Any?): JSONObject {
        val root = JSONObject()
        var node = root
        val segments = path.split('.')
        segments.dropLast(1).forEach { segment ->
            val child = JSONObject()
            node.put(segment, child)
            node = child
        }
        node.put(segments.last(), value)
        return root
    }

    private fun payloadForEntries(vararg entries: Pair<String, Any?>): String {
        val root = JSONObject()
        entries.forEach { (path, value) ->
            var node = root
            val segments = path.split('.')
            segments.dropLast(1).forEach { segment ->
                val child = node.optJSONObject(segment) ?: JSONObject().also { node.put(segment, it) }
                node = child
            }
            node.put(segments.last(), value)
        }
        return root.toString()
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
