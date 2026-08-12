package io.trackkit

import io.trackkit.internal.AdRevenueAccumulator
import io.trackkit.internal.EventValidator
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * The taxonomy guard.
 *
 * Every event in [TrackkitEvents] is discovered by reflection, instantiated, and pushed through the
 * same [EventValidator] the runtime uses — plus the stricter naming rules the catalog documents but
 * the validator cannot express (lowercase snake_case, known domain prefix).
 *
 * A typo like `iap_successfull`, a 41-character name or a `firebase_`-prefixed param fails here,
 * not silently inside GA4 six weeks after release.
 */
class TaxonomyTest {

    // Documented grammar: lowercase snake_case, no leading digit, no double or trailing underscore.
    private val snakeCase = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    private val domains = listOf("ad_", "fo_", "iap_", "consent_", "app_")

    /** Sanity check: if reflection silently finds nothing, every other assertion is vacuous. */
    @Test
    fun `catalog is fully discovered`() {
        val events = catalogEvents()
        assertTrue(
            "expected the whole catalog, found only ${events.size}: ${events.map { it.name }}",
            events.size >= EXPECTED_EVENT_COUNT,
        )
    }

    @Test
    fun `every catalog event name passes the validator`() {
        catalogEvents().forEach { event ->
            assertName(event.name, "${event.javaClass.name} -> '${event.name}'")
        }
    }

    /** A class whose name is missing from all() is invisible to sink configuration validation. */
    @Test
    fun `every catalog event name is a member of all`() {
        catalogEvents().forEach { event ->
            assertTrue(
                "${event.javaClass.name} -> '${event.name}' missing from TrackkitEvents.all()",
                event.name in TrackkitEvents.all(),
            )
        }
    }

    @Test
    fun `every catalog param key passes the validator`() {
        catalogEvents().forEach { event ->
            event.params.keys.forEach { key ->
                assertParamKey(key, "${event.name} param '$key'")
            }
        }
    }

    /** The `AD_*` name constants and `PARAM_*` keys sinks and the Adjust token map reference. */
    @Test
    fun `every declared constant passes the validator`() {
        stringConstantsOf(TrackkitEvents::class.java).forEach { (field, value) ->
            if (field.startsWith("PARAM_")) {
                assertParamKey(value, "TrackkitEvents.$field")
            } else {
                assertName(value, "TrackkitEvents.$field")
            }
        }
        stringConstantsOf(Tracker::class.java)
            .filter { (field, _) -> field.startsWith("PARAM_") }
            .forEach { (field, value) -> assertParamKey(value, "Tracker.$field") }
    }

    /** The accumulator builds two of its names by string template, so assert the literals too. */
    @Test
    fun `revenue event names pass the validator`() {
        listOf(
            AdRevenueAccumulator.EVENT_TOTAL,
            AdRevenueAccumulator.EVENT_FLUSH,
            "ad_revenue_d3",
            "ad_revenue_d7",
        ).forEach { name -> assertName(name, "revenue event '$name'") }
    }

    /** `ad_format` values land in dashboards; a stray uppercase key would fork every report. */
    @Test
    fun `every ad format key is snake_case`() {
        AdFormat.entries.forEach { format ->
            assertTrue(
                "AdFormat.${format.name} key '${format.key}' is not snake_case",
                snakeCase.matches(format.key),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Assertions
    // -----------------------------------------------------------------------

    private fun assertName(name: String, where: String) {
        val problem = EventValidator.validateName(name)
        assertNull("$where: $problem", problem)
        assertTrue(
            "$where is ${name.length} chars, over the ${EventValidator.MAX_NAME_LENGTH} limit",
            name.length <= EventValidator.MAX_NAME_LENGTH,
        )
        assertTrue("$where is not lowercase snake_case", snakeCase.matches(name))
        assertTrue(
            "$where does not start with a known domain ${domains.joinToString()}",
            domains.any { name.startsWith(it) },
        )
    }

    private fun assertParamKey(key: String, where: String) {
        val problem = EventValidator.validateParamKey(key)
        assertNull("$where: $problem", problem)
        assertTrue(
            "$where is ${key.length} chars, over the ${EventValidator.MAX_NAME_LENGTH} limit",
            key.length <= EventValidator.MAX_NAME_LENGTH,
        )
        assertTrue("$where is not lowercase snake_case", snakeCase.matches(key))
    }

    // -----------------------------------------------------------------------
    // Reflection
    // -----------------------------------------------------------------------

    /** Instantiates every [TrackEvent] nested anywhere under [TrackkitEvents]. */
    private fun catalogEvents(): List<TrackEvent> =
        nestedClasses(TrackkitEvents::class.java)
            .filter { TrackEvent::class.java.isAssignableFrom(it) }
            .filter { !Modifier.isAbstract(it.modifiers) && !it.isInterface }
            .sortedBy { it.name }
            .map { instantiate(it) }

    private fun nestedClasses(root: Class<*>): List<Class<*>> =
        root.declaredClasses.flatMap { listOf(it) + nestedClasses(it) }

    private fun instantiate(type: Class<*>): TrackEvent {
        val constructor = type.declaredConstructors
            // Skip the synthetic default-argument overload; the full one carries every param.
            .filter { ctor -> ctor.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
            .maxByOrNull { it.parameterTypes.size }
            ?: error("${type.name} has no usable constructor")

        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { argFor(it, constructor) }.toTypedArray()
        return constructor.newInstance(*args) as TrackEvent
    }

    /** Synthetic but valid values — the test cares about names and keys, not payloads. */
    private fun argFor(type: Class<*>, ctor: Constructor<*>): Any = when {
        type == String::class.java -> "sample_value"
        type == Int::class.javaPrimitiveType || type == Integer::class.java -> 1
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 1L
        type == Double::class.javaPrimitiveType || type == java.lang.Double::class.java -> 1.0
        type == Float::class.javaPrimitiveType || type == java.lang.Float::class.java -> 1.0f
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> true
        type.isEnum -> type.enumConstants.first()
        else -> throw IllegalArgumentException(
            "TaxonomyTest cannot synthesise a ${type.name} for ${ctor.declaringClass.name}. " +
                    "Add it to argFor() when the catalog gains a new param type."
        )
    }

    private fun stringConstantsOf(type: Class<*>): List<Pair<String, String>> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { field ->
                field.isAccessible = true
                field.name to (field.get(null) as String)
            }

    private companion object {
        /** 9 ad + 13 first-open + 6 iap + 3 consent. Raise it when the catalog grows. */
        const val EXPECTED_EVENT_COUNT = 31
    }
}
