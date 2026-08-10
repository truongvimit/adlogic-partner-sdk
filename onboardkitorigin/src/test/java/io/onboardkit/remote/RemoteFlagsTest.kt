package io.onboardkit.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFlagsTest {

    private fun reader(values: Map<String, String>): RemoteValueReader =
        object : RemoteValueReader {
            override fun string(key: String): String? = values[key]
        }

    @Test
    fun `missing keys resolve to declared defaults`() {
        val flags = RemoteFlags.from(reader(emptyMap()))
        assertEquals(RemoteFlags(), flags)
        assertTrue(flags.enableAllAds)
        assertTrue(flags.enableLanguageNative2)
        assertEquals(3L, flags.skipButtonDelaySec)
    }

    @Test
    fun `deleted key falls back instead of sticking`() {
        val first = RemoteFlags.from(
            reader(mapOf(ObRemoteKeys.ENABLE_STEP_OB2.key to "false")),
        )
        assertFalse(first.enableStepOb2)

        // Next sync: the key no longer exists remotely → default returns
        val second = RemoteFlags.from(reader(emptyMap()))
        assertTrue(second.enableStepOb2)
    }

    @Test
    fun `boolean parsing is tolerant`() {
        val flags = RemoteFlags.from(
            reader(
                mapOf(
                    ObRemoteKeys.ENABLE_STEP_OB1.key to " TRUE ",
                    ObRemoteKeys.ENABLE_STEP_OB2.key to "1",
                    ObRemoteKeys.ENABLE_STEP_OB3.key to "yes",
                    ObRemoteKeys.ENABLE_STEP_OB4.key to "garbage",
                ),
            ),
        )
        assertTrue(flags.enableStepOb1)
        assertTrue(flags.enableStepOb2)
        assertTrue(flags.enableStepOb3)
        assertFalse(flags.enableStepOb4)
    }

    @Test
    fun `numbers parse with fallback`() {
        val flags = RemoteFlags.from(
            reader(
                mapOf(
                    ObRemoteKeys.SKIP_BUTTON_DELAY_SEC.key to "5",
                    ObRemoteKeys.SPLASH_MIN_DISPLAY_MS.key to "not_a_number",
                ),
            ),
        )
        assertEquals(5L, flags.skipButtonDelaySec)
        assertEquals(ObRemoteKeys.SPLASH_MIN_DISPLAY_MS.default, flags.splashMinDisplayMs)
    }

    @Test
    fun `step gating helper honors flags`() {
        val flags = RemoteFlags(enableStepOb3 = false)
        assertFalse(flags.isStepEnabled(io.onboardkit.core.StepId.OB3))
        assertTrue(flags.isStepEnabled(io.onboardkit.core.StepId.OB1))
        assertTrue(flags.anyTutorialStepEnabled)
    }

    @Test
    fun `all steps off disables tutorial flow`() {
        val flags = RemoteFlags(
            enableStepOb1 = false,
            enableStepOb2 = false,
            enableStepOb3 = false,
            enableStepOb4 = false,
        )
        assertFalse(flags.anyTutorialStepEnabled)
    }
}
