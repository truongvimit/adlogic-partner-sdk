package com.itg.template.utils

import android.os.Bundle
import io.trackkit.Tracker

/**
 * Thin compatibility layer over [Tracker]. Kept only so existing call sites keep compiling —
 * new code should call [Tracker] directly.
 *
 * Two behaviour changes worth knowing about:
 *
 *  - [logEventClick] and [fromScreenToScreen] used to build the event name by concatenation
 *    (`"${activityName}_click_$eventName"`, `"${from}_${to}"`). That silently blew past GA4's
 *    40-character name limit on longer activity names and created an unbounded event-name space
 *    that no dashboard can aggregate. They now emit the fixed names [EVENT_UI_CLICK] /
 *    [EVENT_SCREEN_FLOW] and carry the parts as params instead.
 *  - [addScreenTrack] no longer logs an event named after the screen; it goes through
 *    `Tracker.screen`, so each sink emits its own native screen-view.
 */
@Deprecated("Use io.trackkit.Tracker directly.")
object ITGTrackingHelper {
    const val CONSENT_ERROR_1: String = "consent_error_1"
    const val CONSENT_ERROR_2: String = "consent_error_2"
    const val LOAD_CONSENT_1 = "new_load_consent_1"
    const val DISPLAY_CONSENT_1 = "new_display_consent_1"
    const val NOT_REQUIRE_DISPLAY_CONSENT_1 = "new_not_require_consent_1"
    const val NOT_USING_DISPLAY_CONSENT_1 = "new_not_using_display_consent_1"
    const val AGREE_CONSENT_1 = "new_agree_consent_1"
    const val REFUSE_CONSENT_1 = "new_refuse_consent_1"

    const val LOAD_CONSENT_2 = "load_consent_2"
    const val DISPLAY_CONSENT_2 = "display_consent_2"
    const val NOT_USING_DISPLAY_CONSENT_2 = "not_using_display_consent_2"

    const val AGREE_CONSENT_2 = "agree_consent_2"
    const val REFUSE_CONSENT_2 = "refuse_consent_2"

    const val LOAD_CONSENT_3 = "load_consent_3"
    const val DISPLAY_CONSENT_3 = "display_consent_3"
    const val AGREE_CONSENT_3 = "agree_consent_3"
    const val REFUSE_CONSENT_3 = "refuse_consent_3"

    /** Fixed replacements for the two concatenated names. */
    const val EVENT_UI_CLICK = "app_ui_click"
    const val EVENT_SCREEN_FLOW = "app_screen_flow"

    @Deprecated(
        "Use Tracker.setUserProperty(name, value).",
        ReplaceWith("Tracker.setUserProperty(name, value)", "io.trackkit.Tracker"),
    )
    fun userProperty(name: String, value: String): ITGTrackingHelper {
        Tracker.setUserProperty(name, value)
        return this
    }

    @Deprecated(
        "Use Tracker.track(SimpleEvent(eventName, params)) or a TrackkitEvents catalog entry.",
        ReplaceWith("Tracker.track(eventName)", "io.trackkit.Tracker"),
    )
    fun logEvent(eventName: String, params: Bundle?): ITGTrackingHelper {
        Tracker.track(eventName, params.toParams())
        return this
    }

    /**
     * Emits [EVENT_UI_CLICK] with `screen` / `action` params. The old concatenated name is gone —
     * see the class note.
     */
    @Deprecated("Use Tracker.track(SimpleEvent(\"app_ui_click\", mapOf(\"screen\" to …, \"action\" to …))).")
    fun logEventClick(activityName: String, eventName: String, params: Bundle?): ITGTrackingHelper {
        Tracker.track(
            EVENT_UI_CLICK,
            params.toParams() + mapOf(PARAM_SCREEN to activityName, PARAM_ACTION to eventName),
        )
        return this
    }

    @Deprecated(
        "Use Tracker.screen(screenName).",
        ReplaceWith("Tracker.screen(screenName)", "io.trackkit.Tracker"),
    )
    fun addScreenTrack(screenName: String): ITGTrackingHelper {
        Tracker.screen(screenName, screenName)
        return this
    }

    /**
     * Emits [EVENT_SCREEN_FLOW] with `from_screen` / `to_screen` params. The old concatenated name
     * is gone — see the class note.
     */
    @Deprecated("Use Tracker.track(SimpleEvent(\"app_screen_flow\", mapOf(\"from_screen\" to …, \"to_screen\" to …))).")
    fun fromScreenToScreen(fromScreen: String, toScreen: String): ITGTrackingHelper {
        Tracker.track(
            EVENT_SCREEN_FLOW,
            mapOf(PARAM_FROM_SCREEN to fromScreen, PARAM_TO_SCREEN to toScreen),
        )
        return this
    }

    private const val PARAM_SCREEN = "screen"
    private const val PARAM_ACTION = "action"
    private const val PARAM_FROM_SCREEN = "from_screen"
    private const val PARAM_TO_SCREEN = "to_screen"

    private fun Bundle?.toParams(): Map<String, Any?> {
        if (this == null || isEmpty) return emptyMap()
        val out = LinkedHashMap<String, Any?>(size())
        for (key in keySet()) {
            @Suppress("DEPRECATION")
            out[key] = get(key)
        }
        return out
    }

    object Params {}
}
