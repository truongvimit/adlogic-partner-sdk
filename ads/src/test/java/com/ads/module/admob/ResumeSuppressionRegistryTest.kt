package com.ads.module.admob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResumeSuppressionRegistryTest {
    private var now = 100L
    private val registry = ResumeSuppressionRegistry { now }

    @Test
    fun `closing an older lease cannot cancel a newer lease with the same owner`() {
        val first = registry.acquire("widget", "first_pin", 1_000, false)
        val second = registry.acquire("widget", "second_pin", 1_000, false)
        first.close()
        first.close()
        assertEquals("second_pin", registry.reason())
        second.close()
        assertNull(registry.reason())
    }

    @Test
    fun `holds survive multiple returns but expire without a callback`() {
        registry.acquire("feedback", "feedback_open", 100, false)
        registry.acquire("permission", "permission_open", 200, false)
        assertEquals(0L, registry.captureReturn())
        registry.clearCaptured()
        now += 100
        assertEquals("permission_open", registry.reason())
        now += 100
        assertNull(registry.reason())
    }

    @Test
    fun `canceling one captured return preserves the other owner and hold`() {
        val first = registry.acquire("pin", "pin_return", 1_000, true)
        registry.acquire("store", "store_return", 1_000, true)
        registry.acquire("modal", "modal_open", 1_000, false)
        val capture = registry.captureReturn()
        first.close()
        assertEquals("store_return", registry.reason())
        registry.clearCaptured(capture)
        assertEquals("modal_open", registry.reason())
    }

    @Test
    fun `clearing an old dispatch cannot erase a newer captured return`() {
        registry.acquire("pin", "first", 1_000, true)
        val first = registry.captureReturn()
        registry.acquire("pin", "second", 1_000, true)
        val second = registry.captureReturn()
        registry.clearCaptured(first)
        assertEquals("second", registry.reason())
        registry.clearCaptured(second)
        assertNull(registry.reason())
    }

    @Test
    fun `expired one shot cannot suppress an unrelated future exit`() {
        registry.acquire("pin", "never_launched", 10, true)
        now += 11
        assertEquals(0L, registry.captureReturn())
        assertNull(registry.reason())
    }

    @Test
    fun `invalid ownership or unbounded timeout changes no state`() {
        assertThrows(IllegalArgumentException::class.java) { registry.acquire("", "reason", 1, false) }
        assertThrows(IllegalArgumentException::class.java) { registry.acquire("owner", " ", 1, false) }
        assertThrows(IllegalArgumentException::class.java) { registry.acquire("owner", "reason", 0, false) }
        assertThrows(IllegalArgumentException::class.java) { registry.acquire("owner", "reason", 600_001, false) }
        assertNull(registry.reason())
    }
}
