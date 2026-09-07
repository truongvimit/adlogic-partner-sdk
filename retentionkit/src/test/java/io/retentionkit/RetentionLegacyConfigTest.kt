package io.retentionkit

import io.retentionkit.integration.RetentionLegacyConfig
import org.junit.Assert.*
import org.junit.Test

class RetentionLegacyConfigTest {
    @Test fun commonMoAndSuiteParametersMapToTheirActualUnitsAndOwners() {
        val result = RetentionLegacyConfig.map(mapOf("noti_lockscreen_slots" to "11:30,17:00,20:00",
            "noti_lockscreen_slots_new" to "12:00", "notiLockscreenReplace" to "false",
            "notiWidgetPromptEnabled" to "false", "notiUninstallShortcutEnabled" to "true",
            "rateEnabled" to "true", "rateMinSuccesses" to "5", "rateCooldownDays" to "10", "rateMaxPrompts" to "3"))
        assertTrue(result.unsupportedKeys.isEmpty())
        assertEquals("12:00", result.overrides["notifications.lockscreen.new_user_slots"])
        assertEquals("false", result.overrides["notifications.lockscreen.replace"])
        assertEquals("false", result.overrides["widgets.invitation.enabled"])
        assertFalse(result.overrides.containsKey("widgets.enabled"))
        assertEquals("true", result.overrides["feedback.shortcut_enabled"])
        assertEquals("5", result.overrides["review.success_threshold"])
        assertEquals("10", result.overrides["review.cooldown_days"])
        assertEquals("3", result.overrides["review.max_attempts"])
    }

    @Test fun unsupportedCapabilitiesAreReportedAndNeverEnablePremiumMarketing() {
        val result = RetentionLegacyConfig.map(mapOf("notiForPremiumUsers" to "true",
            "noti_lockscreen_wake_seconds" to "20", "noti_lockscreen_content" to "[]", "unknown" to "true"))
        assertEquals(mapOf("notifications.lockscreen.wake_duration_ms" to "20000"), result.overrides)
        assertEquals(setOf("notiForPremiumUsers", "noti_lockscreen_content", "unknown"), result.unsupportedKeys)
        assertTrue(RetentionLegacyConfig.map(mapOf("notiForPremiumUsers" to "false")).unsupportedKeys.isEmpty())
    }

    @Test fun absentFieldsDoNotInventDefaultsAndOversizedNumbersRemainForStrictValidation() {
        assertTrue(RetentionLegacyConfig.map(emptyMap()).overrides.isEmpty())
        assertEquals("9223372036854775807", RetentionLegacyConfig.map(mapOf("rateCooldownDays" to "9223372036854775807"))
            .overrides["review.cooldown_days"])
        assertTrue(RetentionLegacyConfig.keys.containsAll(setOf("noti_lockscreen_slots", "noti_lockscreen_wake_seconds", "notiWidgetPromptEnabled", "rateCooldownDays")))
    }
}
