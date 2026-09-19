package com.ads.module.config.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Precedence is stated once, here. Every scoped setting reads through this, so no field can
 * grow a different chain than its siblings by hand.
 */
class ScopeChainTest {
    private val values get() = AdBehavior.document.snapshot

    @After fun clearRemote() { AdBehavior.document.acceptSuccessfulFetch(null) }

    @Test fun `when the first scope carries an override, it wins over a later one`() {
        AdBehavior.document.acceptSuccessfulFetch(
            """{"native":{"reload":{"resume_debounce_ms":111}},"banner":{"reload":{"resume_debounce_ms":222}}}""")
        assertEquals(111L, values.scoped("native.reload.resume_debounce_ms", "banner.reload.resume_debounce_ms").long(0L))
        assertEquals(222L, values.scoped("banner.reload.resume_debounce_ms", "native.reload.resume_debounce_ms").long(0L))
    }

    @Test fun `when only a later scope carries an override, that one answers`() {
        AdBehavior.document.acceptSuccessfulFetch("""{"banner":{"reload":{"resume_debounce_ms":222}}}""")
        assertEquals(222L, values.scoped("native.reload.resume_debounce_ms", "banner.reload.resume_debounce_ms").long(0L))
    }

    @Test fun `when no scope carries an override, the host value stands over the bundled default`() {
        // Both paths have bundled defaults of 500; a default is not an override and must not displace 7.
        assertEquals(7L, values.scoped("native.reload.resume_debounce_ms", "banner.reload.resume_debounce_ms").long(7L))
        assertEquals("HOST", values.scoped("native.click.action").string("HOST"))
        assertEquals(true, values.scoped("native.reload.allowed").boolean(true))
    }

    @Test fun `an empty chain is just the host value`() {
        assertEquals(7L, values.scoped().long(7L))
    }
}
