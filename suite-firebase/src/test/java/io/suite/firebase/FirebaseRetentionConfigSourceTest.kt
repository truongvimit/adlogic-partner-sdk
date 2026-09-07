package io.suite.firebase

import io.retentionkit.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseRetentionConfigSourceTest {
    private val mapping = mapOf("noti_lockscreen_slots" to "notifications.lockscreen.slots",
        "notiLockscreenReplace" to "notifications.lockscreen.replace")
    private fun source() = FirebaseRetentionConfigSource(legacyKeys = mapping.keys,
        legacyMapper = { values -> values.mapKeys { mapping.getValue(it.key) } })
    private fun parse(result: RetentionConfigFetchResult): RetentionConfigDocument =
        (RetentionConfigParser.parse((result as RetentionConfigFetchResult.Document).json) as RetentionConfigParseResult.Valid).document

    @Test fun presentMoKeysProduceRuntimeDocumentWithoutAnotherDocumentKey() {
        val reads = mutableListOf<String>()
        val values = mapOf("noti_lockscreen_slots" to "09:30,18:00", "notiLockscreenReplace" to "false")
        val result = source().readActivatedValues { reads.add(it); values[it] }
        assertEquals(mapOf("notifications.lockscreen.slots" to "09:30,18:00",
            "notifications.lockscreen.replace" to "false"), parse(result).overrides)
        assertEquals(setOf("retention_config") + mapping.keys, reads.toSet())
        assertEquals(reads.size, reads.distinct().size)
    }

    @Test fun absentKeysAreMissingAndDoNotEraseCachedOrDefaultValues() {
        assertEquals(RetentionConfigFetchResult.Missing, source().readActivatedValues { null })
        assertEquals(mapOf("notifications.lockscreen.replace" to "false"),
            parse(source().readActivatedValues { if (it == "notiLockscreenReplace") "false" else null }).overrides)
    }

    @Test fun normalizedOverridesAndRemovalsWinOverLegacyAliases() {
        val values = mapOf("noti_lockscreen_slots" to "09:30", "notiLockscreenReplace" to "true",
            "retention_config" to """{"version":1,"overrides":{"notifications.lockscreen.slots":"11:30,17:00"},"removeKeys":["notifications.lockscreen.replace"]}""")
        val document = parse(source().readActivatedValues(values::get))
        assertEquals(mapOf("notifications.lockscreen.slots" to "11:30,17:00"), document.overrides)
        assertEquals(setOf("notifications.lockscreen.replace"), document.removeKeys)
    }

    @Test fun malformedAuthoritativeDocumentIsNotHiddenByValidLegacyValues() {
        val raw = """{"version":999,"overrides":{}}"""
        val result = source().readActivatedValues {
            when (it) { "retention_config" -> raw; "notiLockscreenReplace" -> "true"; else -> null }
        }
        assertEquals(RetentionConfigFetchResult.Document(raw), result)
        assertTrue(RetentionConfigParser.parse((result as RetentionConfigFetchResult.Document).json) is RetentionConfigParseResult.Invalid)
    }

    @Test fun jsonOnlyConstructorKeepsExistingSourceBehavior() {
        val raw = """{"version":1,"overrides":{"review.enabled":false}}"""
        assertEquals(RetentionConfigFetchResult.Document(raw), FirebaseRetentionConfigSource().readActivatedValues {
            assertEquals("retention_config", it)
            raw
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacyKeysWithoutMapperFailBeforeFetching() {
        FirebaseRetentionConfigSource(legacyKeys = setOf("notiLockscreenReplace"))
    }
}
