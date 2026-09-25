package com.ads.module.config.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote (document, then legacy keys) > app asset > host value > bundled default, at every scope. */
class RemoteSupremacyTest {
    private fun document(asset: String? = null) =
        SettingsDocument("remote_supremacy_test", BundledAdBehavior.JSON).also { it.install(asset, null) }

    @Test fun `remote at a broad scope beats the app asset at a narrow scope`() {
        val d = document("""{"banner":{"reload":{"resume_debounce_ms":111}}}""")
        val chain = { d.snapshot.scoped("banner.reload.resume_debounce_ms", "native.reload.resume_debounce_ms") }
        assertEquals(111L, chain().long(7L))
        d.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":222}}}""")
        assertEquals(222L, chain().long(7L))
        d.acceptSuccessfulFetch("""{"banner":{"reload":{"resume_debounce_ms":333}}}""")
        assertEquals(333L, chain().long(7L))
    }

    @Test fun `legacy values rank below the document's remote and above the app asset`() {
        val d = document("""{"native":{"reload":{"resume_debounce_ms":111}}}""")
        d.acceptLegacyRemote(mapOf("native.reload.resume_debounce_ms" to 222L))
        assertEquals(222L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
        assertTrue(d.snapshot.hasRemoteOverride("native.reload"))
        d.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":333}}}""")
        assertEquals(333L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
        d.acceptSuccessfulFetch(null)
        assertEquals(222L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
        d.acceptLegacyRemote(emptyMap())
        assertEquals(111L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
    }

    @Test fun `an invalid legacy value is dropped rather than reaching a reader`() {
        val d = document()
        d.acceptLegacyRemote(mapOf("native.reload.resume_debounce_ms" to "fast", "unknown.path" to true))
        assertNull(d.snapshot.remoteValue("native.reload.resume_debounce_ms"))
        assertFalse(d.snapshot.hasRemoteOverride("unknown"))
        assertEquals(7L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
    }

    @Test fun `a fetch that lands after legacy values keeps them`() {
        val d = document()
        d.acceptLegacyRemote(mapOf("native.reload.resume_debounce_ms" to 222L))
        d.acceptSuccessfulFetch("""{"banner":{"reload":{"resume_debounce_ms":333}}}""")
        assertEquals(222L, d.snapshot.long("native.reload.resume_debounce_ms", 7L))
        assertEquals(333L, d.snapshot.long("banner.reload.resume_debounce_ms", 7L))
    }

    @Test fun `behavior scopes read remote at any scope before the app asset at any scope`() {
        val screen = document("""{"banner":{"reload":{"resume_debounce_ms":111}}}""")
        val format = document()
        fun wait() = BehaviorValues(format.snapshot, "native", null, screen.snapshot, "banner", null)
            .long("reload.resume_debounce_ms", 7L)
        assertEquals(111L, wait())
        format.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":222}}}""")
        assertEquals(222L, wait())
    }

    @Test fun `a screen alias outranks placement and format scopes but not the slot`() {
        val screen = document()
        val format = document()
        fun wait() = BehaviorValues(format.snapshot, "native", "native_lang", screen.snapshot, "native",
            null, mapOf("reload.resume_debounce_ms" to "banner.reload.resume_debounce_ms")).long("reload.resume_debounce_ms", 7L)
        format.acceptSuccessfulFetch("""{"native":{"reload":{"resume_debounce_ms":111}}}""")
        assertEquals(111L, wait())
        screen.acceptSuccessfulFetch("""{"banner":{"reload":{"resume_debounce_ms":222}}}""")
        assertEquals(222L, wait())
        screen.acceptSuccessfulFetch("""{"banner":{"reload":{"resume_debounce_ms":222}},"native":{"reload":{"resume_debounce_ms":333}}}""")
        assertEquals(333L, wait())
    }
}
